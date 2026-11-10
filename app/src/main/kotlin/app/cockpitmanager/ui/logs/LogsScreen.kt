@file:OptIn(ExperimentalMaterial3Api::class)

package app.cockpitmanager.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.JournalEntry
import app.cockpitmanager.system.LogLevel
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.EmptyState
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.services.SearchField
import app.cockpitmanager.ui.services.groupShape
import app.cockpitmanager.ui.theme.AppTheme
import app.cockpitmanager.ui.theme.StatusColors

fun priorityColor(priority: Int, status: StatusColors): Color = when {
    priority <= 3 -> status.bad
    priority == 4 -> status.warn
    priority == 5 -> status.info
    else -> status.idle
}

fun priorityName(priority: Int) = when (priority) {
    0 -> "Emergency"
    1 -> "Alert"
    2 -> "Critical"
    3 -> "Error"
    4 -> "Warning"
    5 -> "Notice"
    6 -> "Info"
    else -> "Debug"
}

private sealed interface LogItem {
    data class Header(val day: String) : LogItem
    data class Entry(val entry: JournalEntry, val index: Int, val count: Int) : LogItem
}

private fun group(entries: List<JournalEntry>): List<LogItem> {
    val out = ArrayList<LogItem>()
    entries.groupBy { Format.day(it.timestampMillis) }.forEach { (day, list) ->
        out += LogItem.Header(day)
        list.forEachIndexed { i, e -> out += LogItem.Entry(e, i, list.size) }
    }
    return out
}

@Composable
fun LogsScreen(
    state: LogsState,
    onLevel: (LogLevel) -> Unit,
    onSearch: (String) -> Unit,
    onLive: (Boolean) -> Unit,
    onBootOnly: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(state.entries, state.search) { group(state.visible) }
    Column(modifier.fillMaxSize()) {
        SearchField(state.search, onSearch, "Search loaded entries", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(selected = state.query.level == level, onClick = { onLevel(level) }, label = { Text(level.label) })
            }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
            FilterChip(
                selected = state.live,
                onClick = { onLive(!state.live) },
                label = { Text("Live") },
                leadingIcon = { Icon(CockpitIcons.Live, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
            FilterChip(selected = state.query.currentBootOnly, onClick = { onBootOnly(!state.query.currentBootOnly) }, label = { Text("This boot") })
        }
        PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.error?.let { msg -> item { ErrorCard(msg, actionLabel = "Retry", onAction = onRefresh, modifier = Modifier.padding(vertical = 6.dp)) } }
                if (state.loading && state.entries.isEmpty()) {
                    item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                } else if (items.isEmpty() && state.error == null) {
                    item {
                        EmptyState(
                            CockpitIcons.Logs,
                            if (state.search.isNotBlank()) "No matches" else "No entries",
                            if (state.search.isNotBlank()) "Nothing loaded matches \"${state.search}\"." else "Nothing at this level. Try a lower one, such as Notices or All.",
                        )
                    }
                }
                items(items, key = { item ->
                    when (item) {
                        is LogItem.Header -> "h:${item.day}"
                        is LogItem.Entry -> "e:${item.entry.cursor.ifEmpty { "${item.entry.timestampMicros}:${item.index}" }}"
                    }
                }) { item ->
                    when (item) {
                        is LogItem.Header -> Text(
                            item.day,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                        )
                        is LogItem.Entry -> Box(
                            Modifier.fillMaxWidth().clip(groupShape(item.index, item.count)).background(MaterialTheme.colorScheme.surfaceContainer),
                        ) { LogLine(item.entry, showSource = true) }
                    }
                }
                if (state.entries.isNotEmpty() && state.entries.size >= state.query.lines) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = onLoadMore, enabled = !state.loading) { Text("Load older entries") }
                        }
                    }
                }
            }
        }
    }
}

/** One journal entry: time, source and message, with a priority stripe. Tap to see all of it. */
@Composable
fun LogLine(entry: JournalEntry, showSource: Boolean, monospace: Boolean = false) {
    var expanded by rememberSaveable(entry.cursor) { mutableStateOf(false) }
    val color = priorityColor(entry.priority, AppTheme.status)
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(Modifier.width(4.dp).height(if (expanded) 48.dp else 36.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.clock(entry.timestampMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showSource && entry.identifier.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(entry.identifier, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.priority <= 4) {
                    Spacer(Modifier.width(8.dp))
                    Text(priorityName(entry.priority), style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
            Spacer(Modifier.height(2.dp))
            val style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium
            if (expanded) {
                SelectionContainer { Text(entry.message, style = style) }
                val meta = listOfNotNull(entry.unit?.let { "Unit $it" }, entry.pid?.let { "PID $it" }, priorityName(entry.priority)).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(entry.message, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
