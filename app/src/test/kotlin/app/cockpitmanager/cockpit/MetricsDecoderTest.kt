package app.cockpitmanager.cockpit

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MetricsDecoderTest {
    @Test
    fun decodesScalarAndInstancedMetrics() {
        val d = MetricsDecoder()
        assertTrue(
            d.feed(
                """{"source":"internal","interval":2000,"timestamp":1,"metrics":[
                {"name":"cpu.basic.user","derive":"rate","units":"millisec"},
                {"name":"mount.used","units":"bytes","instances":["/","/boot"]}]}""",
            ).isEmpty(),
        )
        val samples = d.feed("""[[false,[100,20]],[250.5,[110,20]]]""")
        assertEquals(2, samples.size)
        assertNull(samples[0].scalar(0), "false means no value yet")
        assertEquals(mapOf("/" to 100.0, "/boot" to 20.0), samples[0].instances(1))
        assertEquals(250.5, samples[1].scalar(0))
        assertEquals(110.0, samples[1].instances(1)["/"])
    }

    @Test
    fun compressedSamplesRepeatThePreviousValue() {
        val d = MetricsDecoder()
        d.feed("""{"metrics":[{"name":"a"},{"name":"b"},{"name":"c","instances":["x","y"]}]}""")
        d.feed("""[[1,2,[3,4]]]""")
        // null = unchanged; a short array = the rest unchanged.
        val s = d.feed("""[[null,5,[null,6]],[7]]""")
        assertEquals(1.0, s[0].scalar(0))
        assertEquals(5.0, s[0].scalar(1))
        assertEquals(mapOf("x" to 3.0, "y" to 6.0), s[0].instances(2))
        assertEquals(7.0, s[1].scalar(0))
        assertEquals(5.0, s[1].scalar(1))
        assertEquals(mapOf("x" to 3.0, "y" to 6.0), s[1].instances(2))
    }

    @Test
    fun newMetaResetsInstances() {
        val d = MetricsDecoder()
        d.feed("""{"metrics":[{"name":"rx","instances":["eth0"]}]}""")
        d.feed("""[[[5]]]""")
        d.feed("""{"metrics":[{"name":"rx","instances":["eth0","wlan0"]}]}""")
        val s = d.feed("""[[[6,7]]]""")
        assertEquals(mapOf("eth0" to 6.0, "wlan0" to 7.0), s[0].instances(0))
    }
}
