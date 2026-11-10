package app.cockpitmanager.ui.containers

import app.cockpitmanager.cockpit.ByteLineBuffer
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.Superuser
import app.cockpitmanager.cockpit.httpStream
import app.cockpitmanager.system.ContainerAction
import app.cockpitmanager.system.ContainerDetails
import app.cockpitmanager.system.ContainerStats
import app.cockpitmanager.system.PodmanClient
import app.cockpitmanager.system.PodmanContainer
import app.cockpitmanager.system.PodmanOwner
import app.cockpitmanager.system.PodmanServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ContainerFilter(val label: String) { RUNNING("Running"), ALL("All") }

data class ContainersState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val containers: List<PodmanContainer> = emptyList(),
    /** Live usage by [PodmanContainer.key]. */
    val stats: Map<String, ContainerStats> = emptyMap(),
    val error: String? = null,
    /** Podman services that couldn't be reached, in words. */
    val notes: List<String> = emptyList(),
    val filter: ContainerFilter = ContainerFilter.RUNNING,
    val query: String = "",
    val busy: Map<String, ContainerAction> = emptyMap(),
    val message: String? = null,
) {
    /** Infra containers are pods' plumbing; cockpit-podman hides them too. */
    private val shown get() = containers.filterNot { it.isInfra }

    val visible: List<PodmanContainer>
        get() {
            val q = query.trim()
            return shown.filter { c ->
                (q.isNotEmpty() || filter == ContainerFilter.ALL || c.isRunning || c.isPaused) &&
                    (q.isEmpty() || listOfNotNull(c.name, c.image, c.podName, c.systemdUnit).any { it.contains(q, ignoreCase = true) })
            }.sortedWith(compareBy({ it.podName ?: "" }, { !it.isRunning }, { it.name.lowercase() }))
        }

    /** Up, including paused (as the Running filter shows them). */
    val running: Int get() = shown.count { it.isRunning || it.isPaused }
    val paused: Int get() = shown.count { it.isPaused }
    val total: Int get() = shown.size
    val stopped: Int get() = total - running
    val bothOwners: Boolean get() = shown.map { it.owner }.distinct().size > 1
}

data class ContainerDetailState(
    val key: String,
    val details: ContainerDetails? = null,
    val logs: List<String> = emptyList(),
    val logsError: String? = null,
    val error: String? = null,
)

/**
 * Containers from every Podman service this session can reach (the user's
 * and, with administrative access, the system's), as cockpit-podman shows
 * them. Refreshes on Podman's own events while the screen is open.
 */
class ContainersModel(
    private val transport: CockpitTransport,
    private val adminAccess: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ContainersState())
    val state: StateFlow<ContainersState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<ContainerDetailState?>(null)
    val detail: StateFlow<ContainerDetailState?> = _detail.asStateFlow()

    private val connectLock = Mutex()
    private var clients: List<PodmanClient>? = null
    private val jobs = mutableListOf<Job>()
    private var detailJobs = mutableListOf<Job>()
    private var loadJob: Job? = null

    private suspend fun clients(): List<PodmanClient> = connectLock.withLock {
        clients ?: run {
            val (found, problems) = PodmanServices.connect(transport, adminAccess())
            val notes = problems.toMutableList()
            if (found.none { it.owner == PodmanOwner.SYSTEM } && notes.none { it.startsWith("System") }) {
                notes += "System containers need administrative access (turn it on in the server's settings)."
            }
            _state.update { it.copy(notes = notes) }
            clients = found
            found
        }
    }

    private fun client(owner: PodmanOwner) = clients?.firstOrNull { it.owner == owner }

    /** Loads once, e.g. for the Overview's summary. */
    fun ensureLoaded() {
        if (!_state.value.loaded) refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val all = clients()
                val lists = all.map { c -> async { runCatching { c.containers() } } }.awaitAll()
                val failure = lists.firstNotNullOfOrNull { it.exceptionOrNull() }
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        containers = lists.flatMap { r -> r.getOrNull().orEmpty() },
                        error = if (all.isEmpty()) "Couldn't reach Podman on this server." else failure?.message,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loaded = true, error = e.message ?: "Couldn't list containers") }
            }
        }
    }

    /** While the list is on screen: live stats, and refreshes when Podman reports container events. */
    /** Screens showing live data; start/stop pair up, so the list and a detail page can overlap during navigation. */
    private var watchers = 0

    fun start() {
        watchers++
        if (jobs.isNotEmpty()) return
        refresh()
        jobs += scope.launch {
            val all = clients()
            all.forEach { c ->
                jobs += launch {
                    c.stats().catch { }.collect { byId ->
                        _state.update { st -> st.copy(stats = st.stats + byId.mapKeys { (id, _) -> "${c.owner.name}:$id" }) }
                    }
                }
                jobs += launch {
                    val lines = ByteLineBuffer()
                    var pending: Job? = null
                    val superuser = if (c.owner == PodmanOwner.SYSTEM) Superuser.REQUIRE else Superuser.NONE
                    transport.httpStream(c.socket, "${PodmanClient.VERSION}libpod/events", superuser)
                        .catch { }
                        .collect { bytes ->
                            if (lines.feed(bytes).any { eventType(it) in setOf("container", "pod") }) {
                                // Bursts of events (create, init, start...) settle into one refresh.
                                pending?.cancel()
                                pending = launch {
                                    delay(400)
                                    refresh()
                                }
                            }
                        }
                }
            }
            // A slow safety net in case events aren't available.
            while (isActive) {
                delay(30_000)
                refresh()
            }
        }
    }

    fun stop() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers > 0) return
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    fun setFilter(filter: ContainerFilter) = _state.update { it.copy(filter = filter) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun act(container: PodmanContainer, action: ContainerAction) {
        if (container.key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + (container.key to action)) }
        scope.launch {
            val message = try {
                val c = client(container.owner) ?: throw IllegalStateException("Podman isn't reachable")
                c.act(container, action)
                when (action) {
                    ContainerAction.START -> "Started ${container.name}"
                    ContainerAction.STOP, ContainerAction.FORCE_STOP -> "Stopped ${container.name}"
                    ContainerAction.RESTART, ContainerAction.FORCE_RESTART -> "Restarted ${container.name}"
                    ContainerAction.PAUSE -> "Paused ${container.name}"
                    ContainerAction.RESUME -> "Resumed ${container.name}"
                    ContainerAction.DELETE -> "Deleted ${container.name}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't ${action.label.lowercase()} ${container.name}"
            }
            _state.update { it.copy(busy = it.busy - container.key, message = message) }
            loadJob?.cancel()
            loadJob = null
            refresh()
            if (_detail.value?.key == container.key && action != ContainerAction.DELETE) loadDetails(container)
        }
    }

    fun openDetail(container: PodmanContainer) {
        if (_detail.value?.key == container.key) return
        closeDetail()
        _detail.value = ContainerDetailState(container.key)
        loadDetails(container)
        detailJobs += scope.launch {
            val c = clients().firstOrNull { it.owner == container.owner } ?: return@launch
            val lines = ArrayDeque<String>()
            var partial = ""
            c.logs(container.id, tail = 200)
                .catch { e -> _detail.update { it?.takeIf { d -> d.key == container.key }?.copy(logsError = e.message ?: "Logs aren't available") } }
                .collect { text ->
                    val parts = (partial + text).split('\n')
                    partial = parts.last()
                    parts.dropLast(1).forEach { line ->
                        lines.addLast(line)
                        while (lines.size > MAX_LOG_LINES) lines.removeFirst()
                    }
                    val snapshot = lines.toList() + listOfNotNull(partial.ifEmpty { null })
                    _detail.update { it?.takeIf { d -> d.key == container.key }?.copy(logs = snapshot) }
                }
        }
    }

    fun closeDetail() {
        detailJobs.forEach { it.cancel() }
        detailJobs.clear()
        _detail.value = null
    }

    private fun loadDetails(container: PodmanContainer) {
        detailJobs += scope.launch {
            val c = clients().firstOrNull { it.owner == container.owner } ?: return@launch
            val result = runCatching { c.details(container.id) }
            _detail.update {
                it?.takeIf { d -> d.key == container.key }?.copy(
                    details = result.getOrNull() ?: it.details,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    companion object {
        const val MAX_LOG_LINES = 400

        private fun eventType(line: String): String? =
            runCatching { Json.parseToJsonElement(line).jsonObject["Type"]?.jsonPrimitive?.content }.getOrNull()
    }
}
