package app.cockpitmanager.terminal

/**
 * A compact xterm-compatible screen model: enough of VT100/VT220 and xterm's
 * extensions (cursor addressing, scroll regions, insert/delete, 256 and
 * true-color SGR, the alternate screen) for shells and full-screen programs
 * such as top, less, nano and vim. Not thread-safe: feed and read it from one
 * thread (the UI's).
 */
class TerminalEmulator(rows: Int = 24, cols: Int = 80, private val scrollbackLimit: Int = 2000) {

    /** Colors: [DEFAULT], a palette index 0–255, or [rgb] for 24-bit color. */
    data class Style(
        val fg: Int = DEFAULT,
        val bg: Int = DEFAULT,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
        val strike: Boolean = false,
    ) {
        companion object {
            val PLAIN = Style()
        }
    }

    class Line(cols: Int) {
        var chars = CharArray(cols) { ' ' }
            private set
        var styles: Array<Style> = Array(cols) { Style.PLAIN }
            private set

        val width: Int get() = chars.size

        fun isBlank() = chars.all { it == ' ' } && styles.all { it.bg == DEFAULT && !it.inverse }

        fun clear(from: Int, to: Int, style: Style) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(width)) {
                chars[i] = ' '
                styles[i] = style.blank()
            }
        }

        fun resize(cols: Int) {
            if (cols == width) return
            val c = CharArray(cols) { if (it < width) chars[it] else ' ' }
            val s = Array(cols) { if (it < width) styles[it] else Style.PLAIN }
            chars = c
            styles = s
        }

        /** The text with trailing spaces removed. */
        fun text(): String = String(chars).trimEnd()
    }

    var rows = rows.coerceAtLeast(2)
        private set
    var cols = cols.coerceAtLeast(10)
        private set

    private val mainLines = ArrayList<Line>().apply { repeat(this@TerminalEmulator.rows) { add(Line(this@TerminalEmulator.cols)) } }
    private val altLines = ArrayList<Line>().apply { repeat(this@TerminalEmulator.rows) { add(Line(this@TerminalEmulator.cols)) } }
    private val scrollback = ArrayDeque<Line>()
    private var screen = mainLines

    val usingAlternateScreen: Boolean get() = screen === altLines

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var pendingWrap = false
    private var style = Style.PLAIN
    private var top = 0
    private var bottom = this.rows - 1
    private var saved = SavedCursor(0, 0, Style.PLAIN)

    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var lastPrinted = ' '

    /** The window title programs set (OSC 0/2), e.g. "user@host:~". */
    var title: String = ""
        private set

    /** Bumped on every change, so a UI can observe it. */
    var version = 0L
        private set

    /** Replies the terminal sends back to the program (cursor position reports, device attributes). */
    var onResponse: (String) -> Unit = {}

    private data class SavedCursor(val row: Int, val col: Int, val style: Style)

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, SKIP_ONE, STRING, STRING_ESC }

    private var state = State.GROUND
    private val params = StringBuilder()
    private var privateMarker: Char? = null
    private var intermediates = StringBuilder()
    private val osc = StringBuilder()

    // ---- Reading the screen ----

    /** Lines a scrolling view shows: scrollback then the screen (only the screen on the alternate screen). */
    val lineCount: Int get() = if (usingAlternateScreen) rows else scrollback.size + rows

    fun line(index: Int): Line =
        if (usingAlternateScreen) screen[index]
        else if (index < scrollback.size) scrollback[index]
        else screen[index - scrollback.size]

    /** [cursorRow] as an index for [line]. */
    val cursorLineIndex: Int get() = if (usingAlternateScreen) cursorRow else scrollback.size + cursorRow

    /** The visible screen as plain text, for tests and copying. */
    fun screenText(): String = screen.joinToString("\n") { it.text() }.trimEnd('\n')

    /** Everything, scrollback included, as plain text. */
    fun allText(): String = (0 until lineCount).joinToString("\n") { line(it).text() }.trimEnd('\n')

    // ---- Input from the program ----

    fun feed(text: String) {
        for (c in text) process(c)
        version++
    }

    private fun process(c: Char) {
        when (state) {
            State.GROUND -> ground(c)
            State.ESC -> escape(c)
            State.CSI -> csi(c)
            State.OSC -> when (c) {
                '\u0007' -> endOsc()
                '\u001b' -> state = State.OSC_ESC
                else -> if (osc.length < 4096) osc.append(c)
            }
            State.OSC_ESC -> if (c == '\\') endOsc() else { state = State.ESC; escape(c) }
            State.STRING -> if (c == '\u001b') state = State.STRING_ESC else if (c == '\u0007') state = State.GROUND
            State.STRING_ESC -> state = if (c == '\\') State.GROUND else State.STRING
            State.SKIP_ONE -> state = State.GROUND
        }
    }

    private fun ground(c: Char) {
        when {
            c == '\u001b' -> state = State.ESC
            c == '\r' -> { cursorCol = 0; pendingWrap = false }
            c == '\n' || c == '\u000b' || c == '\u000c' -> lineFeed()
            c == '\b' -> { if (cursorCol > 0) cursorCol--; pendingWrap = false }
            c == '\t' -> tab(1)
            c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f' -> Unit // BEL, SO/SI, DEL and C1 controls
            else -> print(c)
        }
    }

    private fun print(c: Char) {
        if (pendingWrap && autowrap) {
            cursorCol = 0
            lineFeed()
        }
        pendingWrap = false
        val line = screen[cursorRow]
        if (insertMode) insertBlanks(1)
        line.chars[cursorCol] = c
        line.styles[cursorCol] = style
        lastPrinted = c
        if (cursorCol >= cols - 1) {
            if (autowrap) pendingWrap = true
        } else {
            cursorCol++
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == bottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == top) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun tab(n: Int) {
        repeat(n) { cursorCol = ((cursorCol / 8) + 1) * 8 }
        cursorCol = cursorCol.coerceAtMost(cols - 1)
        pendingWrap = false
    }

    private fun scrollUp(n: Int) {
        repeat(n.coerceAtMost(bottom - top + 1)) {
            val removed = screen.removeAt(top)
            if (screen === mainLines && top == 0) {
                scrollback.addLast(removed)
                while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
            }
            screen.add(bottom, blankLine())
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n.coerceAtMost(bottom - top + 1)) {
            screen.removeAt(bottom)
            screen.add(top, blankLine())
        }
    }

    private fun blankLine() = Line(cols).also { l -> if (style.bg != DEFAULT) l.clear(0, cols, style) }

    private fun escape(c: Char) {
        state = State.GROUND
        when (c) {
            '[' -> {
                params.setLength(0)
                intermediates.setLength(0)
                privateMarker = null
                state = State.CSI
            }
            ']' -> { osc.setLength(0); state = State.OSC }
            'P', 'X', '^', '_' -> state = State.STRING // DCS, SOS, PM, APC: ignored up to ST
            '(', ')', '*', '+', '-', '.', '/', '#', '%' -> state = State.SKIP_ONE
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorCol = 0; lineFeed() }
            'M' -> reverseIndex()
            'c' -> reset()
            else -> Unit // '=', '>' keypad modes and anything unknown
        }
    }

    private fun csi(c: Char) {
        when {
            c in '0'..'9' || c == ';' || c == ':' -> params.append(if (c == ':') ';' else c)
            c in '<'..'?' && params.isEmpty() && privateMarker == null -> privateMarker = c
            c in ' '..'/' -> intermediates.append(c)
            c in '@'..'~' -> {
                state = State.GROUND
                executeCsi(c)
            }
            c == '\u001b' -> state = State.ESC
            c < ' ' -> ground(c) // C0 controls take effect inside sequences
            else -> state = State.GROUND
        }
    }

    private fun parsedParams(): List<Int> =
        if (params.isEmpty()) emptyList() else params.split(';').map { it.toIntOrNull()?.coerceAtMost(65535) ?: 0 }

    private fun executeCsi(final: Char) {
        val p = parsedParams()
        fun arg(i: Int, default: Int) = p.getOrNull(i)?.takeIf { it > 0 } ?: default
        if (intermediates.isNotEmpty()) return // cursor style, soft reset, etc.
        when (privateMarker) {
            '?' -> {
                when (final) {
                    'h' -> p.forEach { setPrivateMode(it, true) }
                    'l' -> p.forEach { setPrivateMode(it, false) }
                    'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
                    'K' -> eraseLine(p.getOrNull(0) ?: 0)
                }
                return
            }
            '>', '=' -> {
                if (final == 'c') onResponse("\u001b[>0;276;0c")
                return
            }
            '<' -> return
        }
        when (final) {
            '@' -> insertBlanks(arg(0, 1))
            'A' -> moveTo(cursorRow - arg(0, 1), cursorCol, clampToRegion = true)
            'B', 'e' -> moveTo(cursorRow + arg(0, 1), cursorCol, clampToRegion = true)
            'C', 'a' -> moveTo(cursorRow, cursorCol + arg(0, 1))
            'D' -> moveTo(cursorRow, cursorCol - arg(0, 1))
            'E' -> moveTo(cursorRow + arg(0, 1), 0, clampToRegion = true)
            'F' -> moveTo(cursorRow - arg(0, 1), 0, clampToRegion = true)
            'G', '`' -> moveTo(cursorRow, arg(0, 1) - 1)
            'H', 'f' -> {
                val row = arg(0, 1) - 1 + if (originMode) top else 0
                moveTo(row, arg(1, 1) - 1)
            }
            'I' -> tab(arg(0, 1))
            'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseLine(p.getOrNull(0) ?: 0)
            'L' -> if (cursorRow in top..bottom) {
                repeat(arg(0, 1).coerceAtMost(bottom - cursorRow + 1)) {
                    screen.removeAt(bottom)
                    screen.add(cursorRow, blankLine())
                }
                cursorCol = 0
            }
            'M' -> if (cursorRow in top..bottom) {
                repeat(arg(0, 1).coerceAtMost(bottom - cursorRow + 1)) {
                    screen.removeAt(cursorRow)
                    screen.add(bottom, blankLine())
                }
                cursorCol = 0
            }
            'P' -> deleteChars(arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'X' -> {
                screen[cursorRow].clear(cursorCol, cursorCol + arg(0, 1), style)
                pendingWrap = false
            }
            'Z' -> {
                repeat(arg(0, 1)) { cursorCol = if (cursorCol % 8 == 0) cursorCol - 8 else cursorCol - cursorCol % 8 }
                cursorCol = cursorCol.coerceAtLeast(0)
            }
            'b' -> repeat(arg(0, 1).coerceAtMost(cols * rows)) { print(lastPrinted) }
            'c' -> if ((p.getOrNull(0) ?: 0) == 0) onResponse("\u001b[?62;22c")
            'd' -> moveTo(arg(0, 1) - 1 + if (originMode) top else 0, cursorCol)
            'h' -> if (p.contains(4)) insertMode = true
            'l' -> if (p.contains(4)) insertMode = false
            'm' -> sgr(p)
            'n' -> when (p.getOrNull(0)) {
                5 -> onResponse("\u001b[0n")
                6 -> onResponse("\u001b[${cursorRow + 1 - if (originMode) top else 0};${cursorCol + 1}R")
            }
            'r' -> {
                val t = arg(0, 1) - 1
                val b = arg(1, rows) - 1
                if (t < b && b < rows) {
                    top = t
                    bottom = b
                } else {
                    top = 0
                    bottom = rows - 1
                }
                moveTo(if (originMode) top else 0, 0)
            }
            's' -> if (p.isEmpty()) saveCursor()
            'u' -> restoreCursor()
            else -> Unit // window ops ('t'), tab clear ('g') and others
        }
    }

    private fun moveTo(row: Int, col: Int, clampToRegion: Boolean = false) {
        val (minRow, maxRow) = when {
            clampToRegion && cursorRow in top..bottom -> top to bottom
            originMode -> top to bottom
            else -> 0 to rows - 1
        }
        cursorRow = row.coerceIn(minRow, maxRow)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun insertBlanks(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        for (i in cols - 1 downTo cursorCol + count) {
            line.chars[i] = line.chars[i - count]
            line.styles[i] = line.styles[i - count]
        }
        line.clear(cursorCol, cursorCol + count, style)
        pendingWrap = false
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        for (i in cursorCol until cols - count) {
            line.chars[i] = line.chars[i + count]
            line.styles[i] = line.styles[i + count]
        }
        line.clear(cols - count, cols, style)
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                screen[cursorRow].clear(cursorCol, cols, style)
                for (r in cursorRow + 1 until rows) screen[r].clear(0, cols, style)
            }
            1 -> {
                for (r in 0 until cursorRow) screen[r].clear(0, cols, style)
                screen[cursorRow].clear(0, cursorCol + 1, style)
            }
            2 -> screen.forEach { it.clear(0, cols, style) }
            3 -> if (!usingAlternateScreen) scrollback.clear()
        }
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        when (mode) {
            0 -> line.clear(cursorCol, cols, style)
            1 -> line.clear(0, cursorCol + 1, style)
            2 -> line.clear(0, cols, style)
        }
        pendingWrap = false
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            6 -> { originMode = on; moveTo(if (on) top else 0, 0) }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, saveRestore = false)
            1049 -> switchScreen(on, saveRestore = true)
            2004 -> bracketedPaste = on
        }
    }

    private fun switchScreen(alternate: Boolean, saveRestore: Boolean) {
        if (alternate == usingAlternateScreen) return
        if (alternate) {
            if (saveRestore) saveCursor()
            screen = altLines
            altLines.forEach { it.clear(0, cols, Style.PLAIN) }
        } else {
            screen = mainLines
            if (saveRestore) restoreCursor()
        }
        top = 0
        bottom = rows - 1
        pendingWrap = false
    }

    private fun saveCursor() {
        saved = SavedCursor(cursorRow, cursorCol, style)
    }

    private fun restoreCursor() {
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        style = saved.style
        pendingWrap = false
    }

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) {
            style = Style.PLAIN
            return
        }
        var i = 0
        while (i < p.size) {
            when (val n = p[i]) {
                0 -> style = Style.PLAIN
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4, 21 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                9 -> style = style.copy(strike = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                29 -> style = style.copy(strike = false)
                in 30..37 -> style = style.copy(fg = n - 30)
                39 -> style = style.copy(fg = DEFAULT)
                in 40..47 -> style = style.copy(bg = n - 40)
                49 -> style = style.copy(bg = DEFAULT)
                in 90..97 -> style = style.copy(fg = n - 90 + 8)
                in 100..107 -> style = style.copy(bg = n - 100 + 8)
                38, 48 -> {
                    val color = when (p.getOrNull(i + 1)) {
                        5 -> p.getOrNull(i + 2)?.coerceIn(0, 255).also { i += 2 }
                        2 -> {
                            val r = p.getOrNull(i + 2) ?: 0
                            val g = p.getOrNull(i + 3) ?: 0
                            val b = p.getOrNull(i + 4) ?: 0
                            i += 4
                            rgb(r, g, b)
                        }
                        else -> null
                    }
                    if (color != null) style = if (n == 38) style.copy(fg = color) else style.copy(bg = color)
                }
            }
            i++
        }
    }

    private fun endOsc() {
        state = State.GROUND
        val text = osc.toString()
        val semi = text.indexOf(';')
        if (semi > 0) {
            when (text.substring(0, semi)) {
                "0", "2" -> title = text.substring(semi + 1)
            }
        }
    }

    fun reset() {
        style = Style.PLAIN
        screen = mainLines
        mainLines.forEach { it.clear(0, cols, Style.PLAIN) }
        altLines.forEach { it.clear(0, cols, Style.PLAIN) }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        top = 0
        bottom = rows - 1
        cursorVisible = true
        applicationCursorKeys = false
        bracketedPaste = false
        autowrap = true
        originMode = false
        insertMode = false
        state = State.GROUND
        version++
    }

    // ---- Size ----

    /** Changes the size, keeping the cursor's line on screen. Returns whether anything changed. */
    fun resize(newRows: Int, newCols: Int): Boolean {
        val r = newRows.coerceAtLeast(2)
        val c = newCols.coerceAtLeast(10)
        if (r == rows && c == cols) return false
        for (lines in listOf(mainLines, altLines)) {
            val active = lines === screen
            lines.forEach { it.resize(c) }
            while (lines.size > r) {
                val cursorOnLast = active && cursorRow >= lines.size - 1
                if (!cursorOnLast && lines.last().isBlank()) {
                    lines.removeAt(lines.size - 1)
                } else {
                    val removed = lines.removeAt(0)
                    if (lines === mainLines) {
                        scrollback.addLast(removed)
                        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
                    }
                    if (active) cursorRow--
                }
            }
            while (lines.size < r) lines.add(Line(c))
        }
        rows = r
        cols = c
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        saved = saved.copy(row = saved.row.coerceIn(0, rows - 1), col = saved.col.coerceIn(0, cols - 1))
        top = 0
        bottom = rows - 1
        pendingWrap = false
        version++
        return true
    }

    // ---- Keys to the program ----

    enum class Key { UP, DOWN, RIGHT, LEFT, HOME, END, PAGE_UP, PAGE_DOWN, TAB, ESCAPE, ENTER, BACKSPACE, DELETE }

    fun encode(key: Key): String = when (key) {
        Key.UP -> if (applicationCursorKeys) "\u001bOA" else "\u001b[A"
        Key.DOWN -> if (applicationCursorKeys) "\u001bOB" else "\u001b[B"
        Key.RIGHT -> if (applicationCursorKeys) "\u001bOC" else "\u001b[C"
        Key.LEFT -> if (applicationCursorKeys) "\u001bOD" else "\u001b[D"
        Key.HOME -> if (applicationCursorKeys) "\u001bOH" else "\u001b[H"
        Key.END -> if (applicationCursorKeys) "\u001bOF" else "\u001b[F"
        Key.PAGE_UP -> "\u001b[5~"
        Key.PAGE_DOWN -> "\u001b[6~"
        Key.TAB -> "\t"
        Key.ESCAPE -> "\u001b"
        Key.ENTER -> "\r"
        Key.BACKSPACE -> "\u007f"
        Key.DELETE -> "\u001b[3~"
    }

    /** Pasted or typed text, wrapped for bracketed paste when the program asked for it. */
    fun encodePaste(text: String): String {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketedPaste) "\u001b[200~$normalized\u001b[201~" else normalized
    }

    companion object {
        const val DEFAULT = -1
        private const val RGB_FLAG = 0x1000000

        fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)
        fun isRgb(color: Int) = color != DEFAULT && color and RGB_FLAG != 0

        /** Ctrl+letter as the control character a terminal sends. */
        fun control(c: Char): String? {
            val u = c.uppercaseChar()
            return when {
                u in 'A'..'Z' -> (u.code - 64).toChar().toString()
                u == ' ' || u == '@' || u == '2' -> "\u0000"
                u == '[' || u == '3' -> "\u001b"
                u == '\\' || u == '4' -> "\u001c"
                u == ']' || u == '5' -> "\u001d"
                u == '^' || u == '6' -> "\u001e"
                u == '_' || u == '7' || u == '/' -> "\u001f"
                u == '?' || u == '8' -> "\u007f"
                else -> null
            }
        }

        private fun Style.blank(): Style = if (bg == DEFAULT && !inverse) Style.PLAIN else Style(bg = bg, inverse = inverse)
    }
}
