package app.cockpitmanager.cockpit

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where a sign-in stands after each request. */
sealed interface LoginStep {
    /** Signed in; [cookie] opens the WebSocket. */
    data class Success(val cookie: String, val info: JsonObject) : LoginStep

    /**
     * The server wants another answer, usually a one-time code (PAM
     * conversation, e.g. Google Authenticator). [echo] false means the answer is
     * secret and should be masked.
     */
    data class Prompt(val id: String, val prompt: String, val echo: Boolean, val message: String?, val error: String?) : LoginStep
}

/**
 * Cockpit's sign-in, as its own login page does it: GET /cockpit/login with
 * Basic credentials, then answer any X-Conversation challenges. A successful
 * response sets the `cockpit` session cookie.
 */
class CockpitLogin(private val client: OkHttpClient, private val url: ServerUrl) {

    suspend fun start(user: String, password: String, adminAccess: Boolean): LoginStep {
        if (!url.cleartextAllowed) {
            throw CockpitException("Plain http:// would send your password unencrypted over the internet. Use https://.")
        }
        val credentials = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        return send(
            mapOf(
                "Authorization" to "Basic $credentials",
                // "any" asks Cockpit to start an administrative session with the same password (sudo),
                // like ticking "Reuse my password for privileged tasks" on the web login page.
                "X-Superuser" to if (adminAccess) "any" else "none",
            ),
            conversation = false,
        )
    }

    suspend fun answer(prompt: LoginStep.Prompt, answer: String): LoginStep {
        val encoded = Base64.getEncoder().encodeToString(answer.toByteArray(Charsets.UTF_8))
        return send(mapOf("Authorization" to "X-Conversation ${prompt.id} $encoded"), conversation = true)
    }

    private suspend fun send(headers: Map<String, String>, conversation: Boolean): LoginStep {
        val request = Request.Builder().url(url.loginUrl).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept", "application/json")
        }.build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw Problems.fromNetwork(e, url)
        }
        return response.use { interpret(it, conversation) }
    }

    private fun interpret(response: Response, conversation: Boolean): LoginStep {
        val body = runCatching { response.body.string() }.getOrDefault("")
        val status = runCatching { java.net.URLDecoder.decode(response.message, "UTF-8") }.getOrDefault(response.message).trim()
        when (response.code) {
            200 -> {
                val cookie = Cookie.parseAll(url.loginUrl, response.headers).firstOrNull { it.name == "cockpit" }
                    ?: throw CockpitException("The server signed you in but sent no session cookie. Is this a Cockpit server?")
                val info = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                return LoginStep.Success("${cookie.name}=${cookie.value}", info)
            }
            401 -> {
                val challenge = response.header("WWW-Authenticate").orEmpty()
                if (challenge.startsWith("X-Conversation", ignoreCase = true)) {
                    return parseChallenge(challenge, body) ?: throw CockpitException("The server sent an invalid sign-in challenge")
                }
                throw when {
                    status.startsWith("captured-stderr:") -> CockpitException(status.removePrefix("captured-stderr:").trim())
                    "authentication-not-supported" in status || "authentication-unavailable" in status ->
                        CockpitException(Problems.describe("authentication-not-supported"))
                    "terminated" in status -> AuthenticationFailedException("Authentication failed: the server closed the connection")
                    conversation -> AuthenticationFailedException("Authentication failed")
                    else -> AuthenticationFailedException("Wrong user name or password")
                }
            }
            403 -> throw CockpitException(
                if (status.isBlank() || status == "Permission denied") {
                    "Permission denied. Cockpit refuses some accounts (root, by default, via /etc/cockpit/disallowed-users)."
                } else {
                    "Permission denied: $status"
                },
                problem = "access-denied",
            )
            404 -> throw CockpitException("No Cockpit sign-in at ${url.display}. Check the address and port (Cockpit uses 9090).")
            in 300..399 -> {
                val location = response.header("Location")
                throw CockpitException(
                    if (location != null) "The server redirected to $location. Use that address instead." else "The server redirected elsewhere",
                )
            }
            500 -> throw CockpitException(
                when {
                    "no-cockpit" in status -> "Cockpit's system package (cockpit-system) isn't installed on the server"
                    "unsupported-shell" in status -> "Your account's shell isn't supported by Cockpit"
                    else -> "The server had a problem signing you in${if (status.isNotBlank()) ": $status" else ""}"
                },
            )
            else -> throw CockpitException("Sign-in failed (HTTP ${response.code}${if (status.isNotBlank()) ", $status" else ""})")
        }
    }

    companion object {
        /** "X-Conversation <id> <base64 prompt>", with optional JSON details in the body. */
        fun parseChallenge(header: String, body: String): LoginStep.Prompt? {
            val parts = header.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size != 3 || !parts[0].equals("X-Conversation", ignoreCase = true)) return null
            val prompt = runCatching { String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8) }.getOrNull() ?: return null
            val details = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            fun field(name: String) = details?.get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            return LoginStep.Prompt(
                id = parts[1],
                prompt = prompt.trim(),
                echo = details?.get("echo")?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false,
                message = field("message")?.takeIf { it.isNotBlank() },
                error = field("error")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Runs the call without blocking a thread, and cancels it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
