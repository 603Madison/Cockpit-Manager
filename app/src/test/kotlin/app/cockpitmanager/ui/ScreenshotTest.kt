package app.cockpitmanager.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.cockpitmanager.cockpit.CertificateInfo
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.ServerUrl
import app.cockpitmanager.data.AppSettings
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.system.JournalEntry
import app.cockpitmanager.system.JournalQuery
import app.cockpitmanager.system.LogLevel
import app.cockpitmanager.system.ServiceDetails
import app.cockpitmanager.system.ServiceUnit
import app.cockpitmanager.system.SystemFacts
import app.cockpitmanager.terminal.TerminalSession
import app.cockpitmanager.ui.components.Backdrop
import app.cockpitmanager.ui.logs.LogsScreen
import app.cockpitmanager.ui.logs.LogsState
import app.cockpitmanager.ui.overview.Mount
import app.cockpitmanager.ui.overview.OverviewScreen
import app.cockpitmanager.ui.overview.OverviewState
import app.cockpitmanager.ui.servers.ConnectionDialogs
import app.cockpitmanager.ui.servers.ServerEditScreen
import app.cockpitmanager.ui.servers.ServersScreen
import app.cockpitmanager.ui.services.ServiceDetailScreen
import app.cockpitmanager.ui.services.ServiceDetailState
import app.cockpitmanager.ui.services.ServicesScreen
import app.cockpitmanager.ui.services.ServicesState
import app.cockpitmanager.ui.settings.SettingsScreen
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Renders each screen with realistic data to PNGs in
 * app/build/outputs/screenshots, for visual review. Not a pixel-diff test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private val server = ServerConfig(
        id = "atlas", name = "Atlas", address = "atlas.home.arpa", user = "admin",
        lastConnectedMillis = now - 12 * 60_000, pinnedSha256 = null,
    )
    private val servers = listOf(
        server,
        ServerConfig(id = "nas", name = "", address = "https://192.168.1.40:9090", user = "admin", adminAccess = false, lastConnectedMillis = now - 3 * 86_400_000L),
        ServerConfig(id = "vps", name = "Web VPS", address = "https://vps.example.com", user = "deploy"),
    )

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { CockpitTheme(darkTheme = dark) { content() } }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Chrome(tab: SessionTab, failed: Int = 2, content: @Composable () -> Unit) {
        SessionChrome(
            server = server, selected = tab, failedUnits = failed, lostMessage = null,
            snackbar = remember { SnackbarHostState() },
            onTab = {}, onSettings = {}, onDisconnect = {}, onReconnect = {}, content = content,
        )
    }

    // ---- Servers ----

    @Test
    fun serversEmpty() {
        render(dark = false) { ServersScreen(emptyList(), {}, {}, {}, {}, {}) }
        save("servers_empty")
    }

    @Test
    fun serversLight() {
        render(dark = false) { ServersScreen(servers, {}, {}, {}, {}, {}) }
        save("servers_light")
    }

    @Test
    fun serversDark() {
        render(dark = true) { ServersScreen(servers, {}, {}, {}, {}, {}) }
        save("servers_dark")
    }

    @Test
    fun addServer() {
        render(dark = false) { ServerEditScreen(null, hasSavedPassword = false, onSave = { _, _, _ -> }, onForgetCertificate = {}, onBack = {}) }
        save("server_add")
    }

    @Test
    fun certificatePrompt() {
        val info = CertificateInfo(
            sha256 = "3A:7F:0C:91:5E:22:B8:4D:6A:19:F0:C3:88:2E:57:D1:A4:0B:96:3C:E7:51:28:FD:6B:C4:19:8E:72:A0:35:DE",
            subject = "CN=atlas.home.arpa,O=4f9d1c2b3a6e4b7c8d9e0f1a2b3c4d5e",
            issuer = "CN=atlas.home.arpa,O=4f9d1c2b3a6e4b7c8d9e0f1a2b3c4d5e",
            notBeforeMillis = now - 86_400_000L * 30, notAfterMillis = now + 86_400_000L * 335, selfSigned = true,
        )
        render(dark = false) {
            Backdrop {
                ServersScreen(servers, {}, {}, {}, {}, {})
                ConnectionDialogs(ConnectionState.NeedsCertificate(server, info, changed = false), { _, _, _ -> }, { _, _ -> }, {}, {}, {}, {})
            }
        }
        save("dialog_certificate")
    }

    // ---- Overview ----

    private val facts = SystemFacts(
        hostname = "atlas.home.arpa", os = "Red Hat Enterprise Linux 10.0 (Coughlan)", kernel = "6.12.0-55.12.1.el10_0.x86_64",
        arch = "x86_64", cpuCount = 8, cpuModel = "AMD Ryzen 7 5700U with Radeon Graphics", vendor = "Beelink", model = "SER5",
        memTotalBytes = 32L * 1024 * 1024 * 1024, swapTotalBytes = 8L * 1024 * 1024 * 1024, swapFreeBytes = 7L * 1024 * 1024 * 1024,
        uptimeSeconds = 12 * 86_400L + 4 * 3600, load = listOf(0.82, 0.64, 0.51),
    )

    private fun wave(n: Int, base: Float, amp: Float, phase: Float = 0f) = List(n) { base + amp * sin(it / 6f + phase).toFloat() + amp * 0.4f * sin(it / 1.7f).toFloat() }

    private val overview = OverviewState(
        facts = facts,
        osName = facts.os,
        adminAccess = true,
        failedUnits = 2,
        cpu = 18f,
        cpuHistory = wave(60, 16f, 9f),
        memUsed = 11.4 * 1024 * 1024 * 1024,
        memHistory = wave(60, 35f, 1.5f, 1f),
        netRx = 2.4 * 1024 * 1024,
        netTx = 310.0 * 1024,
        netRxHistory = wave(60, 2_000_000f, 900_000f),
        netTxHistory = wave(60, 300_000f, 120_000f, 2f),
        diskRead = 0.0,
        diskWrite = 1.3 * 1024 * 1024,
        mounts = listOf(
            Mount("/", 18.2 * 1024 * 1024 * 1024, 70.0 * 1024 * 1024 * 1024),
            Mount("/boot", 0.41 * 1024 * 1024 * 1024, 0.96 * 1024 * 1024 * 1024),
            Mount("/srv/media", 3.31 * 1024 * 1024 * 1024 * 1024, 3.6 * 1024 * 1024 * 1024 * 1024),
        ),
        temperature = 52.0,
    )

    @Test
    @Config(qualifiers = "w400dp-h1900dp-xxhdpi")
    fun overviewLight() {
        render(dark = false) { Chrome(SessionTab.OVERVIEW) { OverviewScreen(overview, {}, { Result.success(Unit) }) } }
        save("overview_light")
    }

    @Test
    fun overviewDark() {
        render(dark = true) { Chrome(SessionTab.OVERVIEW) { OverviewScreen(overview, {}, { Result.success(Unit) }) } }
        save("overview_dark")
    }

    // ---- Services ----

    private val services = listOf(
        ServiceUnit("httpd.service", "The Apache HTTP Server", "loaded", "failed", "failed", "enabled"),
        ServiceUnit("backup-nightly.service", "Nightly restic backup", "loaded", "failed", "failed", "static"),
        ServiceUnit("chronyd.service", "NTP client/server", "loaded", "active", "running", "enabled"),
        ServiceUnit("cockpit.service", "Cockpit Web Service", "loaded", "active", "running", "static"),
        ServiceUnit("crond.service", "Command Scheduler", "loaded", "active", "running", "enabled"),
        ServiceUnit("firewalld.service", "firewalld - dynamic firewall daemon", "loaded", "active", "running", "enabled"),
        ServiceUnit("jellyfin.service", "Jellyfin Media Server", "loaded", "active", "running", "enabled"),
        ServiceUnit("NetworkManager.service", "Network Manager", "loaded", "active", "running", "enabled"),
        ServiceUnit("podman-auto-update.service", "Podman auto-update service", "loaded", "activating", "start", "disabled"),
        ServiceUnit("sshd.service", "OpenSSH server daemon", "loaded", "active", "running", "enabled"),
        ServiceUnit("tuned.service", "Dynamic System Tuning Daemon", "loaded", "active", "running", "enabled"),
        ServiceUnit("cups.service", "CUPS Scheduler", "not-loaded", "inactive", "dead", "disabled"),
    )

    @Test
    fun servicesLight() {
        render(dark = false) {
            Chrome(SessionTab.SERVICES) {
                ServicesScreen(ServicesState(loading = false, services = services), {}, {}, {}, {})
            }
        }
        save("services_light")
    }

    @Test
    fun servicesDark() {
        render(dark = true) {
            Chrome(SessionTab.SERVICES) {
                ServicesScreen(ServicesState(loading = false, services = services, busy = mapOf("sshd.service" to app.cockpitmanager.system.ServiceAction.RESTART)), {}, {}, {}, {})
            }
        }
        save("services_dark")
    }

    private fun entry(minutesAgo: Int, priority: Int, id: String, unit: String?, message: String) = JournalEntry(
        cursor = "c$minutesAgo$id", timestampMicros = (now - minutesAgo * 60_000L) * 1000, priority = priority,
        identifier = id, unit = unit, pid = "1123", message = message,
    )

    @Test
    @Config(qualifiers = "w400dp-h1200dp-xxhdpi")
    fun serviceDetail() {
        val sshd = services.first { it.name == "sshd.service" }
        val details = ServiceDetails(
            mapOf(
                "Description" to "OpenSSH server daemon", "ActiveState" to "active", "SubState" to "running", "UnitFileState" to "enabled",
                "ActiveEnterTimestamp" to "Mon 2026-11-10 09:12:03 EST", "MainPID" to "1123", "MemoryCurrent" to "2306048",
                "CPUUsageNSec" to "412000000", "TasksCurrent" to "1", "NRestarts" to "0", "Type" to "notify", "Result" to "success",
                "FragmentPath" to "/usr/lib/systemd/system/sshd.service",
            ),
        )
        val logs = listOf(
            entry(3, 6, "sshd", "sshd.service", "Accepted publickey for admin from 192.168.1.23 port 51544 ssh2: ED25519 SHA256:q2Xh…"),
            entry(14, 4, "sshd", "sshd.service", "error: kex_exchange_identification: Connection closed by remote host"),
            entry(62, 5, "sshd", "sshd.service", "Server listening on :: port 22."),
        )
        render(dark = false) {
            ServiceDetailScreen(sshd, ServiceDetailState("sshd.service", loading = false, details = details, logs = logs), null, null, {}, {}, {})
        }
        save("service_detail")
    }

    // ---- Logs ----

    private val journal = listOf(
        entry(1, 3, "httpd", "httpd.service", "AH00015: Unable to open logs"),
        entry(2, 4, "kernel", null, "nvme0n1: I/O Cmd(0x2) @ LBA 41230336, 256 blocks, I/O Error (sct 0x2 / sc 0x81)"),
        entry(5, 4, "podman", "podman-auto-update.service", "Image quay.io/example/app:latest: no update available"),
        entry(9, 5, "systemd", null, "Started backup-nightly.service - Nightly restic backup."),
        entry(9, 3, "restic", "backup-nightly.service", "Fatal: unable to open repository at sftp:backup@nas:/srv/restic: ssh: connect to host nas port 22: No route to host"),
        entry(31, 4, "chronyd", "chronyd.service", "System clock wrong by 1.204512 seconds"),
        entry(47, 5, "sudo", null, "admin : TTY=pts/0 ; PWD=/home/admin ; USER=root ; COMMAND=/usr/bin/dnf upgrade"),
        entry(60 * 26, 4, "firewalld", "firewalld.service", "WARNING: AllowZoneDrifting is enabled. This is considered an insecure configuration option."),
        entry(60 * 27, 3, "kernel", null, "ACPI BIOS Error (bug): Could not resolve symbol [\\_SB.PCI0.GPP2], AE_NOT_FOUND"),
    )

    @Test
    fun logsLight() {
        render(dark = false) {
            Chrome(SessionTab.LOGS) {
                LogsScreen(LogsState(loading = false, loaded = true, entries = journal, query = JournalQuery(level = LogLevel.WARNINGS)), {}, {}, {}, {}, {}, {})
            }
        }
        save("logs_light")
    }

    @Test
    fun logsDark() {
        render(dark = true) {
            Chrome(SessionTab.LOGS) {
                LogsScreen(LogsState(loading = false, loaded = true, entries = journal, live = true, query = JournalQuery(level = LogLevel.WARNINGS)), {}, {}, {}, {}, {}, {})
            }
        }
        save("logs_dark")
    }

    // ---- Terminal ----

    @Test
    fun terminal() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = CockpitTransport(OkHttpClient(), ServerUrl(true, "atlas.home.arpa", 9090), "cockpit=x", scope)
        val session = TerminalSession(transport, scope)
        session.start(rows = 30, cols = 46)
        val esc = "\u001b"
        session.emulator.feed(
            "$esc]0;admin@atlas:~\u0007" +
                "[admin@atlas ~]$ systemctl status sshd --no-pager\r\n" +
                "$esc[0;1;32m●$esc[0m sshd.service - OpenSSH server daemon\r\n" +
                "     Loaded: loaded (/usr/lib/systemd/system/\r\n             sshd.service; $esc[0;1;32menabled$esc[0m)\r\n" +
                "     Active: $esc[0;1;32mactive (running)$esc[0m since Mon\r\n             2026-11-10 09:12:03 EST; 5h ago\r\n" +
                "   Main PID: 1123 (sshd)\r\n      Tasks: 1 (limit: 48912)\r\n     Memory: 2.2M\r\n\r\n" +
                "[admin@atlas ~]$ df -h / /srv/media\r\n" +
                "Filesystem             Size  Used Avail Use%\r\n" +
                "/dev/mapper/rhel-root   70G   19G   52G  27%\r\n" +
                "/dev/sdb1              3.6T  3.3T  290G  92%\r\n" +
                "[admin@atlas ~]$ ls\r\n" +
                "$esc[01;34mbackups$esc[0m  $esc[01;34mcompose$esc[0m  $esc[01;32mdeploy.sh$esc[0m  notes.txt\r\n" +
                "[admin@atlas ~]$ ",
        )
        render(dark = false) {
            Chrome(SessionTab.TERMINAL) { TerminalScreen(session, AppSettings().terminalTextSize.sp) }
        }
        save("terminal")
    }

    // ---- Settings ----

    @Test
    fun settings() {
        render(dark = false) { SettingsScreen(AppSettings(), "1.0.0", {}, {}) }
        save("settings")
    }
}
