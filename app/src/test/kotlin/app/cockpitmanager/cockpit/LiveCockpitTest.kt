package app.cockpitmanager.cockpit

import app.cockpitmanager.system.SystemFacts
import app.cockpitmanager.system.hasAdminAccess
import app.cockpitmanager.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the app's client against a real Cockpit. Skipped unless
 * COCKPIT_LIVE_URL, COCKPIT_LIVE_USER and COCKPIT_LIVE_PASSWORD are set, e.g.
 *
 *   COCKPIT_LIVE_URL=http://127.0.0.1:9090 COCKPIT_LIVE_USER=tester COCKPIT_LIVE_PASSWORD=... \
 *     ./gradlew :app:testDebugUnitTest --tests '*LiveCockpitTest*'
 */
class LiveCockpitTest {
    private val address = System.getenv("COCKPIT_LIVE_URL")
    private val user = System.getenv("COCKPIT_LIVE_USER")
    private val password = System.getenv("COCKPIT_LIVE_PASSWORD")

    private fun withSession(block: suspend (CockpitTransport, CoroutineScope) -> Unit): Unit = runBlocking {
        assumeTrue("Set COCKPIT_LIVE_URL/USER/PASSWORD to run", address != null && user != null && password != null)
        val url = ServerUrl.parse(address!!)!!
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val step = CockpitLogin(client, url).start(user!!, password!!, adminAccess = true)
        val success = assertIs<LoginStep.Success>(step)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = CockpitTransport(client, url, success.cookie, scope)
        try {
            val init = transport.open()
            println("Live init: $init")
            withTimeout(60_000) { block(transport, scope) }
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    @Test
    fun runsProcessesAndReadsFacts() = withSession { transport, _ ->
        val result = transport.run(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals("out\n", result.output)
        assertEquals(3, result.exitStatus)
        assertEquals("err", result.errorOutput?.trim())

        val facts = SystemFacts.read(transport)
        assertTrue(facts.hostname.isNotBlank())
        assertTrue(facts.cpuCount >= 1)
        assertTrue(facts.memTotalBytes > 0)
        assertTrue(facts.os.isNotBlank(), "PRETTY_NAME from /etc/os-release")
        println("Live facts: $facts")
    }

    @Test
    fun streamsMetrics() = withSession { transport, _ ->
        val samples = transport.metrics(
            listOf(MetricSpec("cpu.basic.user", "rate"), MetricSpec("memory.used"), MetricSpec("mount.total")),
            intervalMillis = 300,
        ).take(3).toList()
        assertEquals(3, samples.size)
        assertNotNull(samples.last().scalar(0), "rate after the first sample")
        assertTrue(samples.last().scalar(1)!! > 0)
        assertTrue(samples.last().instances(2).isNotEmpty(), "mounts are instanced")
    }

    @Test
    fun callsDBus() = withSession { transport, scope ->
        val bus = DBusClient.open(transport, scope, "org.freedesktop.DBus", superuser = Superuser.NONE)
        val names = bus.call("/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames").first() as JsonArray
        assertTrue(names.any { it.jsonPrimitive.content == "org.freedesktop.DBus" })
        bus.close()
    }

    @Test
    fun hasAdministrativeAccessThroughSudo() = withSession { transport, _ ->
        assertTrue(transport.hasAdminAccess(), "tester is in the sudo group and signed in with X-Superuser: any")
        val whoami = transport.runChecked(listOf("id", "-un"), Superuser.REQUIRE)
        assertEquals("root", whoami.trim())
    }

    @Test
    fun interactiveShellInAPty() = withSession { transport, scope ->
        val terminal = TerminalSession(transport, scope)
        terminal.start(rows = 24, cols = 80)
        delay(800)
        terminal.send("echo hello-\$((40+2)); printf '\\033[1;31mred\\033[0m\\n'\r")
        terminal.version.first { terminal.emulator.allText().contains("hello-42") }
        val text = terminal.emulator.allText()
        assertTrue(text.contains("hello-42"), text)
        terminal.send("exit\r")
        terminal.state.first { it is TerminalSession.State.Ended }
    }
}
