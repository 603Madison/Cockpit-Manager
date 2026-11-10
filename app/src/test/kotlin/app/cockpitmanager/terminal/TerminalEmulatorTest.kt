package app.cockpitmanager.terminal

import app.cockpitmanager.terminal.TerminalEmulator.Companion.DEFAULT
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    private fun term(rows: Int = 5, cols: Int = 20) = TerminalEmulator(rows, cols)

    @Test
    fun printsAndHandlesCarriageReturnAndNewline() {
        val t = term()
        t.feed("hello\r\nworld\rW")
        assertEquals("hello\nWorld", t.screenText())
        assertEquals(1, t.cursorRow)
        assertEquals(1, t.cursorCol)
    }

    @Test
    fun wrapsAtTheRightMarginOnlyWhenTheNextCharacterArrives() {
        val t = term(cols = 10)
        t.feed("0123456789")
        assertEquals(0, t.cursorRow, "pending wrap: the cursor stays on the last column")
        t.feed("X")
        assertEquals("0123456789\nX", t.screenText())
    }

    @Test
    fun scrollsIntoScrollback() {
        val t = term(rows = 3)
        t.feed("a\r\nb\r\nc\r\nd")
        assertEquals("b\nc\nd", t.screenText())
        assertEquals("a\nb\nc\nd", t.allText())
    }

    @Test
    fun cursorAddressingAndErase() {
        val t = term()
        t.feed("xxxxx\r\nyyyyy")
        t.feed("\u001b[1;3H") // row 1, col 3
        t.feed("\u001b[K")
        assertEquals("xx\nyyyyy", t.screenText())
        t.feed("\u001b[2J")
        assertEquals("", t.screenText())
        t.feed("\u001b[3;2HZ")
        assertEquals("\n\n Z", t.screenText())
    }

    @Test
    fun insertAndDeleteCharacters() {
        val t = term()
        t.feed("abcdef\r\u001b[2C\u001b[2P")
        assertEquals("abef", t.screenText())
        t.feed("\u001b[1@!")
        assertEquals("ab!ef", t.screenText())
    }

    @Test
    fun sgrColors() {
        val t = term()
        t.feed("\u001b[1;31mR\u001b[0m \u001b[38;5;208mO\u001b[38;2;1;2;3mT\u001b[7mI")
        val line = t.line(t.cursorLineIndex)
        assertTrue(line.styles[0].bold)
        assertEquals(1, line.styles[0].fg)
        assertEquals(TerminalEmulator.Style.PLAIN, line.styles[1])
        assertEquals(208, line.styles[2].fg)
        assertEquals(TerminalEmulator.rgb(1, 2, 3), line.styles[3].fg)
        assertTrue(line.styles[4].inverse)
        assertEquals(DEFAULT, line.styles[4].bg)
    }

    @Test
    fun alternateScreenRestoresTheShell() {
        val t = term()
        t.feed("prompt$ ")
        t.feed("\u001b[?1049h\u001b[H\u001b[2Jfull screen app")
        assertTrue(t.usingAlternateScreen)
        assertEquals("full screen app", t.screenText())
        t.feed("\u001b[?1049l")
        assertFalse(t.usingAlternateScreen)
        assertEquals("prompt$", t.screenText())
        assertEquals(8, t.cursorCol)
    }

    @Test
    fun scrollRegionKeepsHeaderAndFooter() {
        val t = term(rows = 5)
        t.feed("head\r\n1\r\n2\r\n3\r\nfoot")
        t.feed("\u001b[2;4r") // rows 2–4
        t.feed("\u001b[4;1H\n") // line feed at the bottom of the region
        assertEquals("head\n2\n3\n\nfoot", t.screenText())
    }

    @Test
    fun answersCursorPositionReports() {
        val t = term()
        var reply = ""
        t.onResponse = { reply = it }
        t.feed("ab\u001b[6n")
        assertEquals("\u001b[1;3R", reply)
    }

    @Test
    fun oscSetsTitleAndIsNotPrinted() {
        val t = term()
        t.feed("\u001b]0;tester@box:~\u0007$ ")
        assertEquals("tester@box:~", t.title)
        assertEquals("$", t.screenText())
    }

    @Test
    fun resizeKeepsTheCursorLineVisible() {
        val t = term(rows = 6, cols = 20)
        t.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        t.resize(3, 20)
        assertEquals("4\n5\n6", t.screenText())
        assertEquals(2, t.cursorRow)
        t.resize(6, 30)
        assertEquals(30, t.cols)
        assertEquals(6, t.rows)
    }

    @Test
    fun keysFollowCursorKeyMode() {
        val t = term()
        assertEquals("\u001b[A", t.encode(TerminalEmulator.Key.UP))
        t.feed("\u001b[?1h")
        assertEquals("\u001bOA", t.encode(TerminalEmulator.Key.UP))
        assertEquals("\u0003", TerminalEmulator.control('c'))
        t.feed("\u001b[?2004h")
        assertEquals("\u001b[200~a\rb\u001b[201~", t.encodePaste("a\nb"))
    }
}
