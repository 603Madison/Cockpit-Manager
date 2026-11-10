package app.cockpitmanager.cockpit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One Cockpit protocol message: a channel id, a newline, then the payload. The control channel is "". */
data class Frame(val channel: String, val payload: String) {
    val isControl: Boolean get() = channel.isEmpty()

    fun encode(): String = "$channel\n$payload"

    companion object {
        fun parse(message: String): Frame? {
            val nl = message.indexOf('\n')
            if (nl < 0) return null
            return Frame(message.substring(0, nl), message.substring(nl + 1))
        }

        fun control(json: JsonObject) = Frame("", json.toString())
    }
}

/** Whether a channel runs as the signed-in user or (when the session has administrative access) as root. */
enum class Superuser(val wire: String?) { NONE(null), TRY("try"), REQUIRE("require") }

/** The bridge's opening message: who and what is on the other end. */
data class InitInfo(val host: String, val osRelease: Map<String, String>, val capabilities: List<String>) {
    val osName: String?
        get() = osRelease["PRETTY_NAME"] ?: listOfNotNull(osRelease["NAME"], osRelease["VERSION"]).joinToString(" ").ifBlank { null }

    companion object {
        fun of(json: JsonObject): InitInfo {
            val os = (json["os-release"] as? JsonObject)?.mapValues { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }
            val caps = runCatching { json["capabilities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            return InitInfo(
                host = runCatching { json["host"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "localhost",
                osRelease = os.orEmpty(),
                capabilities = caps.orEmpty(),
            )
        }
    }
}

sealed interface ChannelEvent {
    data class Data(val text: String) : ChannelEvent
    data class Control(val message: JsonObject) : ChannelEvent

    /** Always the last event. [problem] is null for a normal close. */
    data class Closed(val problem: String?, val message: JsonObject) : ChannelEvent {
        fun string(key: String): String? = runCatching { message[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        fun int(key: String): Int? = runCatching { message[key]?.jsonPrimitive?.intOrNull }.getOrNull()
    }
}

/** One open channel. Events arrive in [events] until a [ChannelEvent.Closed]. */
class CockpitChannel internal constructor(val id: String, private val transport: CockpitTransport) {
    private val inbox = Channel<ChannelEvent>(Channel.UNLIMITED)
    val events: ReceiveChannel<ChannelEvent> get() = inbox

    @Volatile
    var isClosed = false
        private set

    fun send(data: String) {
        if (!isClosed) transport.sendFrame(Frame(id, data))
    }

    fun control(command: String, fields: Map<String, JsonElement> = emptyMap()) {
        if (isClosed) return
        transport.sendFrame(
            Frame.control(
                JsonObject(fields + mapOf("command" to JsonPrimitive(command), "channel" to JsonPrimitive(id))),
            ),
        )
    }

    /** No more data will be sent this way (e.g. closes a process's stdin). */
    fun done() = control("done")

    fun close(problem: String? = null) {
        if (isClosed) return
        control("close", if (problem != null) mapOf("problem" to JsonPrimitive(problem)) else emptyMap())
        deliver(ChannelEvent.Closed(problem, JsonObject(emptyMap())))
    }

    internal fun deliver(event: ChannelEvent) {
        if (isClosed) return
        if (event is ChannelEvent.Closed) {
            isClosed = true
            transport.forget(id)
            inbox.trySend(event)
            inbox.close()
        } else {
            inbox.trySend(event)
        }
    }
}

/**
 * Cockpit's WebSocket transport (protocol "cockpit1") to cockpit-ws, which
 * forwards channels to the bridge running as the signed-in user.
 */
class CockpitTransport(
    private val client: OkHttpClient,
    private val url: ServerUrl,
    private val cookie: String,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Connecting : State
        data class Open(val init: InitInfo) : State
        data class Closed(val problem: String?, val message: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val channels = ConcurrentHashMap<String, CockpitChannel>()
    private val counter = AtomicInteger(0)
    private val init = CompletableDeferred<InitInfo>()
    @Volatile private var seed = ""
    @Volatile private var socket: WebSocket? = null
    @Volatile private var gotMessage = false
    private var health: Job? = null

    /** Opens the socket and waits for the bridge's init. */
    suspend fun open(timeoutMillis: Long = 30_000): InitInfo {
        val request = Request.Builder()
            .url(url.socketUrl)
            .header("Cookie", cookie)
            // cockpit-ws refuses WebSockets whose Origin doesn't match the address it was reached on.
            .header("Origin", url.origin)
            .header("Sec-WebSocket-Protocol", "cockpit1")
            .build()
        socket = client.newWebSocket(request, Listener())
        val info = withTimeoutOrNull(timeoutMillis) { init.await() }
        if (info == null) {
            close("timeout")
            throw CockpitException(Problems.describe("timeout"), "timeout")
        }
        health = scope.launch {
            while (isActive && state.value is State.Open) {
                delay(HEALTH_INTERVAL)
                sendFrame(Frame.control(buildJsonObject { put("command", "ping") }))
                if (!gotMessage) {
                    shutDown("timeout", null)
                    break
                }
                gotMessage = false
            }
        }
        return info
    }

    /** Opens a channel with Cockpit "open" options (payload, spawn, bus, ...). */
    fun channel(options: Map<String, JsonElement>, superuser: Superuser = Superuser.NONE): CockpitChannel {
        val id = "$seed${counter.incrementAndGet()}"
        val channel = CockpitChannel(id, this)
        if (state.value is State.Closed) {
            channel.deliver(ChannelEvent.Closed("disconnected", JsonObject(emptyMap())))
            return channel
        }
        channels[id] = channel
        val open = buildMap {
            putAll(options)
            put("command", JsonPrimitive("open"))
            put("channel", JsonPrimitive(id))
            superuser.wire?.let { put("superuser", JsonPrimitive(it)) }
        }
        sendFrame(Frame.control(JsonObject(open)))
        return channel
    }

    internal fun sendFrame(frame: Frame) {
        socket?.send(frame.encode())
    }

    internal fun forget(id: String) {
        channels.remove(id)
    }

    /** Signs out (invalidating the session cookie) and closes everything. */
    fun close(problem: String? = null) {
        if (state.value is State.Closed) return
        if (problem == null) sendFrame(Frame.control(buildJsonObject { put("command", "logout"); put("disconnect", true) }))
        shutDown(problem, null)
    }

    private fun shutDown(problem: String?, message: String?) {
        if (state.value is State.Closed) return
        _state.value = State.Closed(problem, message)
        health?.cancel()
        socket?.close(1000, null)
        socket = null
        val closing = ChannelEvent.Closed(problem ?: "disconnected", JsonObject(emptyMap()))
        channels.values.toList().forEach { it.deliver(closing) }
        channels.clear()
        if (!init.isCompleted) init.completeExceptionally(CockpitException(Problems.describe(problem, message), problem))
    }

    internal fun dispatch(text: String) {
        gotMessage = true
        val frame = Frame.parse(text) ?: return
        if (!frame.isControl) {
            channels[frame.channel]?.deliver(ChannelEvent.Data(frame.payload))
            return
        }
        val control = runCatching { Json.parseToJsonElement(frame.payload).jsonObject }.getOrNull() ?: return
        val command = control.string("command")
        val channelId = control.string("channel")
        when {
            command == "init" -> handleInit(control)
            !init.isCompleted -> {
                // Anything before init is a failure report (e.g. "close" with a problem).
                shutDown(control.string("problem") ?: "protocol-error", control.string("message"))
            }
            command == "ping" -> sendFrame(Frame.control(JsonObject(control + ("command" to JsonPrimitive("pong")))))
            command == "pong" || command == "hint" -> Unit
            channelId != null -> {
                val channel = channels[channelId] ?: return
                if (command == "close") {
                    channel.deliver(ChannelEvent.Closed(control.string("problem"), control))
                } else {
                    channel.deliver(ChannelEvent.Control(control))
                }
            }
            command == "close" -> shutDown(control.string("problem") ?: "disconnected", control.string("message"))
        }
    }

    private fun handleInit(control: JsonObject) {
        control.string("problem")?.let { problem ->
            shutDown(problem, control.string("message"))
            return
        }
        val version = runCatching { control["version"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (version != 1) {
            shutDown("not-supported", "Unsupported Cockpit protocol version $version")
            return
        }
        control.string("channel-seed")?.let { seed = it }
        val info = InitInfo.of(control)
        if (_state.value is State.Connecting) _state.value = State.Open(info)
        init.complete(info)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(Frame.control(buildJsonObject { put("command", "init"); put("version", 1) }).encode())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = dispatch(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            shutDown(reason.ifBlank { null } ?: "disconnected", null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = shutDown("disconnected", null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when (response?.code) {
                401 -> "The session expired. Sign in again."
                403 -> "The server refused the connection (origin check). If Cockpit is behind a proxy, add its address to Origins in /etc/cockpit/cockpit.conf."
                null -> Problems.fromNetwork(t, url).message
                else -> "The server refused the connection (HTTP ${response.code})"
            }
            response?.close()
            shutDown(if (response?.code == 401) "authentication-failed" else "disconnected", message)
        }
    }

    companion object {
        private const val HEALTH_INTERVAL = 30_000L
    }
}

internal fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
