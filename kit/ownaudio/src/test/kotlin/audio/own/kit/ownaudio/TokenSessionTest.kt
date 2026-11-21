package audio.own.kit.ownaudio

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenSessionTest {

    private fun refreshing(server: (String) -> MockResponse) = StubServer {
        if (it.url.encodedPath == "/api/v2/auth/token") server(it.form()["refresh_token"] ?: "") else answerFor(it.headers["Authorization"])
    }

    private fun answerFor(bearer: String?): MockResponse =
        if (bearer == "Bearer a2") json("""{"items":[]}""") else problem("unauthorized", 401)

    @Test
    fun `a 401 is answered with one refresh and one retry, the rotated tokens are kept`() = runBlocking {
        StubServer {
            when {
                it.url.encodedPath == "/api/v2/auth/token" -> json(tokenJson("a2", "r2"))
                it.headers["Authorization"] == "Bearer a1" -> problem("unauthorized", 401)
                else -> json(ME)
            }
        }.use { stub ->
            val store = RecordingStore()
            val session = TokenSession(farTokens("a1", "r1"), store)
            val me = stub.client(tokens = session).me()
            assertEquals("admin", me.family.role)
            assertEquals("r2", session.current?.refreshToken)
            assertEquals(listOf("r2"), store.saved.map { it?.refreshToken })
            assertEquals(listOf("GET /api/v2/me", "POST /api/v2/auth/token", "GET /api/v2/me"), stub.paths())
            assertEquals("r1", stub.requests[1].form()["refresh_token"])
        }
    }

    @Test
    fun `twenty requests that find the token expired share one refresh`() = runBlocking {
        val refreshes = AtomicInteger()
        refreshing {
            refreshes.incrementAndGet()
            json(tokenJson("a2", "r2")).newBuilder().headersDelay(200, TimeUnit.MILLISECONDS).build()
        }.use { stub ->
            val store = RecordingStore()
            val session = TokenSession(expiredTokens("a1", "r1"), store)
            val client = stub.client(tokens = session)
            (1..20).map { async(Dispatchers.IO) { client.genres() } }.awaitAll()
            assertEquals(1, refreshes.get())
            val bearers = stub.requests.filter { it.url.encodedPath != "/api/v2/auth/token" }.map { it.headers["Authorization"] }
            assertEquals(20, bearers.size)
            assertTrue(bearers.all { it == "Bearer a2" })
            assertEquals("one save, of the fresh pair only", listOf("r2"), store.saved.map { it?.refreshToken })
        }
    }

    @Test
    fun `twenty requests answered 401 at once share one refresh too`() = runBlocking {
        val refreshes = AtomicInteger()
        refreshing {
            refreshes.incrementAndGet()
            json(tokenJson("a2", "r2")).newBuilder().headersDelay(100, TimeUnit.MILLISECONDS).build()
        }.use { stub ->
            val session = TokenSession(farTokens("a1", "r1"))
            val client = stub.client(tokens = session)
            (1..20).map { async(Dispatchers.IO) { client.genres() } }.awaitAll()
            assertEquals(1, refreshes.get())
        }
    }

    @Test
    fun `the rotated pair is stored before anyone uses it`() = runBlocking {
        val store = RecordingStore()
        var storedWhenUsed: String? = null
        StubServer {
            when {
                it.url.encodedPath == "/api/v2/auth/token" -> json(tokenJson("a2", "r2"))
                it.headers["Authorization"] == "Bearer a2" -> {
                    storedWhenUsed = store.saved.lastOrNull()?.accessToken
                    json("""{"items":[]}""")
                }
                else -> problem("unauthorized", 401)
            }
        }.use { stub ->
            stub.client(tokens = TokenSession(expiredTokens("a1", "r1"), store)).genres()
            assertEquals("a2", storedWhenUsed)
        }
    }

    @Test
    fun `a refused refresh stops everything - no loop, no further request`() = runBlocking {
        refreshing { problem("invalid_grant", 400, ""","error":"invalid_grant"""") }.use { stub ->
            val store = RecordingStore()
            val session = TokenSession(expiredTokens(), store)
            val client = stub.client(tokens = session)
            try {
                client.me()
                fail("expected SignInRefused")
            } catch (_: OwnAudioException.SignInRefused) {
            }
            assertEquals(SignInState.SIGN_IN_REFUSED, session.state.value)
            assertNull(session.current)
            assertEquals(listOf<Tokens?>(null), store.saved)
            val before = stub.requests.size
            repeat(5) {
                try {
                    client.genres()
                    fail("expected SignInRefused")
                } catch (_: OwnAudioException.SignInRefused) {
                }
            }
            assertEquals("nothing more reaches the server", before, stub.requests.size)
            assertEquals(1, stub.requests.count { it.url.encodedPath == "/api/v2/auth/token" })
        }
    }

    @Test
    fun `invalid_credentials on refresh counts as refused, other problems do not`() = runBlocking {
        refreshing { problem("invalid_credentials", 401) }.use { stub ->
            val session = TokenSession(expiredTokens())
            runCatching { stub.client(tokens = session).me() }
            assertEquals(SignInState.SIGN_IN_REFUSED, session.state.value)
        }
        refreshing { problem("rate_limited", 429) }.use { stub ->
            val session = TokenSession(expiredTokens())
            val error = runCatching { stub.client(tokens = session).me() }.exceptionOrNull()
            assertTrue(error is OwnAudioException.Problem && error.code == "rate_limited")
            assertEquals("a busy server doesn't sign anyone out", SignInState.SIGNED_IN, session.state.value)
            assertEquals("r1", session.current?.refreshToken)
        }
    }

    @Test
    fun `a sign-in again clears the refused state, stale tokens are never written back`() = runBlocking {
        val store = RecordingStore()
        val session = TokenSession(expiredTokens(), store)
        try {
            session.accessToken { throw OwnAudioException.Problem(400, "invalid_grant") }
            fail("expected SignInRefused")
        } catch (_: OwnAudioException.SignInRefused) {
        }
        session.signedIn(farTokens("a9", "r9"))
        assertEquals(SignInState.SIGNED_IN, session.state.value)
        assertEquals("a9", session.accessToken { error("no refresh while fresh") })
        assertEquals(listOf(null, "r9"), store.saved.map { it?.refreshToken })
    }

    @Test
    fun `signed out without tokens`() = runBlocking {
        StubServer { json(ME) }.use { stub ->
            val error = runCatching { stub.client(tokens = null).me() }.exceptionOrNull()
            assertTrue(error is OwnAudioException.SignedOut)
            assertTrue(stub.requests.isEmpty())
        }
    }

    @Test
    fun `sign-out revokes the refresh token and forgets the pair`() = runBlocking {
        StubServer { MockResponse.Builder().code(200).build() }.use { stub ->
            val store = RecordingStore()
            val session = TokenSession(farTokens("a", "r"), store)
            stub.client(tokens = session).signOut()
            val sent = stub.requests.single()
            assertEquals("/api/v2/auth/revoke", sent.url.encodedPath)
            assertEquals(mapOf("token" to "r", "token_type_hint" to "refresh_token"), sent.form())
            assertNull(session.current)
            assertEquals(SignInState.SIGNED_OUT, session.state.value)
            assertEquals(listOf<Tokens?>(null), store.saved)
        }
    }

    @Test
    fun `one session per server`() {
        val sessions = TokenSessions()
        val first = sessions.session("s1") { TokenSession(null) }
        assertTrue(first === sessions.session("s1") { TokenSession(farTokens()) })
        assertTrue(first !== sessions.session("s2") { TokenSession(null) })
    }
}
