package app.cockpitmanager.system

import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.Superuser
import app.cockpitmanager.cockpit.run
import app.cockpitmanager.cockpit.runChecked

/** The slow-changing facts on the Overview, read with one small script. */
data class SystemFacts(
    val hostname: String = "",
    /** PRETTY_NAME from /etc/os-release, e.g. "Red Hat Enterprise Linux 10.0 (Coughlan)". */
    val os: String = "",
    val kernel: String = "",
    val arch: String = "",
    val cpuCount: Int = 1,
    val cpuModel: String = "",
    val vendor: String = "",
    val model: String = "",
    val memTotalBytes: Long = 0,
    val swapTotalBytes: Long = 0,
    val swapFreeBytes: Long = 0,
    val uptimeSeconds: Long = 0,
    val load: List<Double> = emptyList(),
) {
    val hardware: String
        get() = listOf(vendor, model).filter { it.isNotBlank() && !it.contains("To Be Filled", ignoreCase = true) }
            .distinct().joinToString(" ")

    companion object {
        val SCRIPT = """
            echo "hostname=$(hostname 2>/dev/null || cat /proc/sys/kernel/hostname)"
            echo "os=$(grep -m1 '^PRETTY_NAME=' /etc/os-release /usr/lib/os-release 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '"')"
            echo "kernel=$(uname -r)"
            echo "arch=$(uname -m)"
            echo "nproc=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)"
            echo "cpu=$(grep -m1 -E '^(model name|Model|Hardware)' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//')"
            echo "vendor=$(cat /sys/devices/virtual/dmi/id/sys_vendor 2>/dev/null)"
            echo "model=$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || cat /proc/device-tree/model 2>/dev/null | tr -d '\0')"
            grep -E '^(MemTotal|SwapTotal|SwapFree):' /proc/meminfo | tr -d ' ' | sed 's/kB$//; s/:/=/'
            echo "uptime=$(cut -d' ' -f1 /proc/uptime)"
            echo "load=$(cut -d' ' -f1-3 /proc/loadavg)"
        """.trimIndent()

        fun parse(output: String): SystemFacts {
            val kv = output.lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }.toMap()
            fun kib(key: String) = (kv[key]?.toLongOrNull() ?: 0L) * 1024
            return SystemFacts(
                hostname = kv["hostname"].orEmpty(),
                os = kv["os"].orEmpty(),
                kernel = kv["kernel"].orEmpty(),
                arch = kv["arch"].orEmpty(),
                cpuCount = kv["nproc"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                cpuModel = kv["cpu"].orEmpty().replace(Regex("\\s+"), " "),
                vendor = kv["vendor"].orEmpty(),
                model = kv["model"].orEmpty(),
                memTotalBytes = kib("MemTotal"),
                swapTotalBytes = kib("SwapTotal"),
                swapFreeBytes = kib("SwapFree"),
                uptimeSeconds = kv["uptime"]?.toDoubleOrNull()?.toLong() ?: 0,
                load = kv["load"].orEmpty().split(' ').mapNotNull { it.toDoubleOrNull() },
            )
        }

        suspend fun read(transport: CockpitTransport): SystemFacts =
            parse(transport.runChecked(listOf("/bin/sh", "-c", SCRIPT)))
    }
}

/** Whether this session can act as root (Cockpit's "administrative access"). */
suspend fun CockpitTransport.hasAdminAccess(): Boolean =
    runCatching { run(listOf("true"), Superuser.REQUIRE, timeoutMillis = 15_000).exitStatus == 0 }.getOrDefault(false)

enum class PowerAction(val label: String, val flag: String) {
    RESTART("Restart", "--reboot"),
    SHUT_DOWN("Shut down", "--poweroff"),
}

/** Restarts or powers off the server now, as Cockpit's own power menu does. */
suspend fun CockpitTransport.power(action: PowerAction) {
    runChecked(listOf("shutdown", action.flag, "now"), Superuser.REQUIRE, timeoutMillis = 30_000)
}
