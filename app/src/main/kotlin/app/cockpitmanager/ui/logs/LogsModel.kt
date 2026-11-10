package app.cockpitmanager.ui.logs

import app.cockpitmanager.system.JournalEntry
import app.cockpitmanager.system.JournalQuery
import app.cockpitmanager.system.JournalRepository
import app.cockpitmanager.system.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogsState(
    val loading: Boolean = true,
    val entries: List<JournalEntry> = emptyList(),
    val error: String? = null,
    val query: JournalQuery = JournalQuery(),
    val search: String = "",
    val live: Boolean = false,
    val loaded: Boolean = false,
) {
    /** Newest first, filtered by the search text. */
    val visible: List<JournalEntry>
        get() {
            val q = search.trim()
            if (q.isEmpty()) return entries
            return entries.filter {
                it.message.contains(q, ignoreCase = true) || it.identifier.contains(q, ignoreCase = true) || it.unit?.contains(q, ignoreCase = true) == true
            }
        }
}

class LogsModel(private val journal: JournalRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(LogsState())
    val state: StateFlow<LogsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var followJob: Job? = null

    /** Called when the screen opens: loads once, and resumes following if it was on. */
    fun start() {
        if (!_state.value.loaded) load()
        if (_state.value.live) follow()
    }

    fun stop() {
        followJob?.cancel()
        followJob = null
    }

    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val entries = journal.read(_state.value.query)
                _state.update { it.copy(loading = false, entries = entries, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't read the journal", loaded = true) }
            }
        }
    }

    fun setLevel(level: LogLevel) = requery { it.copy(level = level, lines = JournalQuery().lines) }
    fun setBootOnly(on: Boolean) = requery { it.copy(currentBootOnly = on) }
    fun loadMore() = requery { it.copy(lines = it.lines + 300) }
    fun setSearch(text: String) = _state.update { it.copy(search = text) }

    fun setLive(on: Boolean) {
        _state.update { it.copy(live = on) }
        if (on) follow() else stop()
    }

    private fun requery(change: (JournalQuery) -> JournalQuery) {
        _state.update { it.copy(query = change(it.query)) }
        load()
        if (_state.value.live) follow()
    }

    private fun follow() {
        followJob?.cancel()
        val query = _state.value.query
        followJob = scope.launch {
            journal.follow(query)
                .catch { e -> _state.update { it.copy(live = false, error = e.message ?: "Live updates stopped") } }
                .collect { entry ->
                    _state.update { st -> st.copy(entries = (listOf(entry) + st.entries).take(MAX_ENTRIES)) }
                }
        }
    }

    companion object {
        const val MAX_ENTRIES = 3000
    }
}
