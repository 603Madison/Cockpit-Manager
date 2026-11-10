package app.cockpitmanager.cockpit

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** An HTTP request's outcome, from a REST API on a server-side unix socket. */
class HttpResult(val status: Int, val reason: String, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = status in 200..299
}

/** A REST API answered with an error status; [message] is its own explanation when it gave one. */
class HttpApiException(val status: Int, message: String) : CockpitException(message)

private fun query(params: Map<String, String>): String =
    if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }

/** The error text APIs like Podman's put in their JSON bodies ("message" or "cause"). */
fun apiErrorMessage(body: String, fallback: String): String {
    val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body.trim().ifBlank { fallback }
    fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    return s("message") ?: s("cause") ?: fallback
}

/**
 * One HTTP request to a unix socket on the server, through the bridge
 * ("http-stream2" channel), the way cockpit.http() makes them. Binary mode
 * keeps multi-byte text intact across frames.
 */
suspend fun CockpitTransport.httpRequest(
    socket: String,
    method: String,
    path: String,
    params: Map<String, String> = emptyMap(),
    body: String? = null,
    superuser: Superuser = Superuser.NONE,
    timeoutMillis: Long = 60_000,
): HttpResult {
    val options = buildMap<String, JsonElement> {
        put("payload", JsonPrimitive("http-stream2"))
        put("unix", JsonPrimitive(socket))
        put("method", JsonPrimitive(method))
        put("path", JsonPrimitive(path + query(params)))
        put("binary", JsonPrimitive("raw"))
        if (body != null) put("headers", JsonObject(mapOf("Content-Type" to JsonPrimitive("application/json"))))
    }
    val channel = channel(options, superuser)
    if (!body.isNullOrEmpty()) channel.send(body.toByteArray(Charsets.UTF_8))
    channel.done()
    return try {
        withTimeoutAsError(timeoutMillis, "$socket didn't answer in time") {
            var status = 0
            var reason = ""
            val out = ByteArrayOutputStream()
            for (event in channel.events) {
                when (event) {
                    is ChannelEvent.Control -> if (event.message.string("command") == "response") {
                        status = runCatching { event.message["status"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
                        reason = event.message.string("reason").orEmpty()
                    }
                    is ChannelEvent.Bytes -> out.write(event.data)
                    is ChannelEvent.Data -> out.write(event.text.toByteArray(Charsets.UTF_8))
                    is ChannelEvent.Closed -> {
                        if (event.problem != null) {
                            throw CockpitException(
                                when (event.problem) {
                                    "not-found" -> "The service isn't running on the server ($socket)"
                                    else -> Problems.describe(event.problem, event.string("message"))
                                },
                                event.problem,
                            )
                        }
                        return@withTimeoutAsError HttpResult(status, reason, out.toByteArray())
                    }
                }
            }
            throw CockpitException(Problems.describe("disconnected"), "disconnected")
        }
    } finally {
        channel.close()
    }
}

/** Like [httpRequest], but an error status is an [HttpApiException] with the API's own message. */
suspend fun CockpitTransport.httpCall(
    socket: String,
    method: String,
    path: String,
    params: Map<String, String> = emptyMap(),
    body: String? = null,
    superuser: Superuser = Superuser.NONE,
    timeoutMillis: Long = 60_000,
): String {
    val result = httpRequest(socket, method, path, params, body, superuser, timeoutMillis)
    if (!result.ok) throw HttpApiException(result.status, apiErrorMessage(result.text, "${result.status} ${result.reason}".trim()))
    return result.text
}

/**
 * A streaming GET (events, stats, logs) over a raw stream to the socket, as
 * cockpit-podman's monitor() does: an HTTP/1.0 request, the status line and
 * headers, then body bytes as they arrive until either side closes.
 */
fun CockpitTransport.httpStream(socket: String, path: String, superuser: Superuser = Superuser.NONE): Flow<ByteArray> = callbackFlow {
    val channel = channel(
        mapOf("payload" to JsonPrimitive("stream"), "unix" to JsonPrimitive(socket), "binary" to JsonPrimitive("raw")),
        superuser,
    )
    channel.send("GET $path HTTP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    launch {
        val head = ByteArrayOutputStream()
        var inBody = false
        for (event in channel.events) {
            val bytes = when (event) {
                is ChannelEvent.Bytes -> event.data
                is ChannelEvent.Data -> event.text.toByteArray(Charsets.UTF_8)
                is ChannelEvent.Control -> continue
                is ChannelEvent.Closed -> {
                    if (event.problem != null) {
                        close(CockpitException(Problems.describe(event.problem, event.string("message")), event.problem))
                    } else {
                        close()
                    }
                    break
                }
            }
            if (inBody) {
                send(bytes)
                continue
            }
            head.write(bytes)
            val all = head.toByteArray()
            val split = HttpHead.find(all)
            if (split < 0) continue
            val headers = String(all, 0, split, Charsets.ISO_8859_1)
            val rest = all.copyOfRange(split + 4, all.size)
            if (!HttpHead.isOk(headers)) {
                close(HttpApiException(HttpHead.status(headers), apiErrorMessage(String(rest, Charsets.UTF_8), headers.lineSequence().first())))
                break
            }
            inBody = true
            if (rest.isNotEmpty()) send(rest)
        }
    }
    awaitClose { channel.close() }
}

internal object HttpHead {
    /** Index of the blank line (\r\n\r\n) ending the headers, or -1. */
    fun find(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    fun status(headers: String): Int = headers.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0

    fun isOk(headers: String) = status(headers) == 200
}

/** Splits a byte stream into newline-terminated UTF-8 lines (safe for UTF-8: '\n' never occurs inside a character). */
class ByteLineBuffer {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                pending.write(bytes, start, i - start)
                lines += String(pending.toByteArray(), Charsets.UTF_8)
                pending.reset()
                start = i + 1
            }
        }
        pending.write(bytes, start, bytes.size - start)
        return lines
    }
}
