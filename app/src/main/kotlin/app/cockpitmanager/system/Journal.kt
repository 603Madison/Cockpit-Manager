package app.cockpitmanager.system

import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.LineBuffer
import app.cockpitmanager.cockpit.Superuser
import app.cockpitmanager.cockpit.runChecked
import app.cockpitmanager.cockpit.stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One journal entry, as `journalctl --output=json` prints it. */
data class JournalEntry(
    val cursor: String,
    val timestampMicros: Long,
    val priority: Int,
    val identifier: String,
    val unit: String?,
    val pid: String?,
    val message: String,
) {
    val timestampMillis: Long get() = timestampMicros / 1000

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(line: String): JournalEntry? {
            if (line.isBlank()) return null
            val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
            fun s(key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
            return JournalEntry(
                cursor = s("__CURSOR").orEmpty(),
                timestampMicros = s("__REALTIME_TIMESTAMP")?.toLongOrNull() ?: 0,
                priority = s("PRIORITY")?.toIntOrNull() ?: 6,
                identifier = s("SYSLOG_IDENTIFIER") ?: s("_COMM") ?: s("_SYSTEMD_UNIT") ?: "kernel".takeIf { s("_TRANSPORT") == "kernel" } ?: "",
                unit = s("_SYSTEMD_UNIT") ?: s("UNIT"),
                pid = s("_PID") ?: s("SYSLOG_PID"),
                message = message(o["MESSAGE"]),
            )
        }

        /** MESSAGE is a string, or an array of bytes when it isn't valid UTF-8. */
        private fun message(e: kotlinx.serialization.json.JsonElement?): String = when (e) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonArray -> {
                val bytes = e.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.toByte() }.toByteArray()
                String(bytes, Charsets.UTF_8)
            }
            else -> ""
        }
    }
}

/** How much to show, by syslog priority: everything at or above the chosen level. */
enum class LogLevel(val label: String, val maxPriority: Int) {
    ERRORS("Errors", 3),
    WARNINGS("Warnings", 4),
    NOTICES("Notices", 5),
    ALL("All", 7),
}

data class JournalQuery(
    val level: LogLevel = LogLevel.WARNINGS,
    val unit: String? = null,
    val lines: Int = 300,
    val currentBootOnly: Boolean = false,
) {
    fun args(follow: Boolean): List<String> = buildList {
        addAll(listOf("journalctl", "--no-pager", "--quiet", "--all", "--output=json"))
        if (follow) addAll(listOf("--follow", "--lines=0")) else addAll(listOf("--reverse", "--lines=$lines"))
        if (level != LogLevel.ALL) add("--priority=0..${level.maxPriority}")
        if (unit != null) add("--unit=$unit")
        if (currentBootOnly) add("--boot")
    }
}

/** Reads the system journal. Runs with administrative access when the session has it, to see every unit's logs. */
class JournalRepository(private val transport: CockpitTransport) {
    /** Newest first. */
    suspend fun read(query: JournalQuery): List<JournalEntry> {
        val output = transport.runChecked(query.args(follow = false), Superuser.TRY, timeoutMillis = 60_000)
        return withContext(Dispatchers.Default) { output.lineSequence().mapNotNull(JournalEntry::parse).toList() }
    }

    /** New entries as they're written, oldest first. */
    fun follow(query: JournalQuery): Flow<JournalEntry> = flow {
        val lines = LineBuffer()
        transport.stream(query.args(follow = true), Superuser.TRY).collect { chunk ->
            lines.feed(chunk).forEach { line -> JournalEntry.parse(line)?.let { emit(it) } }
        }
    }
}
