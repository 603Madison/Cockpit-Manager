package app.cockpitmanager.cockpit

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** A failure with a message written for people, not a stack trace. */
open class CockpitException(message: String, val problem: String? = null, cause: Throwable? = null) : Exception(message, cause)

class AuthenticationFailedException(message: String) : CockpitException(message, "authentication-failed")

/** The handshake failed on a certificate the user may choose to trust. */
class CertificateTrustRequired(val info: CertificateInfo, val changed: Boolean) :
    CockpitException(if (changed) "The server's certificate has changed" else "The server's certificate isn't trusted yet")

/** A spawned process exited unsuccessfully. [message] is its stderr when there was any. */
class ProcessFailedException(message: String, val exitStatus: Int?, val output: String) : CockpitException(message)

class DBusException(val name: String, message: String) : CockpitException(message.ifBlank { name })

object Problems {
    /** Cockpit's problem codes (doc/protocol.md), in words. */
    fun describe(problem: String?, fallback: String? = null): String = when (problem) {
        null, "" -> fallback ?: "Unknown error"
        "access-denied" -> "Permission denied. Turn on administrative access for this server, or sign in as a user who can use sudo."
        "authentication-failed" -> "Wrong user name or password"
        "authentication-unavailable", "authentication-not-supported" -> "The server doesn't accept password sign-in for this user"
        "not-found" -> "Not found on the server"
        "no-cockpit" -> "Cockpit isn't installed on that server"
        "no-session" -> "The server didn't start a session"
        "terminated" -> "The server ended the session"
        "timeout" -> "The server stopped responding"
        "disconnected" -> "Disconnected from the server"
        "no-forwarding" -> "The server refused to forward the connection"
        "unknown-hostkey", "invalid-hostkey", "unknown-host" -> "The server's identity couldn't be verified"
        "not-supported" -> "This server's Cockpit doesn't support that"
        "protocol-error" -> "The server sent something unexpected"
        "internal-error" -> fallback ?: "Cockpit reported an internal error"
        else -> fallback ?: problem.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Turn a network failure into something people can act on. */
    fun fromNetwork(e: Throwable, url: ServerUrl): CockpitException {
        if (e is CockpitException) return e
        generateSequence(e) { it.cause }.firstOrNull { it is UntrustedCertificateException }?.let {
            val u = it as UntrustedCertificateException
            return CertificateTrustRequired(u.info, u.changed)
        }
        return when (e) {
            is UnknownHostException -> CockpitException("Couldn't find ${url.host}. Check the address, and that you're on the right network.", cause = e)
            is ConnectException, is NoRouteToHostException ->
                CockpitException("Couldn't reach ${url.hostHeader}. Is Cockpit running (systemctl enable --now cockpit.socket) and is port ${url.port} open in the firewall?", cause = e)
            is SocketTimeoutException -> CockpitException("${url.hostHeader} didn't answer in time", cause = e)
            is SSLPeerUnverifiedException -> CockpitException("The certificate doesn't match ${url.host}", cause = e)
            is SSLException ->
                if (!url.https) CockpitException("Secure connection failed", cause = e)
                else CockpitException("Secure connection to ${url.hostHeader} failed: ${e.message ?: "TLS error"}. If the server uses plain HTTP, add it as http://", cause = e)
            is IOException -> CockpitException(e.message ?: "Network error", cause = e)
            else -> CockpitException(e.message ?: e.javaClass.simpleName, cause = e)
        }
    }
}
