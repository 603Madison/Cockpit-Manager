package app.cockpitmanager.ui.machines

import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.system.MachinesClient
import app.cockpitmanager.system.VirtualMachine
import app.cockpitmanager.system.VmAction
import app.cockpitmanager.system.VmConnection
import app.cockpitmanager.system.VmState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VmFilter(val label: String) { RUNNING("Running"), ALL("All") }

data class VmUsage(val cpuPercent: Double?, val memoryUsedBytes: Long?, val memoryRssBytes: Long?)

data class MachinesState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val vms: List<VirtualMachine> = emptyList(),
    val usage: Map<String, VmUsage> = emptyMap(),
    val error: String? = null,
    val filter: VmFilter = VmFilter.ALL,
    val busy: Map<String, VmAction> = emptyMap(),
    val autostartBusy: Set<String> = emptySet(),
    val message: String? = null,
) {
    val visible: List<VirtualMachine>
        get() = vms.filter { filter == VmFilter.ALL || it.state.isRunning }
            .sortedWith(compareBy({ order(it.state) }, { it.displayName.lowercase() }))

    /** Up, including paused (as the Running filter shows them). */
    val running: Int get() = vms.count { it.state.isRunning }
    val paused: Int get() = vms.count { it.state == VmState.PAUSED }
    val bothConnections: Boolean get() = vms.map { it.connection }.distinct().size > 1
}

private fun order(state: VmState) = when (state) {
    VmState.RUNNING, VmState.BLOCKED -> 0
    VmState.PAUSED -> 1
    VmState.SHUTTING_DOWN -> 2
    else -> 3
}

data class VmDetailState(val key: String, val addresses: List<String> = emptyList())

/**
 * Virtual machines from libvirt-dbus on the system bus (with administrative
 * access when available) and the user's session, as cockpit-machines lists them.
 */
class MachinesModel(private val transport: CockpitTransport, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(MachinesState())
    val state: StateFlow<MachinesState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<VmDetailState?>(null)
    val detail: StateFlow<VmDetailState?> = _detail.asStateFlow()

    private val connectLock = Mutex()
    private var clients: List<MachinesClient>? = null
    private val jobs = mutableListOf<Job>()
    private var detailJob: Job? = null
    private var loadJob: Job? = null

    /** CPU time samples per VM, for the usage percentage. */
    private val lastCpu = mutableMapOf<String, Pair<Long, Long>>()

    private suspend fun clients(): List<MachinesClient> = connectLock.withLock {
        clients ?: VmConnection.entries.filter { c ->
            MachinesClient.available(transport, scope, c.bus)
        }.map { MachinesClient(transport, scope, it) }.also { clients = it }
    }

    private fun client(connection: VmConnection) = clients?.firstOrNull { it.connection == connection }

    fun ensureLoaded() {
        if (!_state.value.loaded) refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val all = clients()
                val results = all.map { c -> async { runCatching { c.list() } } }.awaitAll()
                val vms = results.flatMap { it.getOrNull().orEmpty() }
                // A session connection that fails (e.g. no user session bus) isn't worth an error if the system one works.
                val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.takeIf { results.all { r -> r.isFailure } }
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        vms = vms,
                        error = when {
                            all.isEmpty() -> "libvirt-dbus isn't available on this server."
                            failure != null -> failure.message ?: "Couldn't list virtual machines"
                            else -> null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loaded = true, error = e.message ?: "Couldn't list virtual machines") }
            }
        }
    }

    /** While on screen: the list every 10 s and usage of running VMs every 3 s. */
    /** Screens showing live data; start/stop pair up, so the list and a detail page can overlap during navigation. */
    private var watchers = 0

    fun start() {
        watchers++
        if (jobs.isNotEmpty()) return
        refresh()
        jobs += scope.launch {
            while (isActive) {
                delay(10_000)
                refresh()
            }
        }
        jobs += scope.launch {
            while (isActive) {
                sampleUsage()
                delay(3_000)
            }
        }
    }

    fun stop() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers > 0) return
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun sampleUsage() {
        val running = _state.value.vms.filter { it.state.isRunning }
        val now = System.nanoTime()
        val usage = running.mapNotNull { vm ->
            val stats = runCatching { client(vm.connection)?.stats(vm.path) }.getOrNull() ?: return@mapNotNull null
            val cpuPercent = stats.cpuTimeNanos?.let { cpu ->
                val previous = lastCpu[vm.key]
                lastCpu[vm.key] = cpu to now
                if (previous == null) null else {
                    val (lastCpuTime, lastWall) = previous
                    val cores = (stats.vcpus ?: vm.description?.vcpus ?: 1).coerceAtLeast(1)
                    val wall = (now - lastWall).coerceAtLeast(1)
                    ((cpu - lastCpuTime).toDouble() / wall / cores * 100).coerceIn(0.0, 100.0)
                }
            }
            vm.key to VmUsage(cpuPercent, stats.memoryUsedBytes, stats.memoryRssBytes)
        }.toMap()
        _state.update { st -> st.copy(usage = st.usage.filterKeys { k -> running.any { it.key == k } } + usage) }
    }

    fun setFilter(filter: VmFilter) = _state.update { it.copy(filter = filter) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun act(vm: VirtualMachine, action: VmAction) {
        if (vm.key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + (vm.key to action)) }
        scope.launch {
            val message = try {
                val c = client(vm.connection) ?: throw IllegalStateException("libvirt isn't reachable")
                c.act(vm.path, action)
                when (action) {
                    VmAction.START -> "Started ${vm.displayName}"
                    VmAction.SHUT_DOWN -> "Asked ${vm.displayName} to shut down"
                    VmAction.REBOOT -> "Asked ${vm.displayName} to reboot"
                    VmAction.FORCE_REBOOT -> "Reset ${vm.displayName}"
                    VmAction.FORCE_OFF -> "Turned off ${vm.displayName}"
                    VmAction.PAUSE -> "Paused ${vm.displayName}"
                    VmAction.RESUME -> "Resumed ${vm.displayName}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't ${action.label.lowercase()} ${vm.displayName}"
            }
            _state.update { it.copy(busy = it.busy - vm.key, message = message) }
            loadJob?.cancel()
            loadJob = null
            refresh()
            // A guest takes a moment to act on shutdown and reboot requests; look again shortly.
            if (action == VmAction.SHUT_DOWN || action == VmAction.REBOOT) {
                delay(4_000)
                refresh()
            }
        }
    }

    fun setAutostart(vm: VirtualMachine, on: Boolean) {
        _state.update { it.copy(autostartBusy = it.autostartBusy + vm.key) }
        scope.launch {
            val message = try {
                client(vm.connection)?.setAutostart(vm.path, on)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't change autostart"
            }
            _state.update { st ->
                st.copy(
                    autostartBusy = st.autostartBusy - vm.key,
                    message = message,
                    vms = if (message == null) st.vms.map { if (it.key == vm.key) it.copy(autostart = on) else it } else st.vms,
                )
            }
        }
    }

    fun openDetail(vm: VirtualMachine) {
        if (_detail.value?.key == vm.key) return
        detailJob?.cancel()
        _detail.value = VmDetailState(vm.key)
        detailJob = scope.launch {
            while (isActive) {
                val current = _state.value.vms.firstOrNull { it.key == vm.key } ?: vm
                if (current.state.isRunning) {
                    val addresses = runCatching { client(current.connection)?.addresses(current.path) }.getOrNull().orEmpty()
                    _detail.update { it?.takeIf { d -> d.key == vm.key }?.copy(addresses = addresses) }
                }
                delay(15_000)
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailJob = null
        _detail.value = null
    }
}
