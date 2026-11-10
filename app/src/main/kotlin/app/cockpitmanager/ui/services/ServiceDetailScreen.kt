@file:OptIn(ExperimentalLayoutApi::class)

package app.cockpitmanager.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.ServiceAction
import app.cockpitmanager.system.ServiceUnit
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.DetailRow
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.InfoCard
import app.cockpitmanager.ui.components.LabeledSwitch
import app.cockpitmanager.ui.components.PageScaffold
import app.cockpitmanager.ui.components.StatusDot
import app.cockpitmanager.ui.logs.LogLine
import app.cockpitmanager.ui.theme.AppTheme

@Composable
fun ServiceDetailScreen(
    unit: ServiceUnit?,
    detail: ServiceDetailState?,
    busy: ServiceAction?,
    message: String?,
    onAction: (ServiceAction) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val name = detail?.name ?: unit?.name ?: ""
    val d = detail?.details
    val status = AppTheme.status
    PageScaffold(
        title = name.removeSuffix(".service"),
        onBack = onBack,
        actions = { IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
    ) {
        // State, from the list (fast) or the details (complete).
        val active = d?.get("ActiveState") ?: unit?.activeState ?: ""
        val sub = d?.get("SubState") ?: unit?.subState ?: ""
        val fileState = d?.get("UnitFileState") ?: unit?.fileState
        val shown = unit?.copy(activeState = active, subState = sub, fileState = fileState)
            ?: ServiceUnit(name, d?.get("Description").orEmpty(), d?.get("LoadState").orEmpty(), active, sub, fileState)

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(shown.statusColor(status), size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(shown.stateLabel.ifBlank { "Loading…" }, style = MaterialTheme.typography.titleLarge, color = shown.statusColor(status))
            }
            val description = d?.get("Description") ?: unit?.description
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message?.let { InfoCard(null) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        detail?.error?.let { ErrorCard(it) }

        InfoCard("Control") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val enabled = busy == null
                if (shown.isActive || shown.isTransitioning) {
                    FilledTonalButton(onClick = { onAction(ServiceAction.RESTART) }, enabled = enabled) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Restart")
                    }
                    OutlinedButton(onClick = { onAction(ServiceAction.RELOAD) }, enabled = enabled) { Text("Reload") }
                    OutlinedButton(
                        onClick = { onAction(ServiceAction.STOP) },
                        enabled = enabled,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(CockpitIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                } else {
                    FilledTonalButton(onClick = { onAction(ServiceAction.START) }, enabled = enabled && !shown.isMasked) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start")
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
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            if (shown.canToggleBoot) {
                LabeledSwitch(
                    title = "Start at boot",
                    subtitle = fileState?.let { "Currently $it" },
                    checked = shown.isEnabled,
                    enabled = busy == null,
                    onCheckedChange = { on -> onAction(if (on) ServiceAction.ENABLE else ServiceAction.DISABLE) },
                )
            } else {
                Hint(
                    when {
                        shown.isMasked -> "This service is masked, so it can't be started."
                        fileState == "static" -> "Static: started only when another unit needs it."
                        fileState != null -> "Boot behaviour: $fileState"
                        else -> "Boot behaviour unknown"
                    },
                )
            }
        }

        if (d != null) {
            InfoCard("Details") {
                d["ActiveEnterTimestamp"]?.takeIf { shown.isActive }?.let { DetailRow("Since", it) }
                d["InactiveEnterTimestamp"]?.takeIf { !shown.isActive && it.isNotBlank() }?.let { DetailRow("Stopped", it) }
                d.mainPid?.let { DetailRow("Main PID", it.toString()) }
                d.memoryBytes?.let { DetailRow("Memory", Format.bytes(it)) }
                d.cpuNanos?.let { DetailRow("CPU time", Format.cpuTime(it)) }
                d.tasks?.let { DetailRow("Tasks", it.toString()) }
                d["NRestarts"]?.takeIf { it != "0" }?.let { DetailRow("Restarts", it) }
                d["Result"]?.takeIf { it != "success" }?.let { DetailRow("Result", it) }
                d["Type"]?.let { DetailRow("Type", it) }
                d["User"]?.let { DetailRow("Runs as", it) }
                d["FragmentPath"]?.let { DetailRow("Unit file", it) }
            }
        } else if (detail?.loading != false) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }

        detail?.logs?.takeIf { it.isNotEmpty() }?.let { logs ->
            InfoCard("Recent logs", inset = false) {
                logs.take(40).forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    LogLine(entry, showSource = false, monospace = true)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
