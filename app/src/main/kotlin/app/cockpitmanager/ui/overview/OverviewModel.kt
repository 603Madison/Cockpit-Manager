package app.cockpitmanager.ui.overview

import app.cockpitmanager.cockpit.CockpitException
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.InitInfo
import app.cockpitmanager.cockpit.MetricSpec
import app.cockpitmanager.cockpit.metrics
import app.cockpitmanager.system.PowerAction
import app.cockpitmanager.system.ServicesRepository
import app.cockpitmanager.system.SystemFacts
import app.cockpitmanager.system.hasAdminAccess
import app.cockpitmanager.system.power
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Mount(val path: String, val used: Double, val total: Double) {
    val fraction: Float get() = if (total > 0) (used / total).toFloat() else 0f
}

data class OverviewState(
    val facts: SystemFacts? = null,
    val osName: String? = null,
    val adminAccess: Boolean? = null,
    val failedUnits: Int? = null,
    /** Percent of all cores, 0–100. */
    val cpu: Float? = null,
    val cpuHistory: List<Float> = emptyList(),
    val memUsed: Double? = null,
    val memHistory: List<Float> = emptyList(),
    val netRx: Double? = null,
    val netTx: Double? = null,
    val netRxHistory: List<Float> = emptyList(),
    val netTxHistory: List<Float> = emptyList(),
    val diskRead: Double? = null,
    val diskWrite: Double? = null,
    val mounts: List<Mount> = emptyList(),
    val temperature: Double? = null,
    val error: String? = null,
    val usageError: String? = null,
    val powerMessage: String? = null,
) {
    val memFraction: Float?
        get() {
            val total = facts?.memTotalBytes ?: return null
            val used = memUsed ?: return null
            return if (total > 0) (used / total).toFloat() else null
        }
}

/** The Overview's data: host facts every 15 s, live usage every 2 s while the screen is open. */
class OverviewModel(
    private val transport: CockpitTransport,
    init: InitInfo,
    private val services: ServicesRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(OverviewState(osName = init.osName))
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private var adminChecked = false

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            while (isActive) {
                try {
                    val facts = SystemFacts.read(transport)
                    _state.update { it.copy(facts = facts, osName = facts.os.ifBlank { null } ?: it.osName, error = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Couldn't read system information") }
                }
                runCatching { services.failedCount() }.onSuccess { n -> _state.update { it.copy(failedUnits = n) } }
                if (!adminChecked) {
                    adminChecked = true
                    val admin = transport.hasAdminAccess()
                    _state.update { it.copy(adminAccess = admin) }
                }
                delay(15_000)
            }
        }
        jobs += collect(listOf(MetricSpec("cpu.basic.user", "rate"), MetricSpec("cpu.basic.system", "rate"), MetricSpec("cpu.basic.nice", "rate"), MetricSpec("memory.used")), essential = true) { s ->
            val cores = _state.value.facts?.cpuCount ?: 1
            val busy = listOf(0, 1, 2).mapNotNull { s.scalar(it) }
            val cpu = if (busy.size == 3) (busy.sum() / 10.0 / cores).toFloat().coerceIn(0f, 100f) else null
            val mem = s.scalar(3)
            _state.update { st ->
                val total = st.facts?.memTotalBytes?.toDouble()
                st.copy(
                    cpu = cpu ?: st.cpu,
                    cpuHistory = if (cpu != null) (st.cpuHistory + cpu).takeLast(HISTORY) else st.cpuHistory,
                    memUsed = mem ?: st.memUsed,
                    memHistory = if (mem != null && total != null && total > 0) (st.memHistory + (mem / total * 100).toFloat()).takeLast(HISTORY) else st.memHistory,
                    usageError = null,
                )
            }
        }
        jobs += collect(listOf(MetricSpec("network.interface.rx", "rate"), MetricSpec("network.interface.tx", "rate"))) { s ->
            fun total(i: Int) = s.instances(i).filterKeys(::isPhysicalInterface).values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
            val rx = total(0)
            val tx = total(1)
            _state.update { st ->
                st.copy(
                    netRx = rx ?: st.netRx,
                    netTx = tx ?: st.netTx,
                    netRxHistory = if (rx != null) (st.netRxHistory + rx.toFloat()).takeLast(HISTORY) else st.netRxHistory,
                    netTxHistory = if (tx != null) (st.netTxHistory + tx.toFloat()).takeLast(HISTORY) else st.netTxHistory,
                )
            }
        }
        jobs += collect(listOf(MetricSpec("disk.all.read", "rate"), MetricSpec("disk.all.written", "rate"))) { s ->
            _state.update { st -> st.copy(diskRead = s.scalar(0) ?: st.diskRead, diskWrite = s.scalar(1) ?: st.diskWrite) }
        }
        jobs += collect(listOf(MetricSpec("mount.total"), MetricSpec("mount.used")), interval = 10_000) { s ->
            val totals = s.instances(0)
            val used = s.instances(1)
            val mounts = totals.mapNotNull { (path, total) ->
                val u = used[path]
                if (total == null || u == null || total <= 0) null else Mount(path, u, total)
            }.sortedWith(compareBy({ it.path != "/" }, { it.path }))
            _state.update { it.copy(mounts = mounts) }
        }
        jobs += collect(listOf(MetricSpec("cpu.temperature")), interval = 5_000) { s ->
            val max = s.instances(0).values.filterNotNull().filter { it in 1.0..150.0 }.maxOrNull()
            _state.update { it.copy(temperature = max) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun collect(specs: List<MetricSpec>, interval: Int = 2000, essential: Boolean = false, onSample: (app.cockpitmanager.cockpit.MetricsSample) -> Unit): Job =
        scope.launch {
            transport.metrics(specs, interval)
                .catch { e ->
                    // Some metrics don't exist everywhere (no sensors, older bridges); only CPU/memory is worth reporting.
                    if (essential) _state.update { it.copy(usageError = e.message ?: "Live usage isn't available") }
                }
                .collect(onSample)
        }

    suspend fun power(action: PowerAction): Result<Unit> = try {
        transport.power(action)
        _state.update { it.copy(powerMessage = "${if (action == PowerAction.RESTART) "Restarting" else "Shutting down"}…") }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CockpitException) {
        // The server going away mid-command means it worked.
        if (e.problem == "disconnected" || e.problem == "terminated") Result.success(Unit) else Result.failure(e)
    }

    companion object {
        const val HISTORY = 60

        private val virtualPrefixes = listOf("lo", "veth", "docker", "br-", "virbr", "cni", "flannel", "podman", "vnet", "tap", "kube", "cali", "tun")

        fun isPhysicalInterface(name: String) = virtualPrefixes.none { name == it || name.startsWith(it) }
    }
}
