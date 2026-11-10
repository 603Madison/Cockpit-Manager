package app.cockpitmanager.cockpit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The transport against a scripted cockpit-ws: handshake, headers, channel routing, ping, close. */
class CockpitTransportTest {
    private val server = MockWebServer()
    private val fromClient = LinkedBlockingQueue<String>()
    @Volatile private var serverSocket: WebSocket? = null

    @Before
    fun setUp() {
        server.start()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket = webSocket
                    webSocket.send("\n" + """{"command":"init","version":1,"channel-seed":"1:","host":"localhost","os-release":{"PRETTY_NAME":"Fedora Linux 43"}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient.add(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }).build(),
        )
    }

    @After
    fun tearDown() {
        runCatching { serverSocket?.close(1000, null) }
        server.close()
    }

    private fun next(): String = fromClient.poll(5, TimeUnit.SECONDS) ?: error("nothing from the client")

    @Test
    fun handshakeChannelsAndClose() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = ServerUrl(https = false, host = "127.0.0.1", port = server.port)
        val transport = CockpitTransport(OkHttpClient(), url, "cockpit=abc", scope)
        val init = withTimeout(5000) { transport.open() }
        assertEquals("Fedora Linux 43", init.osName)

        val upgrade = server.takeRequest()
        assertEquals("/cockpit/socket", upgrade.url.encodedPath)
        assertEquals("cockpit=abc", upgrade.headers["Cookie"])
        assertEquals("http://127.0.0.1:${server.port}", upgrade.headers["Origin"])
        assertEquals("cockpit1", upgrade.headers["Sec-WebSocket-Protocol"])

        // The client's own init comes first.
        val clientInit = Json.parseToJsonElement(next().removePrefix("\n")).jsonObject
        assertEquals("init", clientInit["command"]!!.jsonPrimitive.content)

        // Opening a channel uses the server's seed.
        val channel = transport.channel(mapOf("payload" to kotlinx.serialization.json.JsonPrimitive("echo")), Superuser.TRY)
        assertEquals("1:1", channel.id)
        val open = Json.parseToJsonElement(next().removePrefix("\n")).jsonObject
        assertEquals("open", open["command"]!!.jsonPrimitive.content)
        assertEquals("1:1", open["channel"]!!.jsonPrimitive.content)
        assertEquals("try", open["superuser"]!!.jsonPrimitive.content)

        channel.send("payload")
        assertEquals("1:1\npayload", next())

        // Pings are answered with pongs.
        serverSocket!!.send("\n" + """{"command":"ping","x":1}""")
        val pong = Json.parseToJsonElement(next().removePrefix("\n")).jsonObject
        assertEquals("pong", pong["command"]!!.jsonPrimitive.content)

        // Data and close are routed to the channel.
        serverSocket!!.send("1:1\nhello")
        serverSocket!!.send("\n" + """{"command":"close","channel":"1:1","problem":"not-found"}""")
        val events = withTimeout(5000) { channel.events.receiveAsList() }
        assertEquals(ChannelEvent.Data("hello"), events[0])
        val closed = assertIs<ChannelEvent.Closed>(events[1])
        assertEquals("not-found", closed.problem)
        assertTrue(channel.isClosed)

        // A transport-wide close ends everything.
        val other = transport.channel(mapOf("payload" to kotlinx.serialization.json.JsonPrimitive("null")))
        serverSocket!!.send("\n" + """{"command":"close","problem":"terminated"}""")
        val last = withTimeout(5000) { other.events.receiveAsList() }.last()
        assertIs<ChannelEvent.Closed>(last)
        val state = transport.state.value
        assertIs<CockpitTransport.State.Closed>(state)
        assertEquals("terminated", state.problem)
        scope.cancel()
    }

    private suspend fun kotlinx.coroutines.channels.ReceiveChannel<ChannelEvent>.receiveAsList(): List<ChannelEvent> {
        val out = mutableListOf<ChannelEvent>()
        for (e in this) out += e
        return out
    }
}
