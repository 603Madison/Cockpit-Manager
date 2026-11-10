@file:OptIn(ExperimentalMaterial3Api::class)

package app.cockpitmanager.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.ui.Format
import app.cockpitmanager.ui.components.Backdrop
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.EmptyState

@Composable
fun ServersScreen(
    servers: List<ServerConfig>?,
    onConnect: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerConfig) -> Unit,
    onDelete: (ServerConfig) -> Unit,
    onSettings: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<ServerConfig?>(null) }
    Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                if (!servers.isNullOrEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = onAdd,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Add server") },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(onSettings) }
                when {
                    servers == null -> Unit
                    servers.isEmpty() -> item {
                        EmptyState(
                            icon = CockpitIcons.Server,
                            title = "Add your first server",
                            body = "Connect to any Linux machine running Cockpit, the web console that ships with RHEL, Fedora, CentOS Stream, Rocky and Alma. It listens on port 9090.",
                        ) {
                            FilledTonalButton(onClick = onAdd) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add server")
                            }
                        }
                    }
                    else -> items(servers.sortedByDescending { it.lastConnectedMillis ?: 0 }, key = { it.id }) { server ->
                        ServerCard(server, onClick = { onConnect(server) }, onEdit = { onEdit(server) }, onDelete = { confirmDelete = server })
                    }
                }
            }
        }
    }
    confirmDelete?.let { server ->
        ConfirmDialog(
            title = "Remove ${server.title}?",
            body = "Its address, user name and saved password are removed from this phone. Nothing changes on the server.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { onDelete(server); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CockpitIcons.Gauge, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Cockpit Manager", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text("Your servers", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
    }
}

@Composable
private fun ServerCard(server: ServerConfig, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CockpitIcons.Server, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(server.lastConnectedMillis?.let { "Connected ${Format.ago(it)}" } ?: "Never connected")
                        if (server.adminAccess) append(" · Admin")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${server.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
