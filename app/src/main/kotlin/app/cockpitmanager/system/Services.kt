package app.cockpitmanager.system

import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.DBusClient
import app.cockpitmanager.cockpit.Superuser
import app.cockpitmanager.cockpit.runChecked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A systemd service as listed: its runtime state plus whether it starts at boot. */
data class ServiceUnit(
    val name: String,
    val description: String,
    val loadState: String,
    val activeState: String,
    val subState: String,
    val fileState: String?,
) {
    /** "sshd.service" → "sshd". */
    val shortName: String get() = name.removeSuffix(".service")

    val isActive get() = activeState == "active" || activeState == "reloading"
    val isFailed get() = activeState == "failed"
    val isTransitioning get() = activeState == "activating" || activeState == "deactivating"
    val isEnabled get() = fileState == "enabled" || fileState == "enabled-runtime" || fileState == "alias"
    val isMasked get() = fileState == "masked" || fileState == "masked-runtime" || loadState == "masked"

    /** Whether enable/disable makes sense (static and generated units have no [Install] section). */
    val canToggleBoot get() = fileState != null && fileState !in setOf("static", "generated", "transient", "indirect", "alias") && !isMasked

    /** "active (running)", "inactive (dead)", "failed (Result: exit-code)"... */
    val stateLabel: String get() = if (subState.isNotBlank() && subState != activeState) "$activeState ($subState)" else activeState
}

enum class ServiceAction(val verb: String, val label: String, val progress: String) {
    START("start", "Start", "Starting"),
    STOP("stop", "Stop", "Stopping"),
    RESTART("restart", "Restart", "Restarting"),
    RELOAD("reload", "Reload", "Reloading"),
    ENABLE("enable", "Start at boot", "Enabling"),
    DISABLE("disable", "Don't start at boot", "Disabling"),
}

/** Details for one service, from `systemctl show`. */
data class ServiceDetails(val properties: Map<String, String>) {
    operator fun get(key: String): String? = properties[key]?.takeIf { it.isNotBlank() && it != "[not set]" && it != "n/a" }

    val mainPid: Int? get() = this["MainPID"]?.toIntOrNull()?.takeIf { it > 0 }

    /** Bytes, or null when systemd isn't accounting memory for it. */
    val memoryBytes: Long? get() = this["MemoryCurrent"]?.toULongOrNull()?.takeIf { it < ULong.MAX_VALUE / 2u }?.toLong()

    val cpuNanos: Long? get() = this["CPUUsageNSec"]?.toULongOrNull()?.takeIf { it < ULong.MAX_VALUE / 2u }?.toLong()

    val tasks: Long? get() = this["TasksCurrent"]?.toULongOrNull()?.takeIf { it < ULong.MAX_VALUE / 2u }?.toLong()

    companion object {
        val PROPERTIES = listOf(
            "Id", "Description", "LoadState", "ActiveState", "SubState", "UnitFileState", "FragmentPath",
            "MainPID", "MemoryCurrent", "CPUUsageNSec", "TasksCurrent", "ActiveEnterTimestamp", "InactiveEnterTimestamp",
            "NRestarts", "Restart", "Type", "Result", "User", "ExecMainStatus", "Documentation", "Requires", "WantedBy",
        )

        fun parse(output: String): ServiceDetails = ServiceDetails(
            output.lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }.toMap(),
        )
    }
}

/**
 * Lists services over systemd's D-Bus API (works on every systemd Cockpit
 * supports) and acts on them with systemctl, which waits for the job to finish
 * and reports systemd's own explanation when it fails.
 */
class ServicesRepository(private val transport: CockpitTransport, private val scope: CoroutineScope) {
    private val systemd: DBusClient by lazy { DBusClient.open(transport, scope, "org.freedesktop.systemd1") }

    suspend fun list(): List<ServiceUnit> = coroutineScope {
        val unitsCall = async { systemd.call(PATH, MANAGER, "ListUnits") }
        val filesCall = async { runCatching { systemd.call(PATH, MANAGER, "ListUnitFiles") }.getOrNull() }
        val units = unitsCall.await().firstOrNull() as? JsonArray ?: JsonArray(emptyList())
        val files = (filesCall.await()?.firstOrNull() as? JsonArray).orEmpty()
        merge(units, files)
    }

    suspend fun failedCount(): Int {
        val units = systemd.call(PATH, MANAGER, "ListUnits").firstOrNull() as? JsonArray ?: return 0
        return units.count { row -> (row as? JsonArray)?.str(3) == "failed" }
    }

    suspend fun act(unit: String, action: ServiceAction) {
        transport.runChecked(listOf("systemctl", action.verb, "--", unit), Superuser.TRY, timeoutMillis = 120_000)
    }

    suspend fun details(unit: String): ServiceDetails = ServiceDetails.parse(
        transport.runChecked(listOf("systemctl", "show", "--property=" + ServiceDetails.PROPERTIES.joinToString(","), "--", unit)),
    )

    fun close() = systemd.close()

    companion object {
        private const val PATH = "/org/freedesktop/systemd1"
        private const val MANAGER = "org.freedesktop.systemd1.Manager"

        private fun JsonArray.str(i: Int): String = getOrNull(i)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

        /**
         * ListUnits rows are (name, description, load, active, sub, following, path, job id, job type, job path);
         * ListUnitFiles rows are (path, state). Unit files that aren't loaded show as inactive.
         */
        fun merge(units: List<JsonElement>, files: List<JsonElement>): List<ServiceUnit> {
            val fileStates = files.mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                r.str(0).substringAfterLast('/') to r.str(1)
            }.toMap()
            val loaded = units.mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                val name = r.str(0)
                if (!name.endsWith(".service")) return@mapNotNull null
                ServiceUnit(
                    name = name,
                    description = r.str(1),
                    loadState = r.str(2),
                    activeState = r.str(3),
                    subState = r.str(4),
                    fileState = fileStates[name] ?: templateState(name, fileStates),
                )
            }.filter { it.loadState != "not-found" || it.isActive || it.isFailed }
            val loadedNames = loaded.mapTo(HashSet()) { it.name }
            val unloaded = fileStates.filter { (name, _) ->
                name.endsWith(".service") && name !in loadedNames && !name.contains("@.")
            }.map { (name, state) ->
                ServiceUnit(name, "", if (state.startsWith("masked")) "masked" else "not-loaded", "inactive", "dead", state)
            }
            return (loaded + unloaded).sortedBy { it.name.lowercase() }
        }

        /** "getty@tty1.service" takes its boot state from "getty@.service". */
        private fun templateState(name: String, fileStates: Map<String, String>): String? {
            val at = name.indexOf('@')
            if (at < 0) return null
            return fileStates[name.substring(0, at + 1) + "." + name.substringAfterLast('.')]
        }
    }
}
