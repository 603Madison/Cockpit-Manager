@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.cockpitmanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.ui.components.Backdrop
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.logs.LogsScreen
import app.cockpitmanager.ui.overview.OverviewScreen
import app.cockpitmanager.ui.servers.ConnectionDialogs
import app.cockpitmanager.ui.servers.ServerEditScreen
import app.cockpitmanager.ui.servers.ServersScreen
import app.cockpitmanager.ui.services.ServiceDetailScreen
import app.cockpitmanager.ui.services.ServiceFilter
import app.cockpitmanager.ui.services.ServicesScreen
import app.cockpitmanager.ui.settings.SettingsScreen
import app.cockpitmanager.ui.terminal.TerminalScreen

/** Literal so lint doesn't flag the API 37 constant; only requested where it's enforced. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
fun AppRoot(vm: MainViewModel) {
    val connection by vm.connection.collectAsState()
    val session = when (val c = connection) {
        is ConnectionState.Connected -> c.session
        is ConnectionState.Lost -> c.session
        else -> null
    }
    if (session != null) {
        androidx.compose.runtime.key(session) { SessionRoot(vm, session, connection as? ConnectionState.Lost) }
    } else {
        ServersRoot(vm, connection)
    }
}

/** Android 17 asks before apps reach the local network, where most Cockpit servers are. */
@Composable
private fun rememberLocalNetworkGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val needed = Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needed) {
            pending = action
            launcher.launch(LOCAL_NETWORK)
        } else {
            action()
        }
    }
}

private object Routes {
    const val SERVERS = "servers"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
    const val OVERVIEW = "overview"
    const val SERVICES = "services"
    const val SERVICE = "service"
    const val LOGS = "logs"
    const val TERMINAL = "terminal"
}

@Composable
private fun ServersRoot(vm: MainViewModel, connection: ConnectionState) {
    val nav = rememberNavController()
    val servers by vm.servers.collectAsState()
    val settings by vm.settings.collectAsState()
    val gate = rememberLocalNetworkGate()
    val connect = { server: ServerConfig -> gate { vm.connect(server) } }

    NavHost(nav, startDestination = Routes.SERVERS) {
        composable(Routes.SERVERS) {
            ServersScreen(
                servers = servers,
                onConnect = connect,
                onAdd = { nav.navigate("${Routes.EDIT}/new") },
                onEdit = { nav.navigate("${Routes.EDIT}/${it.id}") },
                onDelete = vm::deleteServer,
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
            ConnectionDialogs(
                state = connection,
                onPassword = { server, password, remember -> vm.submitPassword(server, password, remember) },
                onTrust = vm::trustCertificate,
                onAnswer = vm::answerPrompt,
                onRetry = connect,
                onEdit = { nav.navigate("${Routes.EDIT}/${it.id}") },
                onCancel = vm::cancel,
            )
        }
        composable("${Routes.EDIT}/{id}") { entry ->
            val id = entry.arguments?.read { getStringOrNull("id") }
            val existing = servers?.firstOrNull { it.id == id }
            ServerEditScreen(
                initial = existing,
                hasSavedPassword = existing?.let(vm::hasSavedPassword) ?: false,
                onSave = { server, password, remember ->
                    // A newly added server connects straight away.
                    if (existing == null) gate { vm.saveServer(server, password, remember, connectAfter = true) }
                    else vm.saveServer(server, password, remember)
                },
                onForgetCertificate = vm::forgetCertificate,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(settings, vm.appVersion, vm::updateSettings, onBack = { nav.popBackStack() })
        }
    }
}

enum class SessionTab(val route: String, val label: String, val icon: ImageVector) {
    OVERVIEW(Routes.OVERVIEW, "Overview", CockpitIcons.Gauge),
    SERVICES(Routes.SERVICES, "Services", CockpitIcons.Services),
    LOGS(Routes.LOGS, "Logs", CockpitIcons.Logs),
    TERMINAL(Routes.TERMINAL, "Terminal", CockpitIcons.Terminal),
}

private fun NavHostController.navigateToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun SessionRoot(vm: MainViewModel, session: Session, lost: ConnectionState.Lost?) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = if (route == null) SessionTab.OVERVIEW else SessionTab.entries.firstOrNull { it.route == route }
    val settings by vm.settings.collectAsState()
    val overview by session.overview.state.collectAsState()
    val services by session.services.state.collectAsState()
    val detail by session.services.detail.collectAsState()
    val logs by session.logs.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(services.message) {
        services.message?.let {
            snackbar.showSnackbar(it)
            session.services.consumeMessage()
        }
    }

    SessionChrome(
        server = session.server,
        selected = tab,
        failedUnits = overview.failedUnits ?: 0,
        lostMessage = lost?.message,
        snackbar = snackbar,
        onTab = { nav.navigateToTab(it.route) },
        onSettings = { nav.navigate(Routes.SETTINGS) },
        onDisconnect = vm::disconnect,
        onReconnect = vm::reconnect,
    ) {
        NavHost(nav, startDestination = Routes.OVERVIEW, modifier = Modifier.fillMaxSize()) {
            composable(Routes.OVERVIEW) {
                DisposableEffect(Unit) {
                    session.overview.start()
                    onDispose { session.overview.stop() }
                }
                OverviewScreen(
                    state = overview,
                    onOpenFailed = {
                        session.services.setFilter(ServiceFilter.FAILED)
                        session.services.setQuery("")
                        nav.navigateToTab(Routes.SERVICES)
                    },
                    onPower = session.overview::power,
                )
            }
            composable(Routes.SERVICES) {
                LaunchedEffect(Unit) { session.services.refresh() }
                ServicesScreen(
                    state = services,
                    onRefresh = session.services::refresh,
                    onFilter = { session.services.setFilter(it); session.services.setQuery("") },
                    onQuery = session.services::setQuery,
                    onOpen = { nav.navigate("${Routes.SERVICE}/${it.name}") },
                )
            }
            composable("${Routes.SERVICE}/{name}") { e ->
                val name = e.arguments?.read { getStringOrNull("name") } ?: return@composable
                DisposableEffect(name) {
                    session.services.openDetail(name)
                    onDispose { session.services.closeDetail() }
                }
                ServiceDetailScreen(
                    unit = services.services.firstOrNull { it.name == name },
                    detail = detail?.takeIf { it.name == name },
                    busy = services.busy[name],
                    message = null,
                    onAction = { session.services.act(name, it) },
                    onRefresh = { session.services.openDetail(name) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.LOGS) {
                DisposableEffect(Unit) {
                    session.logs.start()
                    onDispose { session.logs.stop() }
                }
                LogsScreen(
                    state = logs,
                    onLevel = session.logs::setLevel,
                    onSearch = session.logs::setSearch,
                    onLive = session.logs::setLive,
                    onBootOnly = session.logs::setBootOnly,
                    onRefresh = session.logs::load,
                    onLoadMore = session.logs::loadMore,
                )
            }
            composable(Routes.TERMINAL) {
                TerminalScreen(session.terminal, settings.terminalTextSize.sp)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settings, vm.appVersion, vm::updateSettings, onBack = { nav.popBackStack() })
            }
        }
    }
}

/**
 * Everything around a connected server's screens: the server in the top bar,
 * the tabs at the bottom (both only on tab screens; sub-pages bring their own
 * bar), a lost-connection banner and the snackbar.
 */
@Composable
fun SessionChrome(
    server: ServerConfig,
    selected: SessionTab?,
    failedUnits: Int,
    lostMessage: String?,
    snackbar: SnackbarHostState,
    onTab: (SessionTab) -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    content: @Composable () -> Unit,
) {
    val onTab = selected != null
    val imeVisible = WindowInsets.isImeVisible
    Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (onTab) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(server.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(server.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        actions = {
                            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                            IconButton(onClick = onDisconnect) { Icon(CockpitIcons.Logout, contentDescription = "Disconnect") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }
            },
            bottomBar = {
                if (onTab && !imeVisible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        SessionTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selected == tab,
                                onClick = { onTab(tab) },
                                icon = {
                                    val badge = if (tab == SessionTab.SERVICES) failedUnits else 0
                                    BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar, modifier = if (onTab) Modifier else Modifier.navigationBarsPadding()) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                lostMessage?.let {
                    ErrorCard(
                        "Connection lost. $it",
                        actionLabel = "Reconnect",
                        onAction = onReconnect,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                content()
            }
        }
    }
}
