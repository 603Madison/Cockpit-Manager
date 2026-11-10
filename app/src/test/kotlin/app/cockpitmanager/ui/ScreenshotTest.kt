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
import app.cockpitmanager.ui.overview.Workloads
import app.cockpitmanager.ui.machines.VmUsage
import app.cockpitmanager.ui.machines.MachinesState
import app.cockpitmanager.ui.machines.MachinesScreen
import app.cockpitmanager.ui.machines.MachineDetailScreen
import app.cockpitmanager.ui.containers.ContainersState
import app.cockpitmanager.ui.containers.ContainersScreen
import app.cockpitmanager.ui.containers.ContainerFilter
import app.cockpitmanager.ui.containers.ContainerDetailState
import app.cockpitmanager.ui.containers.ContainerDetailScreen
import app.cockpitmanager.system.VmState
import app.cockpitmanager.system.VmInterface
import app.cockpitmanager.system.VmDisk
import app.cockpitmanager.system.VmConnection
import app.cockpitmanager.system.VirtualMachine
import app.cockpitmanager.system.PodmanOwner
import app.cockpitmanager.system.PodmanContainer
import app.cockpitmanager.system.DomainDescription
import app.cockpitmanager.system.ContainerStats
import app.cockpitmanager.system.ContainerDetails
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
            server = server, selected = tab, tabs = SessionTab.entries, failedUnits = failed, lostMessage = null,
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

    private fun wave(n: Int, base: Float, amp: Float, phase: Float = 0f) = List(n) { base + amp * sin(it / 6f + phase) + amp * 0.4f * sin(it / 1.7f) }

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
        render(dark = false) {
            Chrome(SessionTab.OVERVIEW) {
                OverviewScreen(overview, {}, { Result.success(Unit) }, workloads = Workloads(true, true, containersState, machinesState))
            }
        }
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

    // ---- Containers ----

    private fun container(
        id: String, name: String, image: String, state: String, owner: PodmanOwner = PodmanOwner.SYSTEM,
        pod: String? = null, exit: Int = 0, unit: String? = null, ports: List<String> = emptyList(), minutes: Long = 90,
    ) = PodmanContainer(
        id = id.padEnd(64, '0'), name = name, image = image, command = "", state = state,
        createdMillis = now - minutes * 120_000, startedMillis = now - minutes * 60_000,
        exitedMillis = if (state == "exited") now - minutes * 30_000 else null, exitCode = exit,
        podId = pod, podName = pod, ports = ports, owner = owner, isInfra = false, systemdUnit = unit,
    )

    private val containerList = listOf(
        container("a1", "jellyfin", "docker.io/jellyfin/jellyfin:latest", "running", ports = listOf("8096 → 8096/tcp"), minutes = 4000, unit = "jellyfin.service"),
        container("a2", "caddy", "docker.io/library/caddy:2", "running", ports = listOf("443 → 443/tcp", "80 → 80/tcp"), minutes = 4000),
        container("a3", "home-assistant", "ghcr.io/home-assistant/home-assistant:stable", "running", minutes = 700, unit = "home-assistant.service"),
        container("a4", "backup", "docker.io/restic/restic:latest", "exited", exit = 1, minutes = 600),
        container("a5", "nextcloud-app", "docker.io/library/nextcloud:29-apache", "running", pod = "nextcloud", minutes = 2000),
        container("a6", "nextcloud-db", "docker.io/library/mariadb:11", "running", pod = "nextcloud", minutes = 2000),
        container("a7", "nextcloud-redis", "docker.io/library/redis:7", "paused", pod = "nextcloud", minutes = 2000),
        container("a8", "toolbox", "registry.fedoraproject.org/fedora-toolbox:41", "exited", owner = PodmanOwner.USER, minutes = 5000),
    )

    private val containersState = ContainersState(
        loading = false, loaded = true, containers = containerList, filter = ContainerFilter.ALL,
        stats = mapOf(
            "SYSTEM:${"a1".padEnd(64, '0')}" to ContainerStats(3.4, 612L * 1024 * 1024, 32L * 1024 * 1024 * 1024, 52_000_000, 8_400_000_000, 120_000_000, 40_000_000, 31),
            "SYSTEM:${"a2".padEnd(64, '0')}" to ContainerStats(0.2, 38L * 1024 * 1024, null, 1_000_000, 900_000, 0, 0, 9),
            "SYSTEM:${"a3".padEnd(64, '0')}" to ContainerStats(1.8, 402L * 1024 * 1024, null, 0, 0, 0, 0, 24),
            "SYSTEM:${"a5".padEnd(64, '0')}" to ContainerStats(0.6, 210L * 1024 * 1024, null, 0, 0, 0, 0, 12),
            "SYSTEM:${"a6".padEnd(64, '0')}" to ContainerStats(0.3, 180L * 1024 * 1024, null, 0, 0, 0, 0, 14),
        ),
    )

    @Test
    fun containersLight() {
        render(dark = false) {
            Chrome(SessionTab.CONTAINERS) { ContainersScreen(containersState, {}, {}, {}, {}) }
        }
        save("containers_light")
    }

    @Test
    fun containersDark() {
        render(dark = true) {
            Chrome(SessionTab.CONTAINERS) { ContainersScreen(containersState.copy(filter = ContainerFilter.RUNNING), {}, {}, {}, {}) }
        }
        save("containers_dark")
    }

    @Test
    @Config(qualifiers = "w400dp-h1500dp-xxhdpi")
    fun containerDetail() {
        val c = containerList.first()
        val details = ContainerDetails(
            imageName = c.image, command = "/jellyfin/jellyfin", createdMillis = c.createdMillis, startedMillis = c.startedMillis,
            finishedMillis = null, exitCode = 0, error = null, health = "healthy", restartPolicy = "always", restartCount = 0,
            ports = c.ports, networks = listOf("podman" to "10.88.0.4"),
            mounts = listOf("/srv/media → /media (read-only)", "jellyfin-config → /config"), tty = false, systemdUnit = c.systemdUnit,
        )
        val logs = listOf(
            "[19:41:02] [INF] [12] Main: Jellyfin version: 10.10.3",
            "[19:41:05] [INF] [12] Emby.Server.Implementations.ApplicationHost: Running startup tasks",
            "[19:41:09] [INF] [7] Jellyfin.Networking.Manager.NetworkManager: Defined LAN addresses: [192.168.1.0/24]",
            "[19:42:31] [INF] [28] Jellyfin.Api.Auth: Authentication request for \"admin\" has succeeded.",
            "[19:44:10] [WRN] [31] MediaBrowser.MediaEncoding: Transcoding: ffmpeg exited with code 0",
        )
        render(dark = false) {
            ContainerDetailScreen(c, ContainerDetailState(c.key, details, logs), containersState.stats[c.key], null, {}, {})
        }
        save("container_detail")
    }

    // ---- Virtual machines ----

    private fun vm(name: String, title: String?, state: VmState, os: String, vcpus: Int, gib: Long, autostart: Boolean = false) = VirtualMachine(
        path = "/org/libvirt/QEMU/domain/_$name", connection = VmConnection.SYSTEM, name = name, uuid = "6cace387-a6ff-48c6-a867-74e83b68f750",
        state = state, persistent = true, autostart = autostart,
        description = DomainDescription(
            name = name, title = title, description = null, vcpus = vcpus, memoryBytes = gib * 1024 * 1024 * 1024,
            currentMemoryBytes = gib * 1024 * 1024 * 1024, osId = os, arch = "x86_64", machine = "pc-q35-9.1",
            disks = listOf(VmDisk("vda", "disk", "/var/lib/libvirt/images/$name.qcow2")),
            interfaces = listOf(VmInterface("52:54:00:6b:3c:58", "default", "virtio")), graphics = listOf("VNC"),
        ),
    )

    private val vmList = listOf(
        vm("rhel10-build", "RHEL 10 build box", VmState.RUNNING, "http://redhat.com/rhel/10.0", 4, 8, autostart = true),
        vm("win11", "Windows 11", VmState.RUNNING, "http://microsoft.com/win/11", 4, 16),
        vm("fedora-41-test", null, VmState.PAUSED, "http://fedoraproject.org/fedora/41", 2, 4),
        vm("ubuntu-lab", null, VmState.SHUT_OFF, "http://ubuntu.com/ubuntu/24.04", 2, 4),
    )

    private val machinesState = MachinesState(
        loading = false, loaded = true, vms = vmList,
        usage = mapOf(vmList[0].key to VmUsage(23.0, 3L * 1024 * 1024 * 1024, null), vmList[1].key to VmUsage(4.0, null, 9L * 1024 * 1024 * 1024)),
    )

    @Test
    fun machinesLight() {
        render(dark = false) { Chrome(SessionTab.MACHINES) { MachinesScreen(machinesState, {}, {}, {}) } }
        save("machines_light")
    }

    @Test
    @Config(qualifiers = "w400dp-h1300dp-xxhdpi")
    fun machineDetailDark() {
        val v = vmList.first()
        render(dark = true) {
            MachineDetailScreen(v, machinesState.usage[v.key], listOf("192.168.122.48"), null, false, {}, {}, {})
        }
        save("machine_detail")
    }
}
