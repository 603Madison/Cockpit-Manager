package app.cockpitmanager.ui.services

import app.cockpitmanager.system.JournalEntry
import app.cockpitmanager.system.JournalQuery
import app.cockpitmanager.system.JournalRepository
import app.cockpitmanager.system.LogLevel
import app.cockpitmanager.system.ServiceAction
import app.cockpitmanager.system.ServiceDetails
import app.cockpitmanager.system.ServiceUnit
import app.cockpitmanager.system.ServicesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServiceFilter(val label: String) {
    RUNNING("Running"),
    FAILED("Failed"),
    BOOT("At boot"),
    ALL("All"),
    ;

    fun matches(s: ServiceUnit) = when (this) {
        RUNNING -> s.isActive || s.isTransitioning
        FAILED -> s.isFailed
        BOOT -> s.isEnabled
        ALL -> true
    }
}

data class ServicesState(
    val loading: Boolean = true,
    val services: List<ServiceUnit> = emptyList(),
    val error: String? = null,
    val filter: ServiceFilter = ServiceFilter.RUNNING,
    val query: String = "",
    /** Units with an action in flight, and which. */
    val busy: Map<String, ServiceAction> = emptyMap(),
    val message: String? = null,
) {
    val visible: List<ServiceUnit>
        get() {
            val q = query.trim()
            return services.filter { s ->
                (q.isNotEmpty() || filter.matches(s)) &&
                    (q.isEmpty() || s.name.contains(q, ignoreCase = true) || s.description.contains(q, ignoreCase = true))
            }.sortedWith(compareBy({ !it.isFailed }, { it.name.lowercase() }))
        }

    fun count(filter: ServiceFilter) = services.count(filter::matches)
}

data class ServiceDetailState(
    val name: String,
    val loading: Boolean = true,
    val details: ServiceDetails? = null,
    val logs: List<JournalEntry> = emptyList(),
    val error: String? = null,
)

class ServicesModel(
    private val repository: ServicesRepository,
    private val journal: JournalRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ServicesState())
    val state: StateFlow<ServicesState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<ServiceDetailState?>(null)
    val detail: StateFlow<ServiceDetailState?> = _detail.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val list = repository.list()
                _state.update { it.copy(loading = false, services = list, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't list services") }
            }
        }
    }

    fun setFilter(filter: ServiceFilter) = _state.update { it.copy(filter = filter) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun act(unit: String, action: ServiceAction) {
        if (unit in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + (unit to action)) }
        scope.launch {
            val short = unit.removeSuffix(".service")
            val message = try {
                repository.act(unit, action)
                when (action) {
                    ServiceAction.START -> "Started $short"
                    ServiceAction.STOP -> "Stopped $short"
                    ServiceAction.RESTART -> "Restarted $short"
                    ServiceAction.RELOAD -> "Reloaded $short"
                    ServiceAction.ENABLE -> "$short will start at boot"
                    ServiceAction.DISABLE -> "$short won't start at boot"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't ${action.verb} $short"
            }
            _state.update { it.copy(busy = it.busy - unit, message = message) }
            loadJob?.cancel()
            loadJob = null
            refresh()
            if (_detail.value?.name == unit) loadDetail(unit, keepContent = true)
        }
    }

    fun openDetail(unit: String) {
        if (_detail.value?.name != unit) _detail.value = ServiceDetailState(unit)
        loadDetail(unit, keepContent = true)
    }

    fun closeDetail() {
        _detail.value = null
    }

    private fun loadDetail(unit: String, keepContent: Boolean) {
        scope.launch {
            if (!keepContent) _detail.value = ServiceDetailState(unit)
            val details = runCatching { repository.details(unit) }
            val logs = runCatching { journal.read(JournalQuery(level = LogLevel.ALL, unit = unit, lines = 60)) }
            if (_detail.value?.name != unit) return@launch
            _detail.update {
                it?.copy(
                    loading = false,
                    details = details.getOrNull() ?: it.details,
                    logs = logs.getOrNull() ?: it.logs,
                    error = details.exceptionOrNull()?.message,
                )
            }
        }
    }
}
