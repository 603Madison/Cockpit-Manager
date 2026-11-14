package app.cockpitmanager.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.cockpitmanager.cockpit.CockpitLogin
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.LoginStep
import app.cockpitmanager.cockpit.ServerUrl
import app.cockpitmanager.terminal.TerminalSession
import app.cockpitmanager.ui.terminal.TerminalScreen
import app.cockpitmanager.ui.theme.CockpitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertTrue

/**
 * The real Terminal screen driving a real login shell through a real Cockpit
 * (skipped without COCKPIT_LIVE_URL/USER/PASSWORD): open, output, clear,
 * full-screen programs and resizes, as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, qualifiers = "w400dp-h760dp-xxhdpi")
class LiveTerminalScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val address = System.getenv("COCKPIT_LIVE_URL")
    private val user = System.getenv("COCKPIT_LIVE_USER")
    private val password = System.getenv("COCKPIT_LIVE_PASSWORD")

    @Test
    fun realShell() {
        assumeTrue("Set COCKPIT_LIVE_URL/USER/PASSWORD to run", address != null && user != null && password != null)
        val url = ServerUrl.parse(address!!)!!
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val cookie = runBlocking { (CockpitLogin(client, url).start(user!!, password!!, adminAccess = false) as LoginStep.Success).cookie }
        // As in the app: the session runs on the main thread.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val transport = CockpitTransport(client, url, cookie, scope)
        try {
            runBlocking { transport.open() }
            val session = TerminalSession(transport, scope)
            var height by mutableStateOf(700.dp)
            compose.setContent {
                CockpitTheme(darkTheme = true) {
                    Box(Modifier.fillMaxWidth().height(height)) { TerminalScreen(session, 13f) }
                }
            }
            fun waitFor(what: String, timeout: Long = 20_000, check: (String) -> Boolean) {
                try {
                    compose.waitUntil(what, timeout) {
                        // Shell output arrives on network threads and is handed to the main thread; let it run.
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        check(session.frame.value.allText())
                    }
                } catch (e: Throwable) {
                    println("Screen while waiting for $what (${session.state.value}):\n" + session.frame.value.allText().takeLast(1500))
                    throw e
                }
            }

            // The screen starts the shell itself; the prompt arrives.
            waitFor("prompt") { it.contains("$user@") || it.contains("$ ") }
            session.send("seq 1 400\r")
            waitFor("output") { it.contains("\n400") }

            // The keyboard opens.
            height = 320.dp
            compose.waitForIdle()
            session.send("clear\r")
            waitFor("cleared") { !it.contains("\n399") }

            // A full-screen program, then back.
            session.send("top\r")
            waitFor("top", 30_000) { it.contains("load average") }
            height = 700.dp
            compose.waitForIdle()
            session.send("q")
            // top leaves its last screen behind on purpose; the prompt comes back below it.
            waitFor("back from top") { it.trimEnd().endsWith("$") }

            // less uses the alternate screen: its text goes away again when it quits.
            session.send("less /etc/os-release\r")
            waitFor("less") { it.contains("VERSION_ID") }
            height = 320.dp
            compose.waitForIdle()
            session.send("q")
            waitFor("back from less") { !it.contains("VERSION_ID") && it.trimEnd().endsWith("$") }

            session.send("echo done-\$((40+2))\r")
            waitFor("still working") { it.contains("done-42") }
            assertTrue(session.state.value is TerminalSession.State.Running)
            session.stop()
        } finally {
            transport.close()
            scope.cancel()
        }
    }
}
