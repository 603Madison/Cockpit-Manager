package app.cockpitmanager.terminal

import app.cockpitmanager.cockpit.ChannelEvent
import app.cockpitmanager.cockpit.CockpitChannel
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.Problems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An interactive login shell on the server, in a pseudo-terminal, as
 * Cockpit's Terminal page runs it. Output goes into [emulator]; [version]
 * changes whenever the screen does.
 */
class TerminalSession(private val transport: CockpitTransport, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data object Running : State
        data class Ended(val message: String) : State
    }

    val emulator = TerminalEmulator(24, 80)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var channel: CockpitChannel? = null
    private var reader: Job? = null

    fun start(rows: Int = emulator.rows, cols: Int = emulator.cols) {
        stop()
        emulator.reset()
        emulator.resize(rows, cols)
        val ch = transport.channel(
            mapOf(
                "payload" to JsonPrimitive("stream"),
                "spawn" to JsonArray(SHELL.map(::JsonPrimitive)),
                "environ" to JsonArray(listOf("TERM=xterm-256color", "COLORTERM=truecolor").map(::JsonPrimitive)),
                "pty" to JsonPrimitive(true),
                "window" to window(emulator.rows, emulator.cols),
            ),
        )
        channel = ch
        emulator.onResponse = { ch.send(it) }
        _state.value = State.Running
        reader = scope.launch {
            for (event in ch.events) {
                when (event) {
                    is ChannelEvent.Data -> {
                        emulator.feed(event.text)
                        _version.value = emulator.version
                    }
                    is ChannelEvent.Control -> Unit
                    is ChannelEvent.Closed -> {
                        val status = event.int("exit-status")
                        _state.value = State.Ended(
                            when {
                                event.problem != null -> Problems.describe(event.problem, event.string("message"))
                                status != null && status != 0 -> "The shell exited with status $status"
                                else -> "The shell exited"
                            },
                        )
                    }
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isNotEmpty()) channel?.send(text)
    }

    fun send(key: TerminalEmulator.Key) = send(emulator.encode(key))

    fun resize(rows: Int, cols: Int) {
        if (emulator.resize(rows, cols)) {
            _version.value = emulator.version
            channel?.control("options", mapOf("window" to window(emulator.rows, emulator.cols)))
        }
    }

    fun stop() {
        reader?.cancel()
        reader = null
        channel?.close()
        channel = null
        if (_state.value is State.Running) _state.value = State.Idle
    }

    private fun window(rows: Int, cols: Int) = JsonObject(mapOf("rows" to JsonPrimitive(rows), "cols" to JsonPrimitive(cols)))

    companion object {
        /** The user's own login shell, started in their home directory. */
        val SHELL = listOf(
            "/bin/sh", "-c",
            "s=\$(getent passwd \"\$(id -un)\" | cut -d: -f7); cd 2>/dev/null; exec \"\${s:-/bin/sh}\" -l",
        )
    }
}
