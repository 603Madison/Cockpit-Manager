@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.cockpitmanager.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.ContainerAction
import app.cockpitmanager.system.ContainerStats
import app.cockpitmanager.system.PodmanContainer
import app.cockpitmanager.system.PodmanOwner
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.DetailRow
import app.cockpitmanager.ui.components.EmptyState
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.InfoCard
import app.cockpitmanager.ui.components.PageScaffold
import app.cockpitmanager.ui.components.StatusDot
import app.cockpitmanager.ui.components.UsageBar
import app.cockpitmanager.ui.servers.ConfirmDialog
import app.cockpitmanager.ui.services.SearchField
import app.cockpitmanager.ui.services.groupShape
import app.cockpitmanager.ui.theme.AppTheme
import app.cockpitmanager.ui.theme.StatusColors
import kotlin.math.roundToInt

fun PodmanContainer.statusColor(status: StatusColors): Color = when {
    isRunning -> status.good
    isPaused -> status.warn
    isFailed -> status.bad
    else -> status.idle
}

/** "exited (3)", "running", "paused"... */
fun PodmanContainer.stateLabel(): String = when {
    state == "exited" && exitCode != 0 -> "exited ($exitCode)"
    else -> state.ifBlank { "unknown" }
}

/** "nginx:latest" rather than "docker.io/library/nginx:latest". */
fun shortImage(image: String): String = image.removePrefix("docker.io/library/").removePrefix("localhost/")

/** "up 2 hours", "exited 3 days ago", "created 5 min ago". */
fun PodmanContainer.whenLabel(now: Long = System.currentTimeMillis()): String? = when {
    isRunning || isPaused -> startedMillis?.let { "up ${Format.duration((now - it).coerceAtLeast(0) / 1000)}" }
    exitedMillis != null -> "exited ${Format.ago(exitedMillis, now)}"
    createdMillis != null -> "created ${Format.ago(createdMillis, now)}"
    else -> null
}

private fun usageLabel(stats: ContainerStats?): String? {
    stats ?: return null
    val parts = listOfNotNull(
        stats.cpuPercent?.let { "CPU ${if (it < 10) String.format(java.util.Locale.US, "%.1f", it) else it.roundToInt().toString()}%" },
        stats.memUsage?.let { Format.bytes(it) },
    )
    return parts.joinToString(" · ").ifBlank { null }
}

private sealed interface Row {
    data class Header(val title: String) : Row
    data class Item(val container: PodmanContainer, val index: Int, val count: Int) : Row
}

private fun rows(list: List<PodmanContainer>): List<Row> {
    val groups = list.groupBy { it.podName }
    val hasPods = groups.keys.any { it != null }
    val out = mutableListOf<Row>()
    groups.entries.sortedWith(compareBy { it.key ?: "" }).forEach { (pod, items) ->
        if (hasPods) out += Row.Header(if (pod == null) "Containers" else "Pod $pod")
        items.forEachIndexed { i, c -> out += Row.Item(c, i, items.size) }
    }
    return out
}

@Composable
fun ContainersScreen(
    state: ContainersState,
    onRefresh: () -> Unit,
    onFilter: (ContainerFilter) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (PodmanContainer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val rows = remember(visible) { rows(visible) }
    Column(modifier.fillMaxSize()) {
        SearchField(state.query, onQuery, "Search containers", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter == ContainerFilter.RUNNING && state.query.isBlank(),
                onClick = { onFilter(ContainerFilter.RUNNING) },
                label = { Text(if (state.loaded) "Running ${state.running}" else "Running") },
            )
            FilterChip(
                selected = state.filter == ContainerFilter.ALL && state.query.isBlank(),
                onClick = { onFilter(ContainerFilter.ALL) },
                label = { Text(if (state.loaded) "All ${state.total}" else "All") },
            )
        }
        PullToRefreshBox(isRefreshing = state.loading && state.loaded, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.error?.let { msg -> item { ErrorCard(msg, actionLabel = "Retry", onAction = onRefresh, modifier = Modifier.padding(bottom = 10.dp)) } }
                if (!state.loaded) {
                    item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                } else if (visible.isEmpty() && state.error == null) {
                    item {
                        EmptyState(
                            CockpitIcons.Container,
                            when {
                                state.query.isNotBlank() -> "No matches"
                                state.total == 0 -> "No containers"
                                else -> "Nothing running"
                            },
                            when {
                                state.query.isNotBlank() -> "No container matches \"${state.query}\"."
                                state.total == 0 -> "Containers you create with Podman show up here."
                                else -> "${state.total} container${if (state.total == 1) " is" else "s are"} stopped. Choose All to see them."
                            },
                        )
                    }
                }
                items(rows, key = { r -> if (r is Row.Header) "h:${r.title}" else (r as Row.Item).container.key }) { r ->
                    when (r) {
                        is Row.Header -> Text(
                            r.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                        )
                        is Row.Item -> ContainerRow(
                            r.container,
                            state.stats[r.container.key],
                            state.busy[r.container.key],
                            showOwner = state.bothOwners,
                            shape = groupShape(r.index, r.count),
                            onClick = { onOpen(r.container) },
                        )
                    }
                }
                if (state.notes.isNotEmpty() && state.loaded) {
                    item { Hint(state.notes.joinToString("\n"), Modifier.padding(start = 4.dp, top = 14.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    c: PodmanContainer,
    stats: ContainerStats?,
    busy: ContainerAction?,
    showOwner: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val status = AppTheme.status
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c.statusColor(status))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.isService) Badge("service")
                // With both kinds present, mark the user's own (rootless) ones; system is the usual case.
                if (showOwner && c.owner == PodmanOwner.USER) Badge("user")
            }
            Text(
                listOfNotNull(shortImage(c.image), c.whenLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(c.stateLabel(), style = MaterialTheme.typography.labelMedium, color = c.statusColor(status))
                usageLabel(stats?.takeIf { c.isRunning })?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A small outlined tag after a name, like cockpit-podman's "service" badge. */
@Composable
fun Badge(text: String) {
    Spacer(Modifier.width(6.dp))
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun ContainerDetailScreen(
    container: PodmanContainer?,
    detail: ContainerDetailState?,
    stats: ContainerStats?,
    busy: ContainerAction?,
    onAction: (ContainerAction) -> Unit,
    onBack: () -> Unit,
) {
    val status = AppTheme.status
    var confirm by remember { mutableStateOf<ContainerAction?>(null) }
    PageScaffold(title = container?.name ?: "Container", onBack = onBack) {
        if (container == null) {
            Hint("This container no longer exists.")
            return@PageScaffold
        }
        val d = detail?.details
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(container.statusColor(status), size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(container.stateLabel(), style = MaterialTheme.typography.titleLarge, color = container.statusColor(status))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(shortImage(container.image), container.whenLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        detail?.error?.let { ErrorCard(it) }

        InfoCard("Control") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val enabled = busy == null
                when {
                    container.isRunning -> {
                        FilledTonalButton(onClick = { onAction(ContainerAction.RESTART) }, enabled = enabled) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Restart")
                        }
                        OutlinedButton(
                            onClick = { onAction(ContainerAction.STOP) },
                            enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(CockpitIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop")
                        }
                        if (!container.isService) {
                            OutlinedButton(onClick = { onAction(ContainerAction.PAUSE) }, enabled = enabled) {
                                Icon(CockpitIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Pause")
                            }
                        }
                    }
                    container.isPaused -> FilledTonalButton(onClick = { onAction(ContainerAction.RESUME) }, enabled = enabled) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Resume")
                    }
                    else -> {
                        FilledTonalButton(onClick = { onAction(ContainerAction.START) }, enabled = enabled) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Start")
                        }
                        if (!container.isService) {
                            OutlinedButton(
                                onClick = { confirm = ContainerAction.DELETE },
                                enabled = enabled,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
                if (busy != null) {
                    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${busy.progress}…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            container.systemdUnit?.let { unit ->
                Spacer(Modifier.height(10.dp))
                Hint("Runs as the systemd service $unit, so starting, stopping and restarting go through systemd, as in Cockpit.")
            }
        }

        if (container.isRunning && stats != null) {
            InfoCard("Usage") {
                stats.cpuPercent?.let { DetailRow("CPU", "${String.format(java.util.Locale.US, "%.1f", it)}%") }
                val mem = stats.memUsage
                val limit = stats.memLimit?.takeIf { it in 1 until Long.MAX_VALUE / 2 }
                if (mem != null) {
                    DetailRow("Memory", if (limit != null) "${Format.bytes(mem)} of ${Format.bytes(limit)}" else Format.bytes(mem))
                    if (limit != null) {
                        UsageBar(mem.toFloat() / limit, Modifier.padding(vertical = 6.dp))
                    }
                }
                if (stats.netIn != null || stats.netOut != null) DetailRow("Network", "↓ ${Format.bytes(stats.netIn)}  ↑ ${Format.bytes(stats.netOut)}")
                if (stats.blockIn != null || stats.blockOut != null) DetailRow("Disk", "read ${Format.bytes(stats.blockIn)}  ·  written ${Format.bytes(stats.blockOut)}")
                stats.pids?.let { DetailRow("Processes", it.toString()) }
            }
        }

        InfoCard("Details") {
            DetailRow("ID", container.shortId)
            DetailRow("Image", d?.imageName?.ifBlank { null } ?: container.image)
            (d?.command ?: container.command).ifBlank { null }?.let { DetailRow("Command", it) }
            DetailRow("Owner", container.owner.label)
            container.podName?.let { DetailRow("Pod", it) }
            container.systemdUnit?.let { DetailRow("Service", it) }
            container.createdMillis?.let { DetailRow("Created", Format.ago(it)) }
            if (!container.isRunning && container.exitedMillis != null) DetailRow("Exit code", container.exitCode.toString())
            d?.health?.let { DetailRow("Health", it) }
            d?.restartPolicy?.takeIf { it != "no" }?.let { DetailRow("Restart", it + (d.restartCount?.takeIf { n -> n > 0 }?.let { n -> " · restarted $n×" } ?: "")) }
            val ports = d?.ports?.ifEmpty { null } ?: container.ports
            if (ports.isNotEmpty()) DetailRow("Ports", ports.joinToString("\n"))
            d?.networks?.takeIf { it.isNotEmpty() }?.let { nets -> DetailRow("Networks", nets.joinToString("\n") { (n, ip) -> "$n: $ip" }) }
            d?.mounts?.takeIf { it.isNotEmpty() }?.let { DetailRow("Volumes", it.joinToString("\n")) }
            d?.error?.let { DetailRow("Error", it) }
        }

        InfoCard("Logs", inset = false) {
            val lines = detail?.logs.orEmpty()
            when {
                detail?.logsError != null -> Hint(detail.logsError, Modifier.padding(horizontal = 16.dp))
                lines.isEmpty() -> Hint(if (detail == null) "Loading…" else "No output yet.", Modifier.padding(horizontal = 16.dp))
                else -> SelectionContainer {
                    Text(
                        lines.takeLast(120).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = "Delete ${container?.name}?",
            body = "The container and its own files are removed. Its image and named volumes are kept.",
            confirm = action.label,
            destructive = true,
            onConfirm = { onAction(action); confirm = null; onBack() },
            onDismiss = { confirm = null },
        )
    }
}
