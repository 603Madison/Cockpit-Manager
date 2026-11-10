package app.cockpitmanager.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    private val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    /** 1536 → "1.5 KiB". Binary units, as Cockpit shows them. */
    fun bytes(value: Double?): String {
        if (value == null || value.isNaN()) return "–"
        var v = abs(value)
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        val number = when {
            i == 0 -> v.roundToInt().toString()
            v >= 100 -> v.roundToInt().toString()
            v >= 10 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
        return "$number ${units[i]}"
    }

    fun bytes(value: Long?): String = bytes(value?.toDouble())

    fun rate(bytesPerSecond: Double?): String = if (bytesPerSecond == null) "–" else "${bytes(bytesPerSecond)}/s"

    fun percent(fraction: Double?): String = if (fraction == null || fraction.isNaN()) "–" else "${(fraction * 100).roundToInt()}%"

    /** 93784 → "1 day, 2 hours". */
    fun duration(seconds: Long): String {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3600
        val minutes = (seconds % 3600) / 60
        fun plural(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"}"
        return when {
            days > 0 -> if (hours > 0) "${plural(days, "day")}, ${plural(hours, "hour")}" else plural(days, "day")
            hours > 0 -> if (minutes > 0) "${plural(hours, "hour")}, $minutes min" else plural(hours, "hour")
            minutes > 0 -> "$minutes min"
            else -> "${seconds}s"
        }
    }

    /** CPU time in nanoseconds → "1min 12.3s"-style. */
    fun cpuTime(nanos: Long): String {
        val seconds = nanos / 1e9
        return when {
            seconds >= 3600 -> "${(seconds / 3600).toInt()}h ${((seconds % 3600) / 60).toInt()}min"
            seconds >= 60 -> "${(seconds / 60).toInt()}min ${String.format(Locale.US, "%.1f", seconds % 60)}s"
            else -> String.format(Locale.US, "%.2fs", seconds)
        }
    }

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = time.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "Today", "Yesterday" or "Saturday, November 8". */
    fun day(millis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()).format(date)
        }
    }

    /** "just now", "5 min ago", "3 days ago". */
    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - millis) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> (s / 3600).let { "$it hour${if (it == 1L) "" else "s"} ago" }
            else -> (s / 86_400).let { "$it day${if (it == 1L) "" else "s"} ago" }
        }
    }
}
