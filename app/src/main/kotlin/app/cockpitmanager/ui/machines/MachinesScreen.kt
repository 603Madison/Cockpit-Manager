@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.cockpitmanager.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.VirtualMachine
import app.cockpitmanager.system.VmAction
import app.cockpitmanager.system.VmConnection
import app.cockpitmanager.system.VmState
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.DetailRow
import app.cockpitmanager.ui.components.EmptyState
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.components.Hint
import app.cockpitmanager.ui.components.InfoCard
import app.cockpitmanager.ui.components.LabeledSwitch
import app.cockpitmanager.ui.components.PageScaffold
import app.cockpitmanager.ui.components.StatusDot
import app.cockpitmanager.ui.containers.Badge
import app.cockpitmanager.ui.servers.ConfirmDialog
import app.cockpitmanager.ui.services.groupShape
import app.cockpitmanager.ui.theme.AppTheme
import app.cockpitmanager.ui.theme.StatusColors
import kotlin.math.roundToInt

fun VmState.color(status: StatusColors): Color = when (this) {
    VmState.RUNNING, VmState.BLOCKED -> status.good
    VmState.PAUSED, VmState.SHUTTING_DOWN, VmState.SUSPENDED -> status.warn
    VmState.CRASHED -> status.bad
    else -> status.idle
}

/** "Fedora 41 · 2 vCPUs · 4 GiB". */
fun VirtualMachine.summary(): String = listOfNotNull(
    description?.osName,
    description?.vcpus?.let { "$it vCPU${if (it == 1) "" else "s"}" },
    (description?.currentMemoryBytes ?: description?.memoryBytes)?.let { Format.bytes(it) },
).joinToString(" · ")

@Composable
fun MachinesScreen(
    state: MachinesState,
    onRefresh: () -> Unit,
    onFilter: (VmFilter) -> Unit,
    onOpen: (VirtualMachine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val status = AppTheme.status
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter == VmFilter.ALL,
                onClick = { onFilter(VmFilter.ALL) },
                label = { Text(if (state.loaded) "All ${state.vms.size}" else "All") },
            )
            FilterChip(
                selected = state.filter == VmFilter.RUNNING,
                onClick = { onFilter(VmFilter.RUNNING) },
                label = { Text(if (state.loaded) "Running ${state.running}" else "Running") },
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
                            CockpitIcons.Machine,
                            if (state.vms.isEmpty()) "No virtual machines" else "Nothing running",
                            if (state.vms.isEmpty()) "Virtual machines you create in Cockpit or virt-manager show up here." else "Choose All to see the ones that are shut off.",
                        )
                    }
                }
                itemsIndexed(visible, key = { _, vm -> vm.key }) { i, vm ->
                    val usage = state.usage[vm.key]
                    Row(
                        Modifier.fillMaxWidth().clip(groupShape(i, visible.size)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpen(vm) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(vm.state.color(status))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (state.bothConnections) Badge(if (vm.connection == VmConnection.SYSTEM) "system" else "session")
                            }
                            vm.summary().ifBlank { null }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (state.busy[vm.key] != null) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(vm.state.label, style = MaterialTheme.typography.labelMedium, color = vm.state.color(status))
                                usage?.cpuPercent?.takeIf { vm.state.isRunning }?.let {
                                    Text("CPU ${it.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MachineDetailScreen(
    vm: VirtualMachine?,
    usage: VmUsage?,
    addresses: List<String>,
    busy: VmAction?,
    autostartBusy: Boolean,
    onAction: (VmAction) -> Unit,
    onAutostart: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val status = AppTheme.status
    var confirm by remember { mutableStateOf<VmAction?>(null) }
    PageScaffold(title = vm?.displayName ?: "Virtual machine", onBack = onBack) {
        if (vm == null) {
            Hint("This virtual machine no longer exists.")
            return@PageScaffold
        }
        val d = vm.description
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(vm.state.color(status), size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(vm.state.label, style = MaterialTheme.typography.titleLarge, color = vm.state.color(status))
            }
            val subtitle = d?.description ?: vm.summary().ifBlank { null }
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        InfoCard("Control") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val enabled = busy == null
                when (vm.state) {
                    VmState.RUNNING, VmState.BLOCKED -> {
                        FilledTonalButton(onClick = { onAction(VmAction.SHUT_DOWN) }, enabled = enabled) {
                            Icon(CockpitIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Shut down")
                        }
                        OutlinedButton(onClick = { onAction(VmAction.REBOOT) }, enabled = enabled) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reboot")
                        }
                        OutlinedButton(onClick = { onAction(VmAction.PAUSE) }, enabled = enabled) {
                            Icon(CockpitIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pause")
                        }
                    }
                    VmState.PAUSED -> {
                        FilledTonalButton(onClick = { onAction(VmAction.RESUME) }, enabled = enabled) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Resume")
                        }
                    }
                    VmState.SHUT_OFF, VmState.CRASHED, VmState.NO_STATE -> {
                        FilledTonalButton(onClick = { onAction(VmAction.START) }, enabled = enabled) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Run")
                        }
                    }
                    else -> Unit
                }
                if (busy != null) {
                    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${busy.progress}…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (vm.state.isRunning || vm.state == VmState.SHUTTING_DOWN) {
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { confirm = VmAction.FORCE_OFF }, enabled = busy == null) {
                        Text("Force shut down", color = MaterialTheme.colorScheme.error)
                    }
                    if (vm.state.isRunning) {
                        TextButton(onClick = { confirm = VmAction.FORCE_REBOOT }, enabled = busy == null) { Text("Force reboot") }
                    }
                }
            }
            if (vm.persistent) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                LabeledSwitch(
                    title = "Run when the host boots",
                    subtitle = "Autostart",
                    checked = vm.autostart,
                    enabled = !autostartBusy,
                    onCheckedChange = onAutostart,
                )
            }
        }

        if (vm.state.isRunning && usage != null) {
            InfoCard("Usage") {
                usage.cpuPercent?.let { DetailRow("CPU", "${it.roundToInt()}%") }
                when {
                    usage.memoryUsedBytes != null -> DetailRow("Memory", "${Format.bytes(usage.memoryUsedBytes)} used by the guest")
                    usage.memoryRssBytes != null -> DetailRow("Memory", "${Format.bytes(usage.memoryRssBytes)} on the host")
                }
            }
        }

        InfoCard("Details") {
            d?.osName?.let { DetailRow("System", it) }
            d?.vcpus?.let { DetailRow("vCPUs", it.toString()) }
            (d?.currentMemoryBytes ?: d?.memoryBytes)?.let { DetailRow("Memory", Format.bytes(it)) }
            listOfNotNull(d?.arch, d?.machine).joinToString(" · ").ifBlank { null }?.let { DetailRow("Machine", it) }
            DetailRow("Connection", vm.connection.label)
            if (!vm.persistent) DetailRow("Kind", "Transient (gone when it stops)")
            d?.graphics?.takeIf { it.isNotEmpty() }?.let { DetailRow("Console", it.joinToString(", ")) }
            DetailRow("Name", vm.name)
            if (vm.uuid.isNotBlank()) DetailRow("UUID", vm.uuid)
        }

        if (!d?.disks.isNullOrEmpty()) {
            InfoCard("Disks") {
                d.disks.forEach { disk ->
                    DetailRow(disk.target, listOfNotNull(disk.source ?: "empty", disk.device.takeIf { it != "disk" }).joinToString(" · "))
                }
            }
        }

        if (!d?.interfaces.isNullOrEmpty() || addresses.isNotEmpty()) {
            InfoCard("Network") {
                d?.interfaces?.forEach { nic ->
                    DetailRow(nic.source ?: "interface", listOfNotNull(nic.mac, nic.model).joinToString(" · "))
                }
                if (addresses.isNotEmpty()) DetailRow("Addresses", addresses.joinToString("\n"))
                else if (vm.state.isRunning) Hint("IP addresses appear here once the guest gets one from libvirt's DHCP.")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = "${action.label} ${vm?.displayName}?",
            body = if (action == VmAction.FORCE_OFF) {
                "This cuts the power to the virtual machine, like pulling the plug. Unsaved work in it is lost."
            } else {
                "This resets the virtual machine immediately, without letting the guest shut down. Unsaved work in it is lost."
            },
            confirm = action.label,
            destructive = true,
            onConfirm = { onAction(action); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}
