package app.cockpitmanager.cockpit

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class CockpitLoginTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var url: ServerUrl

    @Before
    fun setUp() {
        server.start()
        url = ServerUrl(https = false, host = "127.0.0.1", port = server.port)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun successReturnsSessionCookie() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Set-Cookie", "cockpit=c2Vzc2lvbg; Path=/; Secure; HttpOnly")
                .body("""{"csrf-token":"abc"}""").build(),
        )
        val step = CockpitLogin(client, url).start("madison", "pä:ss", adminAccess = true)
        val success = assertIs<LoginStep.Success>(step)
        assertEquals("cockpit=c2Vzc2lvbg", success.cookie)

        val request = server.takeRequest()
        assertEquals("/cockpit/login", request.url.encodedPath)
        val expected = Base64.getEncoder().encodeToString("madison:pä:ss".toByteArray(Charsets.UTF_8))
        assertEquals("Basic $expected", request.headers["Authorization"])
        assertEquals("any", request.headers["X-Superuser"])
    }

    @Test
    fun withoutAdminAccessAsksForNone() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "cockpit=x").body("{}").build())
        CockpitLogin(client, url).start("u", "p", adminAccess = false)
        assertEquals("none", server.takeRequest().headers["X-Superuser"])
    }

    @Test
    fun conversationPromptsForCode() = runTest {
        val prompt = Base64.getEncoder().encodeToString("Verification code: ".toByteArray())
        server.enqueue(
            MockResponse.Builder().code(401)
                .addHeader("WWW-Authenticate", "X-Conversation conv42 $prompt")
                .body("""{"echo":true,"message":"Open your authenticator app"}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "cockpit=done").body("{}").build())

        val login = CockpitLogin(client, url)
        val step = assertIs<LoginStep.Prompt>(login.start("u", "p", adminAccess = true))
        assertEquals("Verification code:", step.prompt)
        assertTrue(step.echo)
        assertEquals("Open your authenticator app", step.message)

        assertIs<LoginStep.Success>(login.answer(step, "123456"))
        server.takeRequest()
        val answer = server.takeRequest().headers["Authorization"]!!
        assertEquals("X-Conversation conv42 ${Base64.getEncoder().encodeToString("123456".toByteArray())}", answer)
    }

    @Test
    fun wrongPassword() = runTest {
        server.enqueue(MockResponse.Builder().code(401).status("HTTP/1.1 401 authentication-failed").body("""{"problem":"authentication-failed"}""").build())
        val e = assertFailsWith<AuthenticationFailedException> { CockpitLogin(client, url).start("u", "bad", true) }
        assertEquals("Wrong user name or password", e.message)
    }

    @Test
    fun permissionDeniedMentionsDisallowedUsers() = runTest {
        server.enqueue(MockResponse.Builder().code(403).status("HTTP/1.1 403 Permission denied").build())
        val e = assertFailsWith<CockpitException> { CockpitLogin(client, url).start("root", "pw", true) }
        assertTrue(e.message!!.contains("disallowed-users"))
    }

    @Test
    fun cleartextToPublicAddressIsRefusedBeforeSending() = runTest {
        val public = ServerUrl(https = false, host = "example.com", port = 9090)
        assertFailsWith<CockpitException> { CockpitLogin(client, public).start("u", "p", true) }
    }

    @Test
    fun parsesChallengeHeader() {
        val p = CockpitLogin.parseChallenge("X-Conversation abc ${Base64.getEncoder().encodeToString("Password: ".toByteArray())}", "")!!
        assertEquals("abc", p.id)
        assertEquals("Password:", p.prompt)
        assertEquals(false, p.echo)
        assertEquals(null, CockpitLogin.parseChallenge("Basic realm=x", ""))
    }
}
