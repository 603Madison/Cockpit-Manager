package app.cockpitmanager.system

import app.cockpitmanager.cockpit.ByteLineBuffer
import app.cockpitmanager.cockpit.CockpitException
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.Superuser
import app.cockpitmanager.cockpit.httpCall
import app.cockpitmanager.cockpit.httpStream
import app.cockpitmanager.cockpit.run
import app.cockpitmanager.cockpit.runChecked
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * Whose containers: the system's (root, through administrative access) or
 * the signed-in user's rootless ones. cockpit-podman shows both.
 */
enum class PodmanOwner(val label: String) { SYSTEM("System"), USER("User") }

data class PodmanContainer(
    val id: String,
    val name: String,
    val image: String,
    val command: String,
    /** "running", "exited", "paused", "created", "stopped", ... */
    val state: String,
    val createdMillis: Long?,
    val startedMillis: Long?,
    val exitedMillis: Long?,
    val exitCode: Int,
    val podId: String?,
    val podName: String?,
    val ports: List<String>,
    val owner: PodmanOwner,
    val isInfra: Boolean,
    /** The systemd unit when the container is a Quadlet service, managed through systemctl. */
    val systemdUnit: String?,
) {
    val shortId: String get() = id.take(12)
    val isRunning get() = state == "running"
    val isPaused get() = state == "paused"
    val isFailed get() = !isRunning && !isPaused && state != "created" && exitCode != 0
    val isService get() = systemdUnit != null
    /** A stable key across owners: the same id can't clash, but be safe. */
    val key: String get() = "${owner.name}:$id"
}

data class ContainerStats(
    val cpuPercent: Double?,
    val memUsage: Long?,
    val memLimit: Long?,
    val netIn: Long?,
    val netOut: Long?,
    val blockIn: Long?,
    val blockOut: Long?,
    val pids: Long?,
)

data class ContainerDetails(
    val imageName: String,
    val command: String,
    val createdMillis: Long?,
    val startedMillis: Long?,
    val finishedMillis: Long?,
    val exitCode: Int?,
    val error: String?,
    val health: String?,
    val restartPolicy: String?,
    val restartCount: Int?,
    val ports: List<String>,
    val networks: List<Pair<String, String>>,
    val mounts: List<String>,
    val tty: Boolean,
    val systemdUnit: String?,
)

enum class ContainerAction(val label: String, val progress: String) {
    START("Start", "Starting"),
    STOP("Stop", "Stopping"),
    FORCE_STOP("Force stop", "Stopping"),
    RESTART("Restart", "Restarting"),
    FORCE_RESTART("Force restart", "Restarting"),
    PAUSE("Pause", "Pausing"),
    RESUME("Resume", "Resuming"),
    DELETE("Delete", "Deleting"),
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** Podman writes times as RFC 3339 strings (or Unix seconds in older versions); Go's zero time means "never". */
internal fun podmanTime(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    val millis = if (p.isString) {
        runCatching { Instant.parse(p.content).toEpochMilli() }.getOrNull()
    } else {
        p.longOrNull?.let { it * 1000 }
    }
    return millis?.takeIf { it > 0 }
}

/** Is this a systemd-managed (Quadlet) container? Same rule as cockpit-podman. */
internal fun systemdUnitOf(labels: JsonObject?): String? =
    labels?.get("PODMAN_SYSTEMD_UNIT").str()?.takeIf { it.isNotBlank() && !it.startsWith("podman-compose@") }

object PodmanParsers {
    private val json = Json { ignoreUnknownKeys = true }

    /** GET libpod/containers/json?all=true */
    fun containers(text: String, owner: PodmanOwner): List<PodmanContainer> {
        val list = runCatching { json.parseToJsonElement(text).arr() }.getOrNull() ?: return emptyList()
        return list.mapNotNull { e ->
            val c = e.obj() ?: return@mapNotNull null
            val names = c["Names"].arr()?.mapNotNull { it.str() }.orEmpty()
            PodmanContainer(
                id = c["Id"].str() ?: return@mapNotNull null,
                name = names.firstOrNull() ?: c["Id"].str()!!.take(12),
                image = c["Image"].str().orEmpty(),
                command = c["Command"].arr()?.mapNotNull { it.str() }?.joinToString(" ").orEmpty(),
                state = c["State"].str()?.lowercase().orEmpty(),
                createdMillis = podmanTime(c["Created"]),
                startedMillis = podmanTime(c["StartedAt"]),
                exitedMillis = podmanTime(c["ExitedAt"]),
                exitCode = c["ExitCode"].long()?.toInt() ?: 0,
                podId = c["Pod"].str()?.ifBlank { null },
                podName = c["PodName"].str()?.ifBlank { null },
                ports = c["Ports"].arr()?.mapNotNull { portLabel(it.obj()) }.orEmpty().distinct(),
                owner = owner,
                isInfra = c["IsInfra"].bool() ?: false,
                systemdUnit = systemdUnitOf(c["Labels"].obj()),
            )
        }
    }

    /** "8080 → 80/tcp", from Podman 4's snake_case or Podman 3's camelCase port mappings. */
    private fun portLabel(p: JsonObject?): String? {
        p ?: return null
        val host = (p["host_port"] ?: p["hostPort"]).long() ?: return null
        val container = (p["container_port"] ?: p["containerPort"]).long() ?: return null
        val protocol = p["protocol"].str()?.ifBlank { null } ?: "tcp"
        val ip = (p["host_ip"] ?: p["hostIP"]).str()?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "::" }
        val range = p["range"].long() ?: 1
        val hostText = if (range > 1) "$host-${host + range - 1}" else "$host"
        val containerText = if (range > 1) "$container-${container + range - 1}" else "$container"
        return "${ip?.let { "$it:" } ?: ""}$hostText → $containerText/$protocol"
    }

    /** One line of GET libpod/containers/stats: {"Error":..,"Stats":[...]} keyed by container id. */
    fun stats(text: String): Map<String, ContainerStats> {
        val root = runCatching { json.parseToJsonElement(text).obj() }.getOrNull() ?: return emptyMap()
        val list = root["Stats"].arr() ?: return emptyMap()
        return list.mapNotNull { e ->
            val s = e.obj() ?: return@mapNotNull null
            val id = s["ContainerID"].str() ?: s["ContainerId"].str() ?: return@mapNotNull null
            id to ContainerStats(
                cpuPercent = s["CPU"].dbl(),
                memUsage = s["MemUsage"].long(),
                memLimit = s["MemLimit"].long(),
                netIn = s["NetInput"].long(),
                netOut = s["NetOutput"].long(),
                blockIn = s["BlockInput"].long(),
                blockOut = s["BlockOutput"].long(),
                pids = s["PIDs"].long(),
            )
        }.toMap()
    }

    /** GET libpod/containers/{id}/json. Environment variables are left out: they often hold secrets. */
    fun details(text: String): ContainerDetails? {
        val c = runCatching { json.parseToJsonElement(text).obj() }.getOrNull() ?: return null
        val state = c["State"].obj()
        val config = c["Config"].obj()
        val host = c["HostConfig"].obj()
        val network = c["NetworkSettings"].obj()
        val command = (config?.get("Entrypoint").arr()?.mapNotNull { it.str() }.orEmpty() +
            config?.get("Cmd").arr()?.mapNotNull { it.str() }.orEmpty()).joinToString(" ")
        val ports = network?.get("Ports").obj()?.flatMap { (containerPort, bindings) ->
            bindings.arr()?.mapNotNull { b ->
                val hostPort = b.obj()?.get("HostPort").str() ?: return@mapNotNull null
                val ip = b.obj()?.get("HostIp").str()?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "::" }
                "${ip?.let { "$it:" } ?: ""}$hostPort → $containerPort"
            }.orEmpty()
        }.orEmpty().distinct()
        val networks = network?.get("Networks").obj()?.mapNotNull { (name, n) ->
            val ip = n.obj()?.get("IPAddress").str()?.ifBlank { null }
            name to (ip ?: "no address")
        }.orEmpty()
        val mounts = c["Mounts"].arr()?.mapNotNull { m ->
            val o = m.obj() ?: return@mapNotNull null
            val dest = o["Destination"].str() ?: return@mapNotNull null
            val src = o["Source"].str() ?: o["Name"].str() ?: ""
            val ro = o["RW"].bool() == false
            "$src → $dest${if (ro) " (read-only)" else ""}"
        }.orEmpty()
        val restart = host?.get("RestartPolicy").obj()?.get("Name").str()?.ifBlank { null }
        return ContainerDetails(
            imageName = c["ImageName"].str() ?: config?.get("Image").str().orEmpty(),
            command = command,
            createdMillis = podmanTime(c["Created"]),
            startedMillis = podmanTime(state?.get("StartedAt")),
            finishedMillis = podmanTime(state?.get("FinishedAt")),
            exitCode = state?.get("ExitCode").long()?.toInt(),
            error = state?.get("Error").str()?.ifBlank { null },
            health = (state?.get("Health") ?: state?.get("Healthcheck")).obj()?.get("Status").str()?.ifBlank { null },
            restartPolicy = restart,
            restartCount = c["RestartCount"].long()?.toInt(),
            ports = ports,
            networks = networks,
            mounts = mounts,
            tty = config?.get("Tty").bool() ?: false,
            systemdUnit = systemdUnitOf(config?.get("Labels").obj()),
        )
    }
}

/**
 * Container logs come multiplexed: each frame is an 8-byte header (stream
 * type, three zero bytes, big-endian length) then that many bytes. Frames may
 * be split across reads, so this buffers.
 */
class PodmanLogDecoder {
    private val buffer = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): String {
        buffer.write(bytes)
        val all = buffer.toByteArray()
        val out = ByteArrayOutputStream()
        var i = 0
        while (all.size - i >= 8) {
            val size = ((all[i + 4].toInt() and 0xff) shl 24) or ((all[i + 5].toInt() and 0xff) shl 16) or
                ((all[i + 6].toInt() and 0xff) shl 8) or (all[i + 7].toInt() and 0xff)
            if (size < 0 || all.size - i - 8 < size) break
            out.write(all, i + 8, size)
            i += 8 + size
        }
        buffer.reset()
        buffer.write(all, i, all.size - i)
        return cleanLogText(String(out.toByteArray(), Charsets.UTF_8))
    }

    companion object {
        private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-_]")

        /** Removes terminal colours and carriage returns so logs read as plain lines. */
        fun cleanLogText(s: String): String = s.replace(ansi, "").replace("\r\n", "\n").replace('\r', '\n')
    }
}

/**
 * Talks to one Podman service the way cockpit-podman does: Podman's REST
 * API (v3.4.0 paths, the oldest it supports) on the service's unix socket,
 * through the bridge. The system service needs administrative access.
 */
class PodmanClient(private val transport: CockpitTransport, val owner: PodmanOwner, val socket: String) {
    private val superuser = if (owner == PodmanOwner.SYSTEM) Superuser.REQUIRE else Superuser.NONE

    private suspend fun call(method: String, path: String, params: Map<String, String> = emptyMap(), timeoutMillis: Long = 60_000) =
        transport.httpCall(socket, method, VERSION + path, params, superuser = superuser, timeoutMillis = timeoutMillis)

    /** Podman's version; throws if the service doesn't answer. */
    suspend fun version(): String? {
        val text = call("GET", "libpod/info", timeoutMillis = 15_000)
        val info = runCatching { Json.parseToJsonElement(text).obj() }.getOrNull()
        return info?.get("version").obj()?.get("Version").str()
    }

    suspend fun containers(): List<PodmanContainer> =
        PodmanParsers.containers(call("GET", "libpod/containers/json", mapOf("all" to "true")), owner)

    suspend fun details(id: String): ContainerDetails? =
        PodmanParsers.details(call("GET", "libpod/containers/$id/json", mapOf("size" to "false")))

    suspend fun act(container: PodmanContainer, action: ContainerAction) {
        val unit = container.systemdUnit
        if (unit != null && action in setOf(ContainerAction.START, ContainerAction.STOP, ContainerAction.RESTART)) {
            // Quadlet containers are systemd services: stopping them behind systemd's back would just restart them.
            val verb = action.name.lowercase()
            val args = listOf("systemctl") + (if (owner == PodmanOwner.USER) listOf("--user") else emptyList()) + listOf(verb, "--", unit)
            transport.runChecked(args, superuser, timeoutMillis = 120_000)
            return
        }
        val id = container.id
        when (action) {
            ContainerAction.START -> call("POST", "libpod/containers/$id/start")
            ContainerAction.STOP -> call("POST", "libpod/containers/$id/stop", timeoutMillis = 120_000)
            ContainerAction.FORCE_STOP -> call("POST", "libpod/containers/$id/stop", mapOf("t" to "0"))
            ContainerAction.RESTART -> call("POST", "libpod/containers/$id/restart", timeoutMillis = 120_000)
            ContainerAction.FORCE_RESTART -> call("POST", "libpod/containers/$id/restart", mapOf("t" to "0"))
            ContainerAction.PAUSE -> call("POST", "libpod/containers/$id/pause")
            ContainerAction.RESUME -> call("POST", "libpod/containers/$id/unpause")
            ContainerAction.DELETE -> call("DELETE", "libpod/containers/$id", mapOf("force" to "true"))
        }
    }

    /** Live usage for every running container, every few seconds. */
    fun stats(): Flow<Map<String, ContainerStats>> = flow {
        val lines = ByteLineBuffer()
        transport.httpStream(socket, "${VERSION}libpod/containers/stats?stream=true&interval=3", superuser).collect { bytes ->
            lines.feed(bytes).forEach { line -> if (line.isNotBlank()) emit(PodmanParsers.stats(line)) }
        }
    }

    /** The last [tail] lines of a container's output, then new output as it's written. */
    fun logs(id: String, tail: Int = 200): Flow<String> {
        val decoder = PodmanLogDecoder()
        return transport.httpStream(socket, "${VERSION}libpod/containers/$id/logs?follow=true&stdout=true&stderr=true&tail=$tail", superuser)
            .mapNotNull { bytes -> decoder.feed(bytes).ifEmpty { null } }
    }

    companion object {
        const val VERSION = "/v3.4.0/"
        const val SYSTEM_SOCKET = "/run/podman/podman.sock"
    }
}

/** Finds and starts the Podman services cockpit-podman would use. */
object PodmanServices {
    /** Is Podman installed at all? (Decides whether the Containers tab shows.) */
    suspend fun installed(transport: CockpitTransport): Boolean =
        runCatching { transport.run(listOf("/bin/sh", "-c", "command -v podman"), timeoutMillis = 15_000).exitStatus == 0 }.getOrDefault(false)

    /**
     * Connects to the user's service and, with administrative access, the
     * system's: starts podman.socket like cockpit-podman, then checks the API
     * answers. Services that can't be reached are left out, with the reason.
     */
    suspend fun connect(transport: CockpitTransport, admin: Boolean): Pair<List<PodmanClient>, List<String>> {
        val clients = mutableListOf<PodmanClient>()
        val problems = mutableListOf<String>()
        val uid = runCatching { transport.runChecked(listOf("id", "-u")).trim().toInt() }.getOrNull()
        if (uid != 0) {
            try {
                val xrd = transport.run(listOf("printenv", "XDG_RUNTIME_DIR"), timeoutMillis = 15_000).output.trim()
                if (xrd.isEmpty()) throw CockpitException("there's no systemd user session for your account on the server")
                transport.run(listOf("systemctl", "--user", "start", "podman.socket"), timeoutMillis = 30_000)
                val client = PodmanClient(transport, PodmanOwner.USER, "$xrd/podman/podman.sock")
                client.version()
                clients += client
            } catch (e: CockpitException) {
                problems += "Your own (rootless) containers aren't available: ${e.message}"
            }
        }
        if (admin) {
            try {
                transport.run(listOf("systemctl", "start", "podman.socket"), Superuser.REQUIRE, timeoutMillis = 30_000)
                val client = PodmanClient(transport, PodmanOwner.SYSTEM, PodmanClient.SYSTEM_SOCKET)
                client.version()
                clients += client
            } catch (e: CockpitException) {
                problems += "System containers aren't available: ${e.message}"
            }
        }
        return clients to problems
    }
}
