package app.cockpitmanager.cockpit

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a Cockpit server lives: scheme, host, port and an optional URL root
 * (Cockpit's `UrlRoot`, for servers behind a reverse proxy).
 *
 * Cockpit listens on 9090 by default, so an address typed without a scheme or
 * port ("server.local") means https://server.local:9090. An address typed with
 * an explicit scheme but no port keeps that scheme's standard port, for
 * reverse-proxied setups ("https://cockpit.example.com").
 */
data class ServerUrl(
    val https: Boolean,
    val host: String,
    val port: Int,
    /** URL root without a trailing slash: "" or e.g. "/admin". */
    val root: String = "",
) {
    private val scheme get() = if (https) "https" else "http"
    private val defaultPort get() = if (https) 443 else 80

    /** host[:port], formatted the way OkHttp writes the Host header (and so the way Cockpit checks Origin). */
    val hostHeader: String
        get() {
            val h = if (':' in host) "[$host]" else host
            return if (port == defaultPort) h else "$h:$port"
        }

    /** The Origin Cockpit expects on the WebSocket: the same scheme and Host it was reached on. */
    val origin: String get() = "$scheme://$hostHeader"

    val base: HttpUrl get() = "$scheme://$hostHeader$root/".toHttpUrlOrNull()!!

    val loginUrl: HttpUrl get() = base.newBuilder().addPathSegments("cockpit/login").build()
    val socketUrl: HttpUrl get() = base.newBuilder().addPathSegments("cockpit/socket").build()

    /** What to show people: no default port, no trailing slash. */
    val display: String get() = "$scheme://$hostHeader$root"

    /**
     * Plain http:// sends the password in the clear, so it's only allowed to
     * addresses that can't be on the public internet: private and loopback
     * ranges, Tailscale's 100.64/10, link-local, and LAN-only names.
     */
    val cleartextAllowed: Boolean get() = https || isPrivateHost(host)

    companion object {
        const val COCKPIT_PORT = 9090

        /** Cockpit pages people may paste along with the address; stripped off. */
        private val pageSegments = setOf(
            "system", "users", "network", "storage", "services", "logs", "terminal", "updates", "metrics",
            "apps", "shell", "machines", "podman", "selinux", "kdump", "sosreport", "playground", "packagekit",
        )

        fun parse(input: String): ServerUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val hasScheme = trimmed.contains("://")
            if (hasScheme && !trimmed.startsWith("https://", true) && !trimmed.startsWith("http://", true)) return null
            val url = (if (hasScheme) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
            if (url.host.isBlank()) return null
            // OkHttp fills in 443/80; only an explicit port or explicit scheme overrides Cockpit's 9090.
            val explicitPort = hasExplicitPort(trimmed)
            val port = when {
                explicitPort -> url.port
                hasScheme -> url.port
                else -> COCKPIT_PORT
            }
            return ServerUrl(https = url.isHttps, host = url.host, port = port, root = rootOf(url.encodedPathSegments))
        }

        private fun hasExplicitPort(input: String): Boolean {
            val afterScheme = input.substringAfter("://", input)
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
            return if (authority.startsWith("[")) {
                authority.substringAfter(']', "").startsWith(":")
            } else {
                authority.contains(':')
            }
        }

        private fun rootOf(segments: List<String>): String {
            var parts = segments.filter { it.isNotEmpty() }
            val cockpitIndex = parts.indexOf("cockpit")
            if (cockpitIndex >= 0) parts = parts.take(cockpitIndex)
            // Drop a pasted page path such as /system/services or /@localhost/system.
            val atHost = parts.indexOfFirst { it.startsWith("@") }
            if (atHost >= 0) parts = parts.take(atHost)
            while (parts.isNotEmpty() && parts.last() in pageSegments) parts = parts.dropLast(1)
            // A page with a sub-path (e.g. /system/services) leaves its first segment behind too.
            val page = parts.indexOfFirst { it in pageSegments }
            if (page >= 0) parts = parts.take(page)
            return if (parts.isEmpty()) "" else parts.joinToString("/", prefix = "/")
        }

        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (h == "localhost") return true
            if (!h.contains('.') && !h.contains(':')) return true // single-label LAN name, e.g. "nas"
            if (listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".ts.net").any { h.endsWith(it) }) return true
            ipv4(h)?.let { (a, b) ->
                return a == 10 || a == 127 ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127)
            }
            if (h.contains(':')) {
                val v6 = h.removePrefix("[").removeSuffix("]")
                return v6 == "::1" || v6.startsWith("fd") || v6.startsWith("fc") || v6.startsWith("fe80")
            }
            return false
        }

        private fun ipv4(h: String): Pair<Int, Int>? {
            val parts = h.split('.')
            if (parts.size != 4) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it !in 0..255 }) return null
            return nums[0] to nums[1]
        }
    }
}
