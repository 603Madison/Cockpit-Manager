package app.cockpitmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cockpitmanager.AppContainer
import app.cockpitmanager.cockpit.AuthenticationFailedException
import app.cockpitmanager.cockpit.CertificateInfo
import app.cockpitmanager.cockpit.CertificateTrustRequired
import app.cockpitmanager.cockpit.CockpitException
import app.cockpitmanager.cockpit.CockpitLogin
import app.cockpitmanager.cockpit.CockpitTransport
import app.cockpitmanager.cockpit.InitInfo
import app.cockpitmanager.cockpit.LoginStep
import app.cockpitmanager.cockpit.Problems
import app.cockpitmanager.cockpit.ServerTrust
import app.cockpitmanager.data.AppSettings
import app.cockpitmanager.data.ServerConfig
import app.cockpitmanager.system.JournalRepository
import app.cockpitmanager.system.MachinesClient
import app.cockpitmanager.system.PodmanServices
import app.cockpitmanager.system.VmConnection
import app.cockpitmanager.system.hasAdminAccess
import app.cockpitmanager.system.ServicesRepository
import app.cockpitmanager.terminal.TerminalSession
import app.cockpitmanager.ui.containers.ContainersModel
import app.cockpitmanager.ui.logs.LogsModel
import app.cockpitmanager.ui.machines.MachinesModel
import app.cockpitmanager.ui.overview.OverviewModel
import app.cockpitmanager.ui.services.ServicesModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.net.ssl.SSLPeerUnverifiedException

/** A signed-in connection and everything the screens need from it. */
class Session(
    val server: ServerConfig,
    val transport: CockpitTransport,
    val init: InitInfo,
    val scope: CoroutineScope,
) {
    private val servicesRepository = ServicesRepository(transport, scope)
    private val journal = JournalRepository(transport)

    private val admin = scope.async(start = CoroutineStart.LAZY) { transport.hasAdminAccess() }

    val overview = OverviewModel(transport, init, servicesRepository, scope)
    val services = ServicesModel(servicesRepository, journal, scope)
    val logs = LogsModel(journal, scope)
    val terminal = TerminalSession(transport, scope)
    val containers = ContainersModel(transport, { admin.await() }, scope)
    val machines = MachinesModel(transport, scope)

    /** What this server has beyond the basics, which decides the tabs. */
    data class Features(val checked: Boolean = false, val podman: Boolean = false, val machines: Boolean = false)

    private val _features = MutableStateFlow(Features())
    val features: StateFlow<Features> = _features.asStateFlow()

    init {
        scope.launch {
            val podman = async { PodmanServices.installed(transport) }
            val vms = async { VmConnection.entries.any { runCatching { MachinesClient.available(transport, scope, it.bus) }.getOrDefault(false) } }
            _features.value = Features(checked = true, podman = podman.await(), machines = vms.await())
        }
    }

    /** Set when the app closes the session on purpose, so that isn't reported as a lost connection. */
    @Volatile
    var closing = false
        private set

    fun close() {
        closing = true
        terminal.stop()
        transport.close()
        scope.cancel()
    }
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val server: ServerConfig, val step: String) : ConnectionState
    data class NeedsPassword(val server: ServerConfig, val error: String? = null) : ConnectionState
    data class NeedsCertificate(val server: ServerConfig, val info: CertificateInfo, val changed: Boolean) : ConnectionState
    data class NeedsAnswer(val server: ServerConfig, val prompt: LoginStep.Prompt) : ConnectionState
    data class Failed(val server: ServerConfig, val message: String) : ConnectionState
    data class Connected(val session: Session) : ConnectionState
    data class Lost(val session: Session, val message: String) : ConnectionState
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val servers: StateFlow<List<ServerConfig>?> = container.servers.servers
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    val appVersion: String get() = container.appVersion

    /** The password for the current attempt, kept in memory only so a reconnect needn't ask again. */
    private var password: String? = null
    private var rememberPassword = false
    private var loginJob: Job? = null
    private var answer: CompletableDeferred<String>? = null

    fun hasSavedPassword(server: ServerConfig) = container.secrets.has(server.passwordKey)

    fun connect(server: ServerConfig) {
        closeSession()
        password = null
        val saved = container.secrets.get(server.passwordKey)
        if (saved == null) {
            _connection.value = ConnectionState.NeedsPassword(server)
        } else {
            password = saved
            rememberPassword = true
            signIn(server)
        }
    }

    fun submitPassword(server: ServerConfig, value: String, remember: Boolean) {
        password = value
        rememberPassword = remember
        signIn(server)
    }

    fun trustCertificate(server: ServerConfig, info: CertificateInfo) {
        viewModelScope.launch {
            val pinned = server.copy(pinnedSha256 = info.sha256)
            container.servers.save(pinned)
            signIn(pinned)
        }
    }

    fun answerPrompt(text: String) {
        answer?.complete(text)
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        answer = null
        if (_connection.value !is ConnectionState.Connected) _connection.value = ConnectionState.Idle
    }

    fun disconnect() {
        closeSession()
        password = null
        _connection.value = ConnectionState.Idle
    }

    fun reconnect() {
        val session = (_connection.value as? ConnectionState.Lost)?.session ?: return
        val server = session.server
        closeSession()
        if (password != null) signIn(server) else connect(server)
    }

    fun saveServer(server: ServerConfig, newPassword: String?, remember: Boolean, connectAfter: Boolean = false) {
        when {
            !remember -> container.secrets.put(server.passwordKey, null)
            !newPassword.isNullOrEmpty() -> container.secrets.put(server.passwordKey, newPassword)
        }
        viewModelScope.launch {
            container.servers.save(server)
            if (!connectAfter) return@launch
            if (newPassword.isNullOrEmpty()) {
                connect(server)
            } else {
                closeSession()
                password = newPassword
                rememberPassword = remember
                signIn(server)
            }
        }
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            container.secrets.put(server.passwordKey, null)
            container.servers.delete(server.id)
        }
    }

    fun forgetCertificate(server: ServerConfig) {
        viewModelScope.launch { container.servers.save(server.copy(pinnedSha256 = null)) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    private fun closeSession() {
        when (val c = _connection.value) {
            is ConnectionState.Connected -> c.session.close()
            is ConnectionState.Lost -> c.session.close()
            else -> Unit
        }
        loginJob?.cancel()
    }

    private fun signIn(server: ServerConfig) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            val url = server.url
            val pass = password
            if (url == null || pass == null) {
                _connection.value = ConnectionState.Failed(server, "The address \"${server.address}\" isn't valid")
                return@launch
            }
            val trust = ServerTrust(server.pinnedSha256)
            val client = container.clientFor(trust)
            val login = CockpitLogin(client, url)
            try {
                _connection.value = ConnectionState.Connecting(server, "Signing in as ${server.user}")
                var step = login.start(server.user, pass, server.adminAccess)
                while (step is LoginStep.Prompt) {
                    val reply = CompletableDeferred<String>()
                    answer = reply
                    _connection.value = ConnectionState.NeedsAnswer(server, step)
                    val text = reply.await()
                    answer = null
                    _connection.value = ConnectionState.Connecting(server, "Checking")
                    step = login.answer(step, text)
                }
                val success = step as LoginStep.Success
                _connection.value = ConnectionState.Connecting(server, "Opening session")
                val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val transport = CockpitTransport(client, url, success.cookie, sessionScope)
                val init = try {
                    transport.open()
                } catch (e: Exception) {
                    sessionScope.cancel()
                    throw e
                }
                if (rememberPassword) container.secrets.put(server.passwordKey, pass) else container.secrets.put(server.passwordKey, null)
                val connected = server.copy(lastConnectedMillis = System.currentTimeMillis())
                container.servers.save(connected)
                val session = Session(connected, transport, init, sessionScope)
                _connection.value = ConnectionState.Connected(session)
                watch(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CertificateTrustRequired) {
                _connection.value = ConnectionState.NeedsCertificate(server, e.info, e.changed)
            } catch (e: AuthenticationFailedException) {
                // A saved password that stopped working is forgotten, so it's asked for again.
                container.secrets.put(server.passwordKey, null)
                password = null
                _connection.value = ConnectionState.NeedsPassword(server, e.message)
            } catch (e: CockpitException) {
                val seen = trust.lastSeen
                _connection.value = if (e.cause is SSLPeerUnverifiedException && seen != null) {
                    ConnectionState.NeedsCertificate(server, CertificateInfo.of(seen), changed = false)
                } else {
                    ConnectionState.Failed(server, e.message ?: "Couldn't connect")
                }
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(server, Problems.fromNetwork(e, url).message ?: "Couldn't connect")
            }
        }
    }

    /** Turns an unexpected disconnect into [ConnectionState.Lost], so the screens can offer to reconnect. */
    private fun watch(session: Session) {
        session.scope.launch {
            val closed = session.transport.state.first { it is CockpitTransport.State.Closed } as CockpitTransport.State.Closed
            val current = _connection.value
            if (!session.closing && current is ConnectionState.Connected && current.session === session) {
                _connection.value = ConnectionState.Lost(session, closed.message ?: Problems.describe(closed.problem ?: "disconnected"))
            }
        }
    }

    override fun onCleared() {
        closeSession()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
