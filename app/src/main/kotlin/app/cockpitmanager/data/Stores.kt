package app.cockpitmanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cockpitmanager.cockpit.ServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** A Cockpit server the user added. The password lives in [SecretStore], never here. */
@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val user: String = "",
    /** Sign in with administrative access (sudo with the same password). */
    val adminAccess: Boolean = true,
    /** SHA-256 of the certificate the user chose to trust, for self-signed servers. */
    val pinnedSha256: String? = null,
    val lastConnectedMillis: Long? = null,
) {
    val url: ServerUrl? get() = ServerUrl.parse(address)
    val title: String get() = name.ifBlank { url?.host ?: address }
    val subtitle: String get() = "$user@${url?.hostHeader ?: address}"
    val passwordKey: String get() = "password:$id"
}

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

enum class TerminalTextSize(val label: String, val sp: Float) { SMALL("Small", 11f), MEDIUM("Medium", 13f), LARGE("Large", 15f) }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val terminalTextSize: TerminalTextSize = TerminalTextSize.MEDIUM,
    /** Heavier Lexend throughout, whatever Android's own Bold text setting is. */
    val boldText: Boolean = true,
)

interface ServerStore {
    val servers: Flow<List<ServerConfig>>
    suspend fun save(server: ServerConfig)
    suspend fun delete(id: String)
}

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val serverListSerializer = ListSerializer(ServerConfig.serializer())

class DataStoreServerStore(context: Context) : ServerStore {
    private val store = context.applicationContext.dataStore
    private val key = stringPreferencesKey("servers")

    override val servers: Flow<List<ServerConfig>> = store.data.map { prefs -> decode(prefs[key]) }

    override suspend fun save(server: ServerConfig) {
        store.edit { prefs ->
            val list = decode(prefs[key]).toMutableList()
            val i = list.indexOfFirst { it.id == server.id }
            if (i >= 0) list[i] = server else list += server
            prefs[key] = json.encodeToString(serverListSerializer, list)
        }
    }

    override suspend fun delete(id: String) {
        store.edit { prefs -> prefs[key] = json.encodeToString(serverListSerializer, decode(prefs[key]).filterNot { it.id == id }) }
    }

    private fun decode(raw: String?): List<ServerConfig> =
        raw?.let { runCatching { json.decodeFromString(serverListSerializer, it) }.getOrNull() }.orEmpty()
}

class DataStoreSettingsStore(context: Context) : SettingsStore {
    private val store = context.applicationContext.dataStore
    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val terminalSizeKey = intPreferencesKey("terminal_text_size")
    private val boldKey = booleanPreferencesKey("bold_text")

    private fun read(p: Preferences) = AppSettings(
        themeMode = ThemeMode.entries.firstOrNull { it.name == p[themeKey] } ?: ThemeMode.SYSTEM,
        dynamicColor = p[dynamicKey] ?: false,
        terminalTextSize = TerminalTextSize.entries.getOrNull(p[terminalSizeKey] ?: -1) ?: TerminalTextSize.MEDIUM,
        boldText = p[boldKey] ?: true,
    )

    override val settings: Flow<AppSettings> = store.data.map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val next = transform(read(p))
            p[themeKey] = next.themeMode.name
            p[dynamicKey] = next.dynamicColor
            p[terminalSizeKey] = next.terminalTextSize.ordinal
            p[boldKey] = next.boldText
        }
    }
}

/** In-memory stores for tests and screenshots. */
class MemoryServerStore(initial: List<ServerConfig> = emptyList()) : ServerStore {
    private val state = MutableStateFlow(initial)
    val current: StateFlow<List<ServerConfig>> = state.asStateFlow()
    override val servers: Flow<List<ServerConfig>> = state
    override suspend fun save(server: ServerConfig) = state.update { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }
    override suspend fun delete(id: String) = state.update { list -> list.filterNot { it.id == id } }
}

class MemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}
