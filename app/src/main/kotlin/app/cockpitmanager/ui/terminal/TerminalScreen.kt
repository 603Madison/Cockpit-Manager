package app.cockpitmanager.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cockpitmanager.terminal.TerminalEmulator
import app.cockpitmanager.terminal.TerminalEmulator.Companion.DEFAULT
import app.cockpitmanager.terminal.TerminalSession

/** Terminal colors stay dark in both themes, like most terminals. */
object TerminalColors {
    val background = Color(0xFF0A111C)
    val foreground = Color(0xFFE5E9F0)
    val cursor = Color(0xFF5EDBC8)

    private val base16 = listOf(
        0xFF1E2633, 0xFFF87171, 0xFF4ADE80, 0xFFFACC15, 0xFF60A5FA, 0xFFC084FC, 0xFF2DD4BF, 0xFFD1D5DB,
        0xFF64748B, 0xFFFCA5A5, 0xFF86EFAC, 0xFFFDE68A, 0xFF93C5FD, 0xFFD8B4FE, 0xFF5EEAD4, 0xFFF8FAFC,
    ).map { Color(it) }

    /** xterm's 256-color palette: 16 named colors, a 6×6×6 cube, then 24 grays. */
    fun palette(index: Int): Color = when {
        index < 16 -> base16[index.coerceAtLeast(0)]
        index < 232 -> {
            val i = index - 16
            fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
            Color(level(i / 36), level((i / 6) % 6), level(i % 6))
        }
        else -> (8 + (index - 232) * 10).let { Color(it, it, it) }
    }

    fun color(value: Int, fallback: Color): Color = when {
        value == DEFAULT -> fallback
        TerminalEmulator.isRgb(value) -> Color((value shr 16) and 0xff, (value shr 8) and 0xff, value and 0xff)
        else -> palette(value)
    }
}

private fun spanFor(style: TerminalEmulator.Style, cursor: Boolean): SpanStyle {
    var fg = TerminalColors.color(style.fg, TerminalColors.foreground)
    var bg = TerminalColors.color(style.bg, Color.Transparent)
    if (style.inverse) {
        val f = fg
        fg = if (bg == Color.Transparent) TerminalColors.background else bg
        bg = f
    }
    if (cursor) {
        fg = TerminalColors.background
        bg = TerminalColors.cursor
    }
    if (style.dim) fg = fg.copy(alpha = 0.65f)
    return SpanStyle(
        color = fg,
        background = bg,
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = when {
            style.underline && style.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            style.underline -> TextDecoration.Underline
            style.strike -> TextDecoration.LineThrough
            else -> null
        },
    )
}

/** One screen line as styled text, merging runs of the same style. */
fun renderLine(line: TerminalEmulator.Line, cursorCol: Int?): AnnotatedString = buildAnnotatedString {
    val text = String(line.chars)
    // Trailing blank cells with no background add nothing but width.
    var end = line.width
    while (end > 0 && line.chars[end - 1] == ' ' && line.styles[end - 1] == TerminalEmulator.Style.PLAIN && (cursorCol == null || end - 1 > cursorCol)) end--
    if (cursorCol != null && cursorCol >= end) end = (cursorCol + 1).coerceAtMost(line.width)
    var start = 0
    while (start < end) {
        val style = line.styles[start]
        val isCursor = cursorCol == start
        var stop = start + 1
        if (!isCursor) {
            while (stop < end && line.styles[stop] == style && stop != cursorCol) stop++
        }
        val span = spanFor(style, isCursor)
        pushStyle(span)
        append(text, start, stop)
        pop()
        start = stop
    }
}

@Composable
fun TerminalScreen(session: TerminalSession, fontSizeSp: Float, modifier: Modifier = Modifier) {
    val version by session.version.collectAsState()
    val state by session.state.collectAsState()
    val emulator = session.emulator
    val listState = rememberLazyListState()
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }

    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.25f).sp, color = TerminalColors.foreground)
    val measurer = rememberTextMeasurer()
    val cell = remember(fontSizeSp) { measurer.measure("MMMMMMMMMM", textStyle).size }
    val density = LocalDensity.current

    fun send(s: String) = session.send(s)
    fun flushThen(extra: String) {
        if (text.isNotEmpty()) send(emulator.encodePaste(text))
        text = ""
        send(extra)
    }

    // Follow new output unless the user has scrolled back.
    LaunchedEffect(version) {
        val total = emulator.lineCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (total > 0 && (lastVisible >= total - emulator.rows - 2 || !listState.canScrollForward)) {
            listState.scrollToItem(total - 1)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                emulator.title.ifBlank { "Shell" }.also { version },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { session.start() }) { Icon(Icons.Filled.Refresh, contentDescription = "New session") }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TerminalColors.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    runCatching { input.requestFocus() }
                    keyboard?.show()
                },
        ) {
            val padding = with(density) { 10.dp.toPx() }
            val widthPx = constraints.maxWidth - 2 * padding
            val heightPx = constraints.maxHeight - 2 * padding
            val cols = (widthPx / (cell.width / 10f)).toInt().coerceAtLeast(20)
            val lineHeightPx = with(density) { textStyle.lineHeight.toPx() }
            val rows = (heightPx / lineHeightPx).toInt().coerceAtLeast(4)
            LaunchedEffect(rows, cols, state is TerminalSession.State.Idle) {
                if (state is TerminalSession.State.Idle) session.start(rows, cols) else session.resize(rows, cols)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                items(count = emulator.lineCount) { index ->
                    val cursor = if (index == emulator.cursorLineIndex && emulator.cursorVisible && state is TerminalSession.State.Running) emulator.cursorCol else null
                    val line = remember(version, index, emulator.lineCount) { renderLine(emulator.line(index), cursor) }
                    Text(line, style = textStyle, softWrap = false, maxLines = 1)
                }
            }
            (state as? TerminalSession.State.Ended)?.let { ended ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ended.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { session.start(rows, cols) }) { Text("New session") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        KeyRow(
            ctrl = ctrl,
            onCtrl = { ctrl = !ctrl },
            onKey = { key -> flushThen(emulator.encode(key)) },
            onText = { s -> flushThen(s) },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    if (ctrl && new.length == text.length + 1) {
                        TerminalEmulator.control(new.last())?.let { send(it) }
                        ctrl = false
                    } else {
                        text = new
                    }
                },
                placeholder = { Text(if (ctrl) "Ctrl + key…" else "Type a command") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onSend = { flushThen("\r") }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(input).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Hardware keyboards: Ctrl combinations and, with nothing typed, the editing keys go straight through.
                    if (event.isCtrlPressed) {
                        val c = event.utf16CodePoint.takeIf { it > 0 }?.toChar()
                        val code = c?.let { TerminalEmulator.control(if (it.code in 1..26) (it.code + 64).toChar() else it) }
                        if (code != null) {
                            flushThen(code)
                            return@onPreviewKeyEvent true
                        }
                    }
                    val key = when (event.key) {
                        Key.DirectionUp -> TerminalEmulator.Key.UP
                        Key.DirectionDown -> TerminalEmulator.Key.DOWN
                        Key.Tab -> TerminalEmulator.Key.TAB
                        Key.Escape -> TerminalEmulator.Key.ESCAPE
                        Key.Enter, Key.NumPadEnter -> if (text.isEmpty()) TerminalEmulator.Key.ENTER else null
                        Key.Backspace -> if (text.isEmpty()) TerminalEmulator.Key.BACKSPACE else null
                        Key.DirectionLeft -> if (text.isEmpty()) TerminalEmulator.Key.LEFT else null
                        Key.DirectionRight -> if (text.isEmpty()) TerminalEmulator.Key.RIGHT else null
                        else -> null
                    }
                    if (key != null) {
                        flushThen(emulator.encode(key))
                        true
                    } else {
                        false
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { flushThen("\r") }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun KeyRow(ctrl: Boolean, onCtrl: () -> Unit, onKey: (TerminalEmulator.Key) -> Unit, onText: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyChip("Esc") { onKey(TerminalEmulator.Key.ESCAPE) }
        KeyChip("Tab") { onKey(TerminalEmulator.Key.TAB) }
        KeyChip("Ctrl", active = ctrl, onClick = onCtrl)
        KeyChip("^C") { onText("\u0003") }
        KeyChip("←") { onKey(TerminalEmulator.Key.LEFT) }
        KeyChip("↑") { onKey(TerminalEmulator.Key.UP) }
        KeyChip("↓") { onKey(TerminalEmulator.Key.DOWN) }
        KeyChip("→") { onKey(TerminalEmulator.Key.RIGHT) }
        KeyChip("Home") { onKey(TerminalEmulator.Key.HOME) }
        KeyChip("End") { onKey(TerminalEmulator.Key.END) }
        KeyChip("PgUp") { onKey(TerminalEmulator.Key.PAGE_UP) }
        KeyChip("PgDn") { onKey(TerminalEmulator.Key.PAGE_DOWN) }
        listOf("|", "/", "-", "~", "*", "$").forEach { s -> KeyChip(s) { onText(s) } }
        KeyChip("⌫") { onKey(TerminalEmulator.Key.BACKSPACE) }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.height(36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            // Arrows and symbols are small in Lexend's label size.
            Text(label, style = if (label.length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        }
    }
}
