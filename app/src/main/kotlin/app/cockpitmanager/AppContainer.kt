package app.cockpitmanager

import android.content.Context
import app.cockpitmanager.cockpit.ServerTrust
import app.cockpitmanager.data.AndroidSecretStore
import app.cockpitmanager.data.DataStoreServerStore
import app.cockpitmanager.data.DataStoreSettingsStore
import app.cockpitmanager.data.SecretStore
import app.cockpitmanager.data.ServerStore
import app.cockpitmanager.data.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** The app's long-lived objects. */
class AppContainer(
    val servers: ServerStore,
    val settings: SettingsStore,
    val secrets: SecretStore,
    val appVersion: String,
) {
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Never follow redirects: Cockpit's cookie and Origin check are tied to the address used.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** A client that trusts what Android trusts plus, for this server only, the certificate the user pinned. */
    fun clientFor(trust: ServerTrust): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return baseClient.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(trust.hostnameVerifier)
            .build()
    }

    companion object {
        fun create(context: Context): AppContainer {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: ""
            return AppContainer(
                servers = DataStoreServerStore(context),
                settings = DataStoreSettingsStore(context),
                secrets = AndroidSecretStore(context),
                appVersion = version,
            )
        }
    }
}
