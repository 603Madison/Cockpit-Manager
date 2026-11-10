package app.cockpitmanager.cockpit

import app.cockpitmanager.system.ContainerAction
import app.cockpitmanager.system.MachinesClient
import app.cockpitmanager.system.PodmanOwner
import app.cockpitmanager.system.PodmanServices
import app.cockpitmanager.system.VmAction
import app.cockpitmanager.system.VmConnection
import app.cockpitmanager.system.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Containers and VMs clients against a real Cockpit with Podman and
 * libvirt-dbus, the same backends cockpit-podman and cockpit-machines use.
 * Needs COCKPIT_LIVE_URL/USER/PASSWORD (a user with sudo); each test skips
 * itself when its backend isn't installed. See CLAUDE.md for the sandbox setup.
 */
class LiveWorkloadsTest {
    private val address = System.getenv("COCKPIT_LIVE_URL")
    private val user = System.getenv("COCKPIT_LIVE_USER")
    private val password = System.getenv("COCKPIT_LIVE_PASSWORD")

    private fun withSession(block: suspend (CockpitTransport, CoroutineScope) -> Unit): Unit = runBlocking {
        assumeTrue("Set COCKPIT_LIVE_URL/USER/PASSWORD to run", address != null && user != null && password != null)
        val url = ServerUrl.parse(address!!)!!
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val success = assertIs<LoginStep.Success>(CockpitLogin(client, url).start(user!!, password!!, adminAccess = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = CockpitTransport(client, url, success.cookie, scope)
        try {
            transport.open()
            withTimeout(180_000) { block(transport, scope) }
        } finally {
            transport.close()
            scope.cancel()
        }
    }

    private suspend fun CockpitTransport.podman(vararg args: String) =
        run(listOf("podman") + args, Superuser.REQUIRE, timeoutMillis = 60_000)

    @Test
    fun systemContainers() = withSession { transport, _ ->
        assumeTrue("Podman isn't installed", PodmanServices.installed(transport))
        val image = transport.podman("images", "--format", "{{.Repository}}:{{.Tag}}").output.lineSequence().firstOrNull { it.isNotBlank() }
        assumeTrue("Needs a local image", image != null)
        transport.podman("rm", "-f", "cm-test-run", "cm-test-fail", "cm-test-unit", "cm-test-scratch")
        transport.podman("run", "-d", "--name", "cm-test-run", "-p", "18080:80", image!!, "sh", "-c", "i=0; while true; do echo \"tick \$i\"; i=\$((i+1)); sleep 1; done")
        transport.podman("run", "--name", "cm-test-fail", image, "sh", "-c", "echo bye; exit 3")
        transport.podman("create", "--name", "cm-test-unit", "--label", "PODMAN_SYSTEMD_UNIT=cm-test-unit.service", image, "sleep", "1000")
        try {
            val (clients, problems) = PodmanServices.connect(transport, admin = true)
            println("Podman problems (expected for the user service without systemd): $problems")
            val system = clients.firstOrNull { it.owner == PodmanOwner.SYSTEM }
            assertNotNull(system, "the system service is reachable with administrative access")
            assertNotNull(system.version())

            val list = system.containers().associateBy { it.name }
            val running = list.getValue("cm-test-run")
            assertTrue(running.isRunning)
            assertEquals(listOf("18080 → 80/tcp"), running.ports)
            assertNotNull(running.startedMillis)
            val failed = list.getValue("cm-test-fail")
            assertEquals(3, failed.exitCode)
            assertTrue(failed.isFailed)
            assertEquals("cm-test-unit.service", list.getValue("cm-test-unit").systemdUnit)

            val details = assertNotNull(system.details(running.id))
            assertTrue(details.command.contains("tick"), details.command)
            assertTrue(details.ports.contains("18080 → 80/tcp"), details.ports.toString())

            // Logs arrive multiplexed over a binary channel and decode to plain text.
            val logs = system.logs(running.id, tail = 5).first { it.contains("tick") }
            assertTrue(logs.lines().any { it.startsWith("tick ") }, logs)
            val stats = system.stats().first { running.id in it }
            assertNotNull(stats.getValue(running.id).memUsage)

            // Plain containers go through the API...
            system.act(running, ContainerAction.FORCE_STOP)
            assertEquals("exited", system.containers().first { it.id == running.id }.state)
            system.act(running, ContainerAction.START)
            assertTrue(system.containers().first { it.id == running.id }.isRunning)
            system.act(running, ContainerAction.PAUSE)
            assertTrue(system.containers().first { it.id == running.id }.isPaused)
            system.act(running, ContainerAction.RESUME)

            // ...Quadlet services through systemctl, which this sandbox doesn't have: the error proves the route.
            val unit = list.getValue("cm-test-unit")
            val e = assertFailsWith<CockpitException> { system.act(unit, ContainerAction.START) }
            println("Quadlet start went to systemctl: ${e.message}")
            assertEquals("created", system.containers().first { it.id == unit.id }.state)

            // API errors carry Podman's own message.
            val gone = assertFailsWith<HttpApiException> { system.details("no-such-container") }
            assertEquals(404, gone.status)

            transport.podman("create", "--name", "cm-test-scratch", image, "true")
            val scratch = system.containers().first { it.name == "cm-test-scratch" }
            system.act(scratch, ContainerAction.DELETE)
            assertTrue(system.containers().none { it.name == "cm-test-scratch" })
        } finally {
            transport.podman("rm", "-f", "cm-test-run", "cm-test-fail", "cm-test-unit", "cm-test-scratch")
        }
    }

    @Test
    fun virtualMachines() = withSession { transport, scope ->
        assumeTrue("libvirt-dbus isn't on the system bus", MachinesClient.available(transport, scope, "system"))
        val client = MachinesClient(transport, scope, VmConnection.SYSTEM)
        val vms = client.list()
        println("VMs: ${vms.map { "${it.name} ${it.state} ${it.description?.osName}" }}")
        assumeTrue("Needs at least one defined VM", vms.isNotEmpty())
        vms.forEach { vm ->
            assertTrue(vm.name.isNotBlank())
            assertTrue(vm.uuid.isNotBlank())
            assertNotNull(vm.description, "domain XML parses")
        }

        val vm = vms.firstOrNull { it.state == VmState.SHUT_OFF } ?: vms.first()
        if (vm.state == VmState.SHUT_OFF) {
            client.act(vm.path, VmAction.START)
            assertEquals(VmState.RUNNING, client.get(vm.path).state)
        }
        val first = client.stats(vm.path)
        delay(1500)
        val second = client.stats(vm.path)
        assertTrue((second.cpuTimeNanos ?: 0) >= (first.cpuTimeNanos ?: 0) && second.cpuTimeNanos != null, "CPU time is counted")

        client.act(vm.path, VmAction.PAUSE)
        assertEquals(VmState.PAUSED, client.get(vm.path).state)
        client.act(vm.path, VmAction.RESUME)
        assertEquals(VmState.RUNNING, client.get(vm.path).state)

        // Autostart is a property on the domain object, set through D-Bus Properties.
        val before = client.get(vm.path).autostart
        client.setAutostart(vm.path, !before)
        assertEquals(!before, client.get(vm.path).autostart)
        client.setAutostart(vm.path, before)
        assertEquals(before, client.get(vm.path).autostart)

        if (vm.state == VmState.SHUT_OFF) {
            client.act(vm.path, VmAction.FORCE_OFF)
            assertEquals(VmState.SHUT_OFF, client.get(vm.path).state)
        }
        client.close()
    }
}
