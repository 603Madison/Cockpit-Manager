package app.cockpitmanager.cockpit

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class ProcessResult(val output: String, val exitStatus: Int, val errorOutput: String?)

private fun spawnOptions(argv: List<String>, environ: List<String>, extra: Map<String, JsonElement> = emptyMap()): Map<String, JsonElement> =
    buildMap {
        put("payload", JsonPrimitive("stream"))
        put("spawn", JsonArray(argv.map(::JsonPrimitive)))
        if (environ.isNotEmpty()) put("environ", JsonArray(environ.map(::JsonPrimitive)))
        putAll(extra)
    }

/** Locale-neutral output, so it can be parsed. */
private val C_LOCALE = listOf("LC_ALL=C.UTF-8")

/**
 * Runs a program on the server and returns what it printed, whatever its exit
 * status. stderr comes back separately in [ProcessResult.errorOutput].
 */
suspend fun CockpitTransport.run(
    argv: List<String>,
    superuser: Superuser = Superuser.NONE,
    environ: List<String> = C_LOCALE,
    input: String? = null,
    timeoutMillis: Long = 60_000,
): ProcessResult {
    val channel = channel(spawnOptions(argv, environ, mapOf("err" to JsonPrimitive("message"))), superuser)
    if (input != null) channel.send(input)
    channel.done()
    return try {
        withTimeout(timeoutMillis) {
            val out = StringBuilder()
            for (event in channel.events) {
                when (event) {
                    is ChannelEvent.Data -> out.append(event.text)
                    is ChannelEvent.Control -> Unit
                    is ChannelEvent.Closed -> {
                        val status = event.int("exit-status")
                        val stderr = event.string("message")?.takeIf { it.isNotBlank() }
                        if (status == null) {
                            val signal = event.string("exit-signal")
                            throw when {
                                signal != null -> ProcessFailedException("${argv.first()} was killed ($signal)", null, out.toString())
                                event.problem == "not-found" -> ProcessFailedException("${argv.first()} isn't installed on the server", null, out.toString())
                                else -> CockpitException(Problems.describe(event.problem, stderr), event.problem)
                            }
                        }
                        return@withTimeout ProcessResult(out.toString(), status, stderr)
                    }
                }
            }
            throw CockpitException(Problems.describe("disconnected"), "disconnected")
        }
    } finally {
        channel.close()
    }
}

/** Like [run], but a non-zero exit is an error carrying the program's own message. */
suspend fun CockpitTransport.runChecked(
    argv: List<String>,
    superuser: Superuser = Superuser.NONE,
    environ: List<String> = C_LOCALE,
    timeoutMillis: Long = 60_000,
): String {
    val result = run(argv, superuser, environ, timeoutMillis = timeoutMillis)
    if (result.exitStatus != 0) {
        throw ProcessFailedException(
            result.errorOutput?.trim()?.ifBlank { null } ?: "${argv.first()} failed (exit status ${result.exitStatus})",
            result.exitStatus,
            result.output,
        )
    }
    return result.output
}

/**
 * A long-running program's output as it arrives, in arbitrary chunks. The
 * flow ends when the program exits; collecting stops it.
 */
fun CockpitTransport.stream(
    argv: List<String>,
    superuser: Superuser = Superuser.NONE,
    environ: List<String> = C_LOCALE,
): Flow<String> = callbackFlow {
    val channel = channel(spawnOptions(argv, environ, mapOf("err" to JsonPrimitive("message"))), superuser)
    channel.done()
    launch {
        for (event in channel.events) {
            when (event) {
                is ChannelEvent.Data -> send(event.text)
                is ChannelEvent.Control -> Unit
                is ChannelEvent.Closed -> {
                    val status = event.int("exit-status")
                    if (event.problem != null && status == null) {
                        close(CockpitException(Problems.describe(event.problem, event.string("message")), event.problem))
                    } else if (status != null && status != 0) {
                        close(ProcessFailedException(event.string("message")?.trim()?.ifBlank { null } ?: "${argv.first()} exited with status $status", status, ""))
                    } else {
                        close()
                    }
                }
            }
        }
    }
    awaitClose { channel.close() }
}

/** Splits a stream of chunks into complete lines. */
class LineBuffer {
    private val pending = StringBuilder()

    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val lines = mutableListOf<String>()
        var start = 0
        while (true) {
            val nl = pending.indexOf("\n", start)
            if (nl < 0) break
            lines += pending.substring(start, nl)
            start = nl + 1
        }
        pending.delete(0, start)
        return lines
    }
}

/** Quotes one argument for /bin/sh. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
