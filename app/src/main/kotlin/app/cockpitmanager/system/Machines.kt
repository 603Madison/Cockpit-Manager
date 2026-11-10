package app.cockpitmanager.system

import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.DBusClient
import app.cockpitmanager.cockpit.Superuser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** libvirt's two connections, as cockpit-machines shows them: the system's VMs and the user's own (session). */
enum class VmConnection(val bus: String, val label: String) {
    SYSTEM("system", "System"),
    SESSION("session", "User session"),
}

/** virDomainState, with cockpit-machines' wording. */
enum class VmState(val label: String) {
    NO_STATE("no state"),
    RUNNING("running"),
    BLOCKED("blocked"),
    PAUSED("paused"),
    SHUTTING_DOWN("shutting down"),
    SHUT_OFF("shut off"),
    CRASHED("crashed"),
    SUSPENDED("suspended"),
    ;

    /** Running in the sense of cockpit-machines' domainIsRunning: it can be shut down or reset. */
    val isRunning get() = this == RUNNING || this == BLOCKED || this == PAUSED

    companion object {
        fun of(code: Int) = entries.getOrElse(code) { NO_STATE }
    }
}

data class VmDisk(val target: String, val device: String, val source: String?)
data class VmInterface(val mac: String?, val source: String?, val model: String?)

/** The parts of the domain XML worth showing. */
data class DomainDescription(
    val name: String,
    val title: String?,
    val description: String?,
    val vcpus: Int?,
    val memoryBytes: Long?,
    val currentMemoryBytes: Long?,
    val osId: String?,
    val arch: String?,
    val machine: String?,
    val disks: List<VmDisk>,
    val interfaces: List<VmInterface>,
    val graphics: List<String>,
) {
    /** "http://fedoraproject.org/fedora/41" → "Fedora 41". */
    val osName: String? get() = osId?.let(::prettyOs)

    companion object {
        fun parse(xml: String): DomainDescription? {
            val doc = runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }
                factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            }.getOrNull() ?: return null
            val root = doc.documentElement ?: return null

            fun Element.children(tag: String): List<Element> {
                val out = mutableListOf<Element>()
                val nodes = childNodes
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i)
                    if (n is Element && (n.localName ?: n.tagName) == tag) out += n
                }
                return out
            }
            fun Element.child(tag: String) = children(tag).firstOrNull()
            fun Element.attr(name: String) = getAttribute(name).ifBlank { null }

            fun bytes(e: Element?): Long? {
                val value = e?.textContent?.trim()?.toLongOrNull() ?: return null
                return value * when (e.getAttribute("unit").ifBlank { "KiB" }) {
                    "b", "bytes" -> 1L
                    "KB" -> 1000L
                    "k", "KiB" -> 1024L
                    "MB" -> 1000L * 1000
                    "M", "MiB" -> 1024L * 1024
                    "GB" -> 1000L * 1000 * 1000
                    "G", "GiB" -> 1024L * 1024 * 1024
                    "TB" -> 1000L * 1000 * 1000 * 1000
                    "T", "TiB" -> 1024L * 1024 * 1024 * 1024
                    else -> 1024L
                }
            }

            val os = root.child("os")
            val type = os?.child("type")
            val osInfo = root.child("metadata")?.let { meta ->
                val nodes = meta.getElementsByTagNameNS("*", "os")
                (0 until nodes.length).map { nodes.item(it) as Element }.firstNotNullOfOrNull { it.attr("id") }
            }
            val devices = root.child("devices")
            val disks = devices?.children("disk")?.mapNotNull { d ->
                val target = d.child("target")?.attr("dev") ?: return@mapNotNull null
                val src = d.child("source")
                VmDisk(
                    target = target,
                    device = d.attr("device") ?: "disk",
                    source = src?.let { it.attr("file") ?: it.attr("dev") ?: it.attr("volume")?.let { v -> "${it.attr("pool")}/$v" } ?: it.attr("name") },
                )
            }.orEmpty()
            val interfaces = devices?.children("interface")?.map { i ->
                val src = i.child("source")
                VmInterface(
                    mac = i.child("mac")?.attr("address"),
                    source = src?.let { it.attr("network") ?: it.attr("bridge") ?: it.attr("dev") } ?: i.attr("type"),
                    model = i.child("model")?.attr("type"),
                )
            }.orEmpty()
            return DomainDescription(
                name = root.child("name")?.textContent?.trim().orEmpty(),
                title = root.child("title")?.textContent?.trim()?.ifBlank { null },
                description = root.child("description")?.textContent?.trim()?.ifBlank { null },
                vcpus = root.child("vcpu")?.let { v -> v.attr("current")?.toIntOrNull() ?: v.textContent.trim().toIntOrNull() },
                memoryBytes = bytes(root.child("memory")),
                currentMemoryBytes = bytes(root.child("currentMemory")),
                osId = osInfo,
                arch = type?.attr("arch"),
                machine = type?.attr("machine"),
                disks = disks,
                interfaces = interfaces,
                graphics = devices?.children("graphics")?.mapNotNull { it.attr("type")?.uppercase() }.orEmpty(),
            )
        }

        private val vendors = mapOf(
            "fedoraproject.org" to "Fedora", "redhat.com" to "Red Hat Enterprise Linux", "centos.org" to "CentOS",
            "ubuntu.com" to "Ubuntu", "debian.org" to "Debian", "microsoft.com" to "Windows", "rockylinux.org" to "Rocky Linux",
            "almalinux.org" to "AlmaLinux", "opensuse.org" to "openSUSE", "archlinux.org" to "Arch Linux", "freebsd.org" to "FreeBSD",
        )

        fun prettyOs(id: String): String {
            val rest = id.substringAfter("://").split('/').filter { it.isNotBlank() }
            if (rest.isEmpty()) return id
            val vendor = vendors[rest.first().removePrefix("www.")]
            val version = rest.drop(1).lastOrNull()?.takeIf { v -> v.any { it.isDigit() } || v == "unknown" }
            val product = rest.drop(1).firstOrNull()
            return when {
                vendor == "Red Hat Enterprise Linux" && version != null -> "$vendor $version"
                vendor == "CentOS" && product == "centos-stream" -> "CentOS Stream ${version.orEmpty()}".trim()
                vendor == "Windows" && version != null -> "Windows $version"
                vendor != null -> listOfNotNull(vendor, version).joinToString(" ")
                else -> rest.drop(1).joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { id }
            }
        }
    }
}

data class VirtualMachine(
    val path: String,
    val connection: VmConnection,
    val name: String,
    val uuid: String,
    val state: VmState,
    val persistent: Boolean,
    val autostart: Boolean,
    val description: DomainDescription?,
) {
    val key: String get() = "${connection.name}:$path"
    val displayName: String get() = description?.title ?: name
}

data class VmStats(val cpuTimeNanos: Long?, val vcpus: Int?, val memoryUsedBytes: Long?, val memoryRssBytes: Long?)

enum class VmAction(val label: String, val progress: String, val method: String, val signature: String, val flags: Boolean) {
    START("Run", "Starting", "Create", "u", true),
    SHUT_DOWN("Shut down", "Shutting down", "Shutdown", "u", true),
    REBOOT("Reboot", "Rebooting", "Reboot", "u", true),
    FORCE_REBOOT("Force reboot", "Rebooting", "Reset", "u", true),
    FORCE_OFF("Force shut down", "Shutting down", "Destroy", "u", true),
    PAUSE("Pause", "Pausing", "Suspend", "", false),
    RESUME("Resume", "Resuming", "Resume", "", false),
}

/**
 * Virtual machines through libvirt-dbus ("org.libvirt"), the same API
 * cockpit-machines uses: the system connection with administrative access
 * when available, or the user's session connection.
 */
class MachinesClient(transport: CockpitTransport, scope: CoroutineScope, val connection: VmConnection) {
    private val dbus = DBusClient.open(
        transport, scope, "org.libvirt", bus = connection.bus,
        superuser = if (connection == VmConnection.SYSTEM) Superuser.TRY else Superuser.NONE,
    )

    suspend fun list(): List<VirtualMachine> = coroutineScope {
        val paths = (dbus.call(ROOT, CONNECT, "ListDomains", listOf(JsonPrimitive(0)), "u").firstOrNull() as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        paths.map { path -> async { runCatching { get(path) }.getOrNull() } }.awaitAll().filterNotNull()
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun get(path: String): VirtualMachine {
        val props = dbus.getAll(path, DOMAIN)
        val state = stateOf(dbus.call(path, DOMAIN, "GetState", listOf(JsonPrimitive(0)), "u"))
        val xml = runCatching {
            (dbus.call(path, DOMAIN, "GetXMLDesc", listOf(JsonPrimitive(0)), "u").firstOrNull() as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return VirtualMachine(
            path = path,
            connection = connection,
            name = props["Name"].str() ?: path.substringAfterLast('/'),
            uuid = props["UUID"].str().orEmpty(),
            state = state,
            persistent = props["Persistent"].bool() ?: true,
            autostart = props["Autostart"].bool() ?: false,
            description = xml?.let(DomainDescription::parse),
        )
    }

    /** CPU time, vCPUs and memory, from GetStats (balloon details need the guest's balloon driver). */
    suspend fun stats(path: String): VmStats {
        val flags = STATS_STATE or STATS_CPU_TOTAL or STATS_BALLOON or STATS_VCPU
        val out = dbus.call(path, DOMAIN, "GetStats", listOf(JsonPrimitive(flags), JsonPrimitive(0)), "uu", timeoutMillis = 5000)
        val raw = out.firstOrNull() as? JsonObject ?: return VmStats(null, null, null, null)
        val s = raw.mapValues { (_, v) -> DBusClient.unwrapVariant(v) }
        val current = s["balloon.current"].num()
        val usable = s["balloon.usable"].num()
        return VmStats(
            cpuTimeNanos = s["cpu.time"].num(),
            vcpus = s["vcpu.current"].num()?.toInt(),
            memoryUsedBytes = if (current != null && usable != null && usable <= current) (current - usable) * 1024 else null,
            memoryRssBytes = s["balloon.rss"].num()?.let { it * 1024 },
        )
    }

    /** IP addresses from libvirt's DHCP leases (no guest agent needed). */
    suspend fun addresses(path: String): List<String> {
        val out = runCatching { dbus.call(path, DOMAIN, "InterfaceAddresses", listOf(JsonPrimitive(0), JsonPrimitive(0)), "uu") }.getOrNull()
            ?: return emptyList()
        val list = out.firstOrNull() as? JsonArray ?: return emptyList()
        return list.flatMap { iface ->
            // (name, hwaddr, [(type, addr, prefix)])
            val addrs = (iface as? JsonArray)?.getOrNull(2) as? JsonArray
            addrs?.mapNotNull { a -> ((a as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }
    }

    suspend fun act(path: String, action: VmAction) {
        val args = if (action.flags) listOf<JsonElement>(JsonPrimitive(0)) else emptyList()
        dbus.call(path, DOMAIN, action.method, args, action.signature, timeoutMillis = 120_000)
    }

    suspend fun setAutostart(path: String, on: Boolean) = dbus.setProperty(path, DOMAIN, "Autostart", "b", JsonPrimitive(on))

    fun close() = dbus.close()

    companion object {
        const val ROOT = "/org/libvirt/QEMU"
        const val CONNECT = "org.libvirt.Connect"
        const val DOMAIN = "org.libvirt.Domain"
        private const val STATS_STATE = 1
        private const val STATS_CPU_TOTAL = 2
        private const val STATS_BALLOON = 4
        private const val STATS_VCPU = 8

        /** GetState returns (state, reason). */
        internal fun stateOf(out: JsonArray): VmState {
            val first = out.firstOrNull()
            val code = when (first) {
                is JsonArray -> (first.firstOrNull() as? JsonPrimitive)?.longOrNull
                is JsonPrimitive -> first.longOrNull
                else -> null
            }
            return VmState.of(code?.toInt() ?: 0)
        }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
        private fun JsonElement?.num(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        /** Is libvirt-dbus installed (what cockpit-machines needs)? Checks the bus for org.libvirt. */
        suspend fun available(transport: CockpitTransport, scope: CoroutineScope, bus: String): Boolean {
            val client = DBusClient.open(transport, scope, "org.freedesktop.DBus", bus = bus, superuser = Superuser.NONE)
            return try {
                fun names(out: JsonArray) = (out.firstOrNull() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val activatable = runCatching { names(client.call("/org/freedesktop/DBus", "org.freedesktop.DBus", "ListActivatableNames", timeoutMillis = 10_000)) }.getOrDefault(emptyList())
                val running = runCatching { names(client.call("/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames", timeoutMillis = 10_000)) }.getOrDefault(emptyList())
                "org.libvirt" in activatable || "org.libvirt" in running
            } finally {
                client.close()
            }
        }
    }
}
