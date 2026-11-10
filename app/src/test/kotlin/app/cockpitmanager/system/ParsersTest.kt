package app.cockpitmanager.system

import app.cockpitmanager.cockpit.LineBuffer
import app.cockpitmanager.cockpit.shellQuote
import app.cockpitmanager.ui.Format
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun systemFacts() {
        val f = SystemFacts.parse(
            """
            hostname=box.example.com
            os=Red Hat Enterprise Linux 10.0 (Coughlan)
            kernel=6.12.0-55.el10.x86_64
            arch=x86_64
            nproc=8
            cpu=Intel(R) Core(TM) i7-8650U   CPU @ 1.90GHz
            vendor=LENOVO
            model=20L8S02D00
            MemTotal=16303452
            SwapTotal=8388604
            SwapFree=8388604
            uptime=93784.21
            load=0.42 0.30 0.25
            """.trimIndent(),
        )
        assertEquals("box.example.com", f.hostname)
        assertEquals("Red Hat Enterprise Linux 10.0 (Coughlan)", f.os)
        assertEquals(8, f.cpuCount)
        assertEquals("Intel(R) Core(TM) i7-8650U CPU @ 1.90GHz", f.cpuModel)
        assertEquals(16303452L * 1024, f.memTotalBytes)
        assertEquals(93784, f.uptimeSeconds)
        assertEquals(listOf(0.42, 0.30, 0.25), f.load)
        assertEquals("LENOVO 20L8S02D00", f.hardware)
    }

    @Test
    fun mergesUnitsWithUnitFiles() {
        val units = Json.parseToJsonElement(
            """[
              ["sshd.service","OpenSSH server daemon","loaded","active","running","","/o/sshd","0","",""],
              ["httpd.service","The Apache HTTP Server","loaded","failed","failed","","/o/httpd","0","",""],
              ["getty@tty1.service","Getty on tty1","loaded","active","running","","/o/getty","0","",""],
              ["ghost.service","ghost","not-found","inactive","dead","","/o/ghost","0","",""],
              ["tmp.mount","Temporary Directory","loaded","active","mounted","","/o/tmp","0","",""]
            ]""",
        ) as JsonArray
        val files = Json.parseToJsonElement(
            """[
              ["/usr/lib/systemd/system/sshd.service","enabled"],
              ["/usr/lib/systemd/system/httpd.service","disabled"],
              ["/usr/lib/systemd/system/getty@.service","enabled"],
              ["/usr/lib/systemd/system/cups.service","disabled"],
              ["/usr/lib/systemd/system/foo@.service","static"],
              ["/etc/systemd/system/bluetooth.service","masked"]
            ]""",
        ) as JsonArray
        val merged = ServicesRepository.merge(units, files).associateBy { it.name }
        assertEquals(setOf("sshd.service", "httpd.service", "getty@tty1.service", "cups.service", "bluetooth.service"), merged.keys)
        assertTrue(merged.getValue("sshd.service").isEnabled)
        assertTrue(merged.getValue("httpd.service").isFailed)
        assertEquals("enabled", merged.getValue("getty@tty1.service").fileState)
        assertEquals("inactive", merged.getValue("cups.service").activeState)
        assertTrue(merged.getValue("bluetooth.service").isMasked)
        assertFalse(merged.getValue("bluetooth.service").canToggleBoot)
        assertEquals("active (running)", merged.getValue("sshd.service").stateLabel)
    }

    @Test
    fun serviceDetails() {
        val d = ServiceDetails.parse("MainPID=1234\nMemoryCurrent=18446744073709551615\nCPUUsageNSec=1500000000\nFragmentPath=/usr/lib/systemd/system/sshd.service\nUser=\n")
        assertEquals(1234, d.mainPid)
        assertNull(d.memoryBytes, "UINT64_MAX means not tracked")
        assertEquals(1_500_000_000L, d.cpuNanos)
        assertNull(d["User"])
    }

    @Test
    fun journalEntries() {
        val e = JournalEntry.parse(
            """{"__CURSOR":"s=1","__REALTIME_TIMESTAMP":"1762800000123456","PRIORITY":"3","SYSLOG_IDENTIFIER":"sshd","_SYSTEMD_UNIT":"sshd.service","_PID":"77","MESSAGE":"Failed password for tester"}""",
        )!!
        assertEquals(1762800000123L, e.timestampMillis)
        assertEquals(3, e.priority)
        assertEquals("sshd", e.identifier)
        assertEquals("sshd.service", e.unit)
        // Binary messages arrive as byte arrays.
        val bin = JournalEntry.parse("""{"__CURSOR":"s=2","MESSAGE":[104,105,195,169]}""")!!
        assertEquals("hié", bin.message)
        assertNull(JournalEntry.parse("not json"))
    }

    @Test
    fun journalArguments() {
        val q = JournalQuery(level = LogLevel.WARNINGS, unit = "sshd.service", lines = 50, currentBootOnly = true)
        assertEquals(
            listOf("journalctl", "--no-pager", "--quiet", "--all", "--output=json", "--reverse", "--lines=50", "--priority=0..4", "--unit=sshd.service", "--boot"),
            q.args(follow = false),
        )
        assertTrue("--follow" in JournalQuery(level = LogLevel.ALL).args(follow = true))
        assertFalse(JournalQuery(level = LogLevel.ALL).args(follow = false).any { it.startsWith("--priority") })
    }

    @Test
    fun lineBufferJoinsChunks() {
        val b = LineBuffer()
        assertEquals(emptyList(), b.feed("ab"))
        assertEquals(listOf("abc", "d"), b.feed("c\nd\ne"))
        assertEquals(listOf("e"), b.feed("\n"))
    }

    @Test
    fun formatting() {
        assertEquals("1.50 KiB", Format.bytes(1536.0))
        assertEquals("15.5 GiB", Format.bytes(16_694_734_848.0))
        assertEquals("512 B", Format.bytes(512L))
        assertEquals("1 day, 2 hours", Format.duration(93_784))
        assertEquals("3 hours, 5 min", Format.duration(3 * 3600 + 5 * 60))
        assertEquals("11 hours ago", Format.ago(0, now = 11 * 3600 * 1000L))
        assertEquals("1 day ago", Format.ago(0, now = 86_400 * 1000L))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }
}
