package app.cockpitmanager.cockpit

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What the user is shown before deciding whether to trust a server's certificate. */
data class CertificateInfo(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val selfSigned: Boolean,
) {
    companion object {
        fun of(cert: X509Certificate) = CertificateInfo(
            sha256 = fingerprint(cert),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notBeforeMillis = cert.notBefore.time,
            notAfterMillis = cert.notAfter.time,
            selfSigned = cert.subjectX500Principal == cert.issuerX500Principal,
        )

        /** SHA-256 of the DER certificate, as colon-separated upper-case hex (OpenSSL's format). */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

        /** Common name out of an X.500 name, for a friendlier display. */
        fun commonName(x500: String): String? =
            x500.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)
    }
}

/** The server's certificate isn't trusted by Android and isn't the one the user pinned. */
class UntrustedCertificateException(val info: CertificateInfo, val changed: Boolean, cause: Throwable?) :
    CertificateException("Untrusted certificate ${info.sha256}", cause)

/**
 * Trust on first use, for Cockpit's default self-signed certificate.
 *
 * A certificate is accepted when Android trusts it (system or user CAs, via
 * the network security config) or when its SHA-256 fingerprint equals the one
 * the user explicitly trusted for this server. Anything else fails the
 * handshake; [lastSeen] keeps the presented certificate so the app can show
 * its fingerprint and ask.
 *
 * Validation itself is Android's own trust manager; this only adds the exact
 * fingerprint the user approved, so lint's general warning doesn't apply.
 */
@SuppressLint("CustomX509TrustManager")
class ServerTrust(private val pinnedSha256: String?) : X509TrustManager {
    private val platform: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile
    var lastSeen: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not accepted")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        lastSeen = leaf
        if (pinnedSha256 != null && CertificateInfo.fingerprint(leaf).equals(pinnedSha256, ignoreCase = true)) return
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UntrustedCertificateException(CertificateInfo.of(leaf), changed = pinnedSha256 != null, cause = e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    /**
     * A pinned certificate is the user's explicit decision about this exact
     * server, so its name needn't match (self-signed certificates often name
     * the machine, not the address used). Otherwise the usual hostname check.
     */
    val hostnameVerifier = HostnameVerifier { host: String, session: SSLSession ->
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (leaf != null && pinnedSha256 != null && CertificateInfo.fingerprint(leaf).equals(pinnedSha256, ignoreCase = true)) {
            true
        } else {
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
    }
}
