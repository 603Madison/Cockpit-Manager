package app.cockpitmanager.cockpit

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun bareHostUsesHttpsOnCockpitPort() {
        val url = ServerUrl.parse("server.local")!!
        assertTrue(url.https)
        assertEquals(9090, url.port)
        assertEquals("https://server.local:9090/cockpit/login", url.loginUrl.toString())
        assertEquals("https://server.local:9090/cockpit/socket", url.socketUrl.toString())
        assertEquals("https://server.local:9090", url.origin)
    }

    @Test
    fun explicitSchemeKeepsStandardPort() {
        val url = ServerUrl.parse("https://cockpit.example.com")!!
        assertEquals(443, url.port)
        assertEquals("https://cockpit.example.com", url.origin)
        assertEquals("cockpit.example.com", url.hostHeader)
    }

    @Test
    fun explicitPortWins() {
        assertEquals(8443, ServerUrl.parse("myhost:8443")!!.port)
        assertEquals(9090, ServerUrl.parse("http://192.168.1.20:9090")!!.port)
        assertFalse(ServerUrl.parse("http://192.168.1.20:9090")!!.https)
    }

    @Test
    fun ipv6() {
        val url = ServerUrl.parse("[fd00::5]")!!
        assertEquals("[fd00::5]:9090", url.hostHeader)
        assertEquals("https://[fd00::5]:9090", url.origin)
        val explicit = ServerUrl.parse("https://[fd00::5]:9443")!!
        assertEquals(9443, explicit.port)
    }

    @Test
    fun urlRootAndPastedPagesAreHandled() {
        assertEquals("/admin", ServerUrl.parse("https://example.com/admin/")!!.root)
        assertEquals("https://example.com/admin/cockpit/login", ServerUrl.parse("https://example.com/admin")!!.loginUrl.toString())
        // Pasting a page out of the browser still finds the server.
        assertEquals("", ServerUrl.parse("https://host:9090/system/services#/sshd.service")!!.root)
        assertEquals("", ServerUrl.parse("https://host:9090/cockpit/@localhost/system/index.html")!!.root)
        assertEquals("/admin", ServerUrl.parse("https://host/admin/system")!!.root)
        assertEquals("", ServerUrl.parse("https://host:9090/@localhost/system")!!.root)
    }

    @Test
    fun invalidAddresses() {
        assertNull(ServerUrl.parse(""))
        assertNull(ServerUrl.parse("ftp://host"))
        assertNull(ServerUrl.parse("two words"))
    }

    @Test
    fun cleartextOnlyForPrivateNetworks() {
        listOf("http://192.168.1.2", "http://10.0.0.5", "http://172.20.1.1", "http://127.0.0.1", "http://nas", "http://server.local",
            "http://box.lan", "http://100.101.102.103", "http://myhost.tail1234.ts.net", "http://[fd12::1]", "http://[::1]")
            .forEach { assertTrue(ServerUrl.parse(it)!!.cleartextAllowed, it) }
        listOf("http://example.com", "http://8.8.8.8", "http://172.32.0.1", "http://100.128.0.1")
            .forEach { assertFalse(ServerUrl.parse(it)!!.cleartextAllowed, it) }
        assertTrue(ServerUrl.parse("https://example.com")!!.cleartextAllowed)
    }
}
