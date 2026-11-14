package app.cockpitmanager.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.ServerUrl
import app.cockpitmanager.terminal.TerminalSession
import app.cockpitmanager.ui.terminal.TerminalScreen
import app.cockpitmanager.ui.theme.CockpitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The Terminal tab as people reach it: the screen opens first and starts the
 * shell itself, then the size changes (keyboard) and output arrives,
 * including things that shrink the line count (clear, full-screen programs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h760dp-xxhdpi")
class TerminalScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun session(): TerminalSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Never opened: channels are created but nothing is sent, which is all the screen needs.
        val transport = CockpitTransport(OkHttpClient(), ServerUrl(true, "example.invalid", 9090), "cockpit=x", scope)
        return TerminalSession(transport, scope)
    }

    @Test
    fun opensWithLargeSystemText() {
        // Fewer than the emulator's initial 24 rows fit on screen.
        RuntimeEnvironment.setFontScale(1.6f)
        val session = session()
        compose.setContent { CockpitTheme(darkTheme = true) { TerminalScreen(session, 15f) } }
        compose.waitForIdle()
        assertTrue(session.state.value is TerminalSession.State.Running)
        assertTrue(session.emulator.rows < 24, "the screen sized the shell to fit (${session.emulator.rows} rows)")
    }

    @Test
    fun survivesKeyboardResizeClearAndFullScreenPrograms() {
        val session = session()
        var height by mutableStateOf(700.dp)
        compose.setContent {
            CockpitTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().height(height)) { TerminalScreen(session, 13f) }
            }
        }
        compose.waitForIdle()
        // A long login banner and prompt, so there's scrollback.
        compose.runOnIdle { session.receive((1..200).joinToString("") { "line $it of the message of the day\r\n" } + "\u001b]0;admin@beacon:~\u0007admin@beacon:~$ ") }
        compose.waitForIdle()

        // The keyboard opens: much less room.
        height = 300.dp
        compose.waitForIdle()
        // `clear` wipes the scrollback (ED 3), then tmux/vim switch to the alternate screen.
        compose.runOnIdle { session.receive("\u001b[H\u001b[2J\u001b[3J") }
        compose.runOnIdle { session.receive("\u001b[?1049h\u001b[H\u001b[2Jfull screen") }
        compose.waitForIdle()
        height = 700.dp
        compose.waitForIdle()
        compose.runOnIdle { session.receive("\u001b[?1049l") }
        compose.waitForIdle()
    }
}
