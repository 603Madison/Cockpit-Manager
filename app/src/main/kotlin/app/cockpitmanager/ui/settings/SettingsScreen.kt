package app.cockpitmanager.ui.settings

import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.cockpitmanager.data.AppSettings
import app.cockpitmanager.data.TerminalTextSize
import app.cockpitmanager.data.ThemeMode
import app.cockpitmanager.ui.components.ChoiceDropdown
import app.cockpitmanager.ui.components.DetailRow
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.LabeledSwitch
import app.cockpitmanager.ui.components.PageScaffold
import app.cockpitmanager.ui.components.SectionCard
import app.cockpitmanager.ui.components.SegmentedChoice

@Composable
fun SettingsScreen(
    settings: AppSettings,
    appVersion: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold("Settings", onBack) {
        SectionCard("Appearance") {
            ChoiceDropdown("Light or dark", ThemeMode.entries, settings.themeMode, { it.label }, { mode -> onUpdate { it.copy(themeMode = mode) } })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LabeledSwitch(
                    title = "Wallpaper colors",
                    subtitle = "Use Material You colors instead of the app's teal",
                    checked = settings.dynamicColor,
                    onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } },
                )
            }
        }
        SectionCard("Terminal") {
            Text("Text size")
            SegmentedChoice(TerminalTextSize.entries, settings.terminalTextSize, { it.label }, { size -> onUpdate { it.copy(terminalTextSize = size) } })
        }
        SectionCard("About & privacy") {
            DetailRow("Version", appVersion.ifBlank { "–" })
            Hint("Cockpit Manager talks only to the servers you add, the same way Cockpit's own web console does: it signs in at /cockpit/login and speaks Cockpit's protocol over a WebSocket.")
            Hint("Saved passwords are encrypted with a key in the Android Keystore that never leaves this phone, and nothing is included in backups.")
            Hint("Set in Lexend (SIL Open Font License). Not affiliated with Red Hat or the Cockpit project.")
        }
    }
}
