package app.cockpitmanager.system

import app.cockpitmanager.cockpit.ByteLineBuffer
import app.cockpitmanager.cockpit.DBusClient
import app.cockpitmanager.cockpit.apiErrorMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WorkloadParsersTest {
    @Test
    fun podman4ContainerList() {
        val list = PodmanParsers.containers(
            """[
              {"Command":["nginx","-g","daemon off;"],"Created":"2026-11-01T10:00:00.5Z","ExitCode":0,"ExitedAt":-62135596800,
               "Id":"aaaaaaaaaaaaaaaaaaaa","Image":"docker.io/library/nginx:latest","IsInfra":false,
               "Labels":{"PODMAN_SYSTEMD_UNIT":"web.service"},"Names":["web"],"Pod":"p1","PodName":"site",
               "Ports":[{"host_ip":"","container_port":80,"host_port":8080,"range":1,"protocol":"tcp"},
                        {"host_ip":"127.0.0.1","container_port":9000,"host_port":9000,"range":3,"protocol":"udp"}],
               "StartedAt":1762000000,"State":"running"},
              {"Command":null,"Created":"2026-11-01T09:00:00Z","ExitCode":3,"ExitedAt":1762001000,"Id":"bbbbbbbbbbbbbbbbbbbb",
               "Image":"localhost/tool","IsInfra":false,"Labels":{"PODMAN_SYSTEMD_UNIT":"podman-compose@proj.service"},
               "Names":["tool"],"Pod":"","PodName":"","Ports":null,"StartedAt":1761990000,"State":"exited"},
              {"Id":"cccccccccccccccccccc","Names":["site-infra"],"IsInfra":true,"State":"running","Pod":"p1","PodName":"site"}
            ]""",
            PodmanOwner.SYSTEM,
        ).associateBy { it.name }
        val web = list.getValue("web")
        assertTrue(web.isRunning)
        assertEquals("site", web.podName)
        assertEquals(listOf("8080 → 80/tcp", "127.0.0.1:9000-9002 → 9000-9002/udp"), web.ports)
        assertEquals("web.service", web.systemdUnit, "a Quadlet service")
        assertEquals(1762000000_000L, web.startedMillis)
        assertNull(web.exitedMillis, "Go's zero time means never")
        val tool = list.getValue("tool")
        assertTrue(tool.isFailed)
        assertNull(tool.systemdUnit, "podman-compose units aren't Quadlets (same rule as cockpit-podman)")
        assertTrue(list.getValue("site-infra").isInfra)
    }

    @Test
    fun podman3CamelCasePorts() {
        val c = PodmanParsers.containers(
            """[{"Id":"x","Names":["old"],"State":"running","Created":1600000000,"Ports":[{"hostPort":2222,"containerPort":22,"protocol":"tcp","hostIP":""}]}]""",
            PodmanOwner.USER,
        ).single()
        assertEquals(listOf("2222 → 22/tcp"), c.ports)
        assertEquals(1600000000_000L, c.createdMillis)
    }

    @Test
    fun statsAndDetails() {
        val stats = PodmanParsers.stats("""{"Error":null,"Stats":[{"ContainerID":"aaa","CPU":1.25,"MemUsage":1048576,"MemLimit":2097152,"NetInput":10,"NetOutput":20,"BlockInput":0,"BlockOutput":4096,"PIDs":3}]}""")
        assertEquals(1.25, stats.getValue("aaa").cpuPercent)
        assertEquals(3L, stats.getValue("aaa").pids)

        val d = PodmanParsers.details(
            """{"Id":"aaa","Created":"2026-11-01T10:00:00Z","ImageName":"docker.io/library/nginx:latest","RestartCount":2,
               "State":{"Status":"running","StartedAt":"2026-11-01T10:00:01Z","FinishedAt":"0001-01-01T00:00:00Z","ExitCode":0,"Error":"","Health":{"Status":"healthy"}},
               "Config":{"Entrypoint":["/docker-entrypoint.sh"],"Cmd":["nginx","-g","daemon off;"],"Env":["SECRET=hunter2"],"Tty":false,"Labels":{}},
               "HostConfig":{"RestartPolicy":{"Name":"always"}},
               "NetworkSettings":{"Ports":{"80/tcp":[{"HostIp":"","HostPort":"8080"}]},"Networks":{"podman":{"IPAddress":"10.88.0.5"}}},
               "Mounts":[{"Type":"bind","Source":"/srv/www","Destination":"/usr/share/nginx/html","RW":false}]}""",
        )!!
        assertEquals("/docker-entrypoint.sh nginx -g daemon off;", d.command)
        assertEquals(listOf("8080 → 80/tcp"), d.ports)
        assertEquals(listOf("podman" to "10.88.0.5"), d.networks)
        assertEquals(listOf("/srv/www → /usr/share/nginx/html (read-only)"), d.mounts)
        assertEquals("healthy", d.health)
        assertEquals("always", d.restartPolicy)
        assertNull(d.finishedMillis)
        assertFalse(d.toString().contains("hunter2"), "environment variables are never kept")
    }

    @Test
    fun multiplexedLogsAcrossReads() {
        fun frame(stream: Int, text: String): ByteArray {
            val body = text.toByteArray()
            return byteArrayOf(stream.toByte(), 0, 0, 0, 0, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        }
        val all = frame(1, "hello\n") + frame(2, "\u001b[31mwarn\u001b[0m\r\n") + frame(1, "é done\n")
        val d = PodmanLogDecoder()
        // Split mid-header and mid-body, as a network would.
        val out = d.feed(all.copyOfRange(0, 5)) + d.feed(all.copyOfRange(5, 17)) + d.feed(all.copyOfRange(17, all.size))
        assertEquals("hello\nwarn\né done\n", out)
    }

    @Test
    fun domainXml() {
        val d = DomainDescription.parse(
            """<domain type='kvm'>
                 <name>rhel9</name><title>Build box</title>
                 <metadata><libosinfo:libosinfo xmlns:libosinfo="http://libosinfo.org/xmlns/libvirt/domain/1.0">
                   <libosinfo:os id="http://redhat.com/rhel/9.4"/></libosinfo:libosinfo></metadata>
                 <memory unit='KiB'>4194304</memory><currentMemory unit='GiB'>2</currentMemory>
                 <vcpu placement='static' current='2'>4</vcpu>
                 <os><type arch='x86_64' machine='pc-q35-rhel9.4.0'>hvm</type></os>
                 <devices>
                   <disk type='file' device='disk'><source file='/var/lib/libvirt/images/rhel9.qcow2'/><target dev='vda' bus='virtio'/></disk>
                   <disk type='file' device='cdrom'><target dev='sda' bus='sata'/></disk>
                   <interface type='network'><mac address='52:54:00:aa:bb:cc'/><source network='default'/><model type='virtio'/></interface>
                   <graphics type='vnc' port='-1'/>
                 </devices>
               </domain>""",
        )!!
        assertEquals("rhel9", d.name)
        assertEquals("Build box", d.title)
        assertEquals("Red Hat Enterprise Linux 9.4", d.osName)
        assertEquals(2, d.vcpus)
        assertEquals(4L * 1024 * 1024 * 1024, d.memoryBytes)
        assertEquals(2L * 1024 * 1024 * 1024, d.currentMemoryBytes)
        assertEquals(listOf(VmDisk("vda", "disk", "/var/lib/libvirt/images/rhel9.qcow2"), VmDisk("sda", "cdrom", null)), d.disks)
        assertEquals(VmInterface("52:54:00:aa:bb:cc", "default", "virtio"), d.interfaces.single())
        assertEquals(listOf("VNC"), d.graphics)
        assertNull(DomainDescription.parse("not xml"))
    }

    @Test
    fun osNames() {
        assertEquals("Fedora 41", DomainDescription.prettyOs("http://fedoraproject.org/fedora/41"))
        assertEquals("Windows 11", DomainDescription.prettyOs("http://microsoft.com/win/11"))
        assertEquals("Ubuntu 24.04", DomainDescription.prettyOs("http://ubuntu.com/ubuntu/24.04"))
        assertEquals("CentOS Stream 9", DomainDescription.prettyOs("http://centos.org/centos-stream/9"))
    }

    @Test
    fun libvirtDbusQuirks() {
        // GetAll wraps each value twice ({"t":"v","v":{"t":"b","v":true}}).
        val doubled = Json.parseToJsonElement("""{"t":"v","v":{"t":"b","v":true}}""")
        assertEquals(JsonPrimitive(true), DBusClient.unwrapVariant(doubled))
        // GetState returns one (ii) struct.
        assertEquals(VmState.RUNNING, MachinesClient.stateOf(Json.parseToJsonElement("[[1,1]]") as JsonArray))
        assertEquals(VmState.SHUT_OFF, MachinesClient.stateOf(Json.parseToJsonElement("[[5,1]]") as JsonArray))
        assertTrue(VmState.PAUSED.isRunning)
        assertFalse(VmState.SHUT_OFF.isRunning)
    }

    @Test
    fun streamsAndErrors() {
        val b = ByteLineBuffer()
        val bytes = "{\"a\":\"é\"}\n{\"b\":1}\n{\"c\"".toByteArray()
        // Split inside the two-byte "é".
        val cut = bytes.indexOf(0xC3.toByte()) + 1
        assertEquals(emptyList(), b.feed(bytes.copyOfRange(0, cut)))
        assertEquals(listOf("{\"a\":\"é\"}", "{\"b\":1}"), b.feed(bytes.copyOfRange(cut, bytes.size)))
        assertEquals("no container with name or ID \"x\" found", apiErrorMessage("""{"cause":"no such container","message":"no container with name or ID \"x\" found","response":404}""", "404"))
        assertEquals("404 Not Found", apiErrorMessage("", "404 Not Found"))
    }
}
