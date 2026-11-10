@file:OptIn(ExperimentalLayoutApi::class)

package app.cockpitmanager.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.PowerAction
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.DetailRow
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.InfoCard
import app.cockpitmanager.ui.components.Pill
import app.cockpitmanager.ui.components.Sparkline
import app.cockpitmanager.ui.components.UsageBar
import app.cockpitmanager.ui.servers.ConfirmDialog
import app.cockpitmanager.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(
    state: OverviewState,
    onOpenFailed: () -> Unit,
    onPower: suspend (PowerAction) -> Result<Unit>,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    var powerError by remember { mutableStateOf<String?>(null) }
    var powerBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(state, onOpenFailed)
        state.powerMessage?.let { InfoCard(null) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        state.error?.let { ErrorCard(it) }
        state.usageError?.let { ErrorCard("Live usage isn't available: $it") }
        UsageCard(state)
        if (state.mounts.isNotEmpty()) StorageCard(state.mounts)
        TrafficCard(state)
        state.facts?.let { SystemCard(state) }
        InfoCard("Power") {
            Hint(
                if (state.adminAccess == false) "Restarting or shutting down needs administrative access."
                else "Everyone signed in to the server is disconnected.",
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { confirm = PowerAction.RESTART }, enabled = !powerBusy && state.adminAccess != false) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restart")
                }
                OutlinedButton(
                    onClick = { confirm = PowerAction.SHUT_DOWN },
                    enabled = !powerBusy && state.adminAccess != false,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(CockpitIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shut down")
                }
                if (powerBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            powerError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    confirm?.let { action ->
        val host = state.facts?.hostname?.ifBlank { null } ?: "the server"
        ConfirmDialog(
            title = "${action.label} $host?",
            body = if (action == PowerAction.RESTART) {
                "The server restarts now. You'll be disconnected and can reconnect once it's back up."
            } else {
                "The server powers off now. You'll need physical or out-of-band access to turn it back on."
            },
            confirm = action.label,
            destructive = action == PowerAction.SHUT_DOWN,
            onConfirm = {
                confirm = null
                powerBusy = true
                powerError = null
                scope.launch {
                    onPower(action).onFailure { powerError = it.message ?: "Couldn't ${action.label.lowercase()}" }
                    powerBusy = false
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun Hero(state: OverviewState, onOpenFailed: () -> Unit) {
    val facts = state.facts
    val status = AppTheme.status
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        if (facts == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Reading system information…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(facts.hostname, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(state.osName, "up ${Format.duration(facts.uptimeSeconds)}").joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.adminAccess) {
                true -> Pill("Administrative access", status.good, icon = CockpitIcons.Shield)
                false -> Pill("Limited access", status.warn, icon = CockpitIcons.Shield)
                null -> Unit
            }
            when (val failed = state.failedUnits) {
                null -> Unit
                0 -> Pill("All services OK", status.good)
                else -> Pill("$failed failed service${if (failed == 1) "" else "s"}", status.bad, onClick = onOpenFailed)
            }
        }
    }
}

@Composable
private fun UsageCard(state: OverviewState) {
    val facts = state.facts
    InfoCard("Usage") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Metric("CPU", state.cpu?.let { "${it.roundToInt()}%" } ?: "–", facts?.let { "${it.cpuCount} core${if (it.cpuCount == 1) "" else "s"}" })
                Spacer(Modifier.height(8.dp))
                Sparkline(state.cpuHistory, max = 100f, capacity = OverviewModel.HISTORY)
            }
            Column(Modifier.weight(1f)) {
                Metric(
                    "Memory",
                    state.memFraction?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                    if (state.memUsed != null && facts != null) "${Format.bytes(state.memUsed)} of ${Format.bytes(facts.memTotalBytes)}" else null,
                )
                Spacer(Modifier.height(8.dp))
                Sparkline(state.memHistory, max = 100f, capacity = OverviewModel.HISTORY, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (facts != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            if (facts.swapTotalBytes > 0) {
                val used = facts.swapTotalBytes - facts.swapFreeBytes
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Swap", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                    UsageBar(used.toFloat() / facts.swapTotalBytes, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(Format.bytes(used), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
            }
            if (facts.load.size == 3) {
                DetailRow("Load", facts.load.joinToString("  ·  ") { String.format(java.util.Locale.US, "%.2f", it) })
            }
            state.temperature?.let { DetailRow("CPU temp", "${it.roundToInt()} °C") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, detail: String?) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light), maxLines = 1)
    Text(detail ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StorageCard(mounts: List<Mount>) {
    InfoCard("Storage", trailing = { Icon(CockpitIcons.Disk, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(20.dp)) }) {
        mounts.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.path, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.bytes(m.used)} of ${Format.bytes(m.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(m.fraction)
        }
    }
}

@Composable
private fun TrafficCard(state: OverviewState) {
    InfoCard("Network & disks", trailing = { Icon(CockpitIcons.Network, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(20.dp)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Rate("Receiving", state.netRx, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            Rate("Sending", state.netTx, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Sparkline(
            state.netRxHistory,
            capacity = OverviewModel.HISTORY,
            secondary = state.netTxHistory,
            secondaryColor = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        DetailRow("Disk reads", Format.rate(state.diskRead))
        DetailRow("Disk writes", Format.rate(state.diskWrite))
    }
}

@Composable
private fun Rate(label: String, value: Double?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.cockpitmanager.ui.components.StatusDot(color, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Format.rate(value), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), maxLines = 1)
    }
}

@Composable
private fun SystemCard(state: OverviewState) {
    val f = state.facts ?: return
    InfoCard("System", trailing = { Icon(CockpitIcons.Cpu, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(20.dp)) }) {
        if (f.hardware.isNotBlank()) DetailRow("Hardware", f.hardware)
        if (f.cpuModel.isNotBlank()) DetailRow("Processor", "${f.cpuModel} × ${f.cpuCount}")
        state.osName?.let { DetailRow("System", it) }
        DetailRow("Kernel", f.kernel)
        DetailRow("Architecture", f.arch)
        DetailRow("Memory", Format.bytes(f.memTotalBytes))
    }
}
