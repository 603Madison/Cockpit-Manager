package app.cockpitmanager.cockpit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Method calls to one D-Bus service through a "dbus-json3" channel. Arguments
 * and replies use Cockpit's JSON encoding of D-Bus types (variants are
 * {"t": signature, "v": value}).
 */
class DBusClient private constructor(private val channel: CockpitChannel, scope: CoroutineScope) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonArray>>()
    private val ids = AtomicInteger(0)
    @Volatile private var failure: CockpitException? = null

    init {
        scope.launch {
            for (event in channel.events) {
                when (event) {
                    is ChannelEvent.Data -> handle(event.text)
                    is ChannelEvent.Control -> Unit
                    is ChannelEvent.Closed -> {
                        val e = CockpitException(Problems.describe(event.problem ?: "disconnected", event.string("message")), event.problem)
                        failure = e
                        pending.values.forEach { it.completeExceptionally(e) }
                        pending.clear()
                    }
                }
            }
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg.string("id") ?: return
        val call = pending.remove(id) ?: return
        val reply = msg["reply"]
        val error = msg["error"]
        when {
            reply != null -> {
                val outer = runCatching { reply.jsonArray }.getOrNull()
                val args = outer?.firstOrNull()
                call.complete(if (args == null || args is JsonNull) JsonArray(emptyList()) else args.jsonArray)
            }
            error != null -> {
                val parts = runCatching { error.jsonArray }.getOrNull()
                val name = parts?.getOrNull(0)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: "org.freedesktop.DBus.Error.Failed"
                val detail = parts?.getOrNull(1)?.let { runCatching { it.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull() }
                call.completeExceptionally(DBusException(name, detail ?: name))
            }
            else -> call.complete(JsonArray(emptyList()))
        }
    }

    /** Calls a method and returns its out arguments. [signature] is the in-argument type signature, e.g. "ss". */
    suspend fun call(
        path: String,
        iface: String,
        method: String,
        args: List<JsonElement> = emptyList(),
        signature: String? = null,
        timeoutMillis: Long = 30_000,
    ): JsonArray {
        failure?.let { throw it }
        val id = ids.incrementAndGet().toString()
        val deferred = CompletableDeferred<JsonArray>()
        pending[id] = deferred
        val message = buildMap<String, JsonElement> {
            put("call", JsonArray(listOf(JsonPrimitive(path), JsonPrimitive(iface), JsonPrimitive(method), JsonArray(args))))
            put("id", JsonPrimitive(id))
            signature?.let { put("type", JsonPrimitive(it)) }
        }
        channel.send(JsonObject(message).toString())
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** All properties of [iface] on [path], unwrapped from their variants. */
    suspend fun getAll(path: String, iface: String): Map<String, JsonElement> {
        val out = call(path, "org.freedesktop.DBus.Properties", "GetAll", listOf(JsonPrimitive(iface)), "s")
        val dict = out.firstOrNull() as? JsonObject ?: return emptyMap()
        return dict.mapValues { (_, v) -> (v as? JsonObject)?.get("v") ?: v }
    }

    fun close() = channel.close()

    companion object {
        fun open(transport: CockpitTransport, scope: CoroutineScope, name: String, bus: String = "system", superuser: Superuser = Superuser.TRY): DBusClient {
            val channel = transport.channel(
                mapOf("payload" to JsonPrimitive("dbus-json3"), "bus" to JsonPrimitive(bus), "name" to JsonPrimitive(name)),
                superuser,
            )
            return DBusClient(channel, scope)
        }
    }
}
