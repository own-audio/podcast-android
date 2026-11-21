package audio.own.kit.audiobookshelf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TokenSessionTest {
    private val http = testHttp()
    private val librariesJson = """{"libraries":[{"id":"lib1","name":"Books","mediaType":"book"},{"id":"lib2","name":"Pods","mediaType":"podcast"}]}"""

    @Test
    fun `a 401 is answered with one refresh (x-refresh-token) and one retry, the rotated tokens are kept`() = runTest {
        val fresh = jwt(3600, id = "a2")
        Stub { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    assertEquals("r1", request.headers["x-refresh-token"])
                    json(userJson(fresh, refresh = "r2"))
                }
                "/api/libraries" ->
                    if (request.headers["Authorization"] == "Bearer $fresh") json(librariesJson) else text("Unauthorized", 401)
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val store = RecordingStore()
            val tokens = TokenSession(Tokens(jwt(3600), "r1", System.currentTimeMillis() + 3_600_000), store)
            val client = AudiobookshelfClient(stub.host, tokens, http)
            assertEquals(listOf("lib1"), client.bookLibraries().map { it.id })
            assertEquals("r2", store.saved.last()?.refreshToken)
            assertEquals("r2", tokens.current?.refreshToken)
            assertEquals(listOf("/api/libraries", "/auth/refresh", "/api/libraries"), stub.paths)
        }
    }

    @Test
    fun `requests that find the token about to expire share one refresh`() = runTest {
        val fresh = jwt(3600, id = "a2")
        Stub { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    Thread.sleep(150)
                    json(userJson(fresh, refresh = "r2"))
                }
                else -> {
                    assertEquals("Bearer $fresh", request.headers["Authorization"])
                    json("""{"libraries":[]}""")
                }
            }
        }.use { stub ->
            val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 10_000))
            val client = AudiobookshelfClient(stub.host, tokens, http)
            (1..5).map { async(Dispatchers.Default) { client.bookLibraries() } }.awaitAll()
            assertEquals(1, stub.paths.count { it == "/auth/refresh" })
            assertEquals(5, stub.paths.count { it == "/api/libraries" })
        }
    }

    @Test
    fun `concurrent 401s spend the refresh token once - late callers take the new tokens`() = runTest {
        val fresh = jwt(3600, id = "a2")
        Stub { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    if (request.headers["x-refresh-token"] != "r1") return@Stub text("Unauthorized", 401)
                    Thread.sleep(100)
                    json(userJson(fresh, refresh = "r2"))
                }
                else -> if (request.headers["Authorization"] == "Bearer $fresh") json(librariesJson) else text("Unauthorized", 401)
            }
        }.use { stub ->
            val store = RecordingStore()
            // Not near expiry by the clock, but revoked on the server: every caller meets a 401.
            val tokens = TokenSession(Tokens("revoked", "r1", System.currentTimeMillis() + 3_600_000), store)
            val client = AudiobookshelfClient(stub.host, tokens, http)
            val answers = (1..4).map { async(Dispatchers.Default) { client.bookLibraries() } }.awaitAll()
            assertTrue(answers.all { it.single().id == "lib1" })
            assertEquals(1, stub.paths.count { it == "/auth/refresh" })
            assertEquals(listOf("r2"), store.saved.map { it?.refreshToken })
        }
    }

    @Test
    fun `rotated tokens are saved before any request uses them`() = runTest {
        val fresh = jwt(3600, id = "a2")
        val usedFresh = AtomicInteger()
        Stub { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> json(userJson(fresh, refresh = "r2"))
                else -> {
                    if (request.headers["Authorization"] == "Bearer $fresh") usedFresh.incrementAndGet()
                    json(librariesJson)
                }
            }
        }.use { stub ->
            val store = RecordingStore()
            var usedWhenSaved = -1
            store.onSave = { usedWhenSaved = usedFresh.get() }
            val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 1_000), store)
            AudiobookshelfClient(stub.host, tokens, http).bookLibraries()
            assertEquals(0, usedWhenSaved)
            assertEquals(1, usedFresh.get())
        }
    }

    @Test
    fun `a refresh whose caller is cancelled still finishes and saves the rotated tokens`() = runBlocking {
        val fresh = jwt(3600, id = "a2")
        Stub { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    Thread.sleep(400)
                    json(userJson(fresh, refresh = "r2"))
                }
                else -> json(librariesJson)
            }
        }.use { stub ->
            val store = RecordingStore()
            val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 1_000), store)
            val client = AudiobookshelfClient(stub.host, tokens, http)
            val job = launch(Dispatchers.Default) { client.bookLibraries() }
            delay(100)
            job.cancel()
            withTimeout(5_000) { while (store.saved.isEmpty()) delay(20) }
            assertEquals("r2", store.saved.single()?.refreshToken)
            assertEquals("r2", tokens.current?.refreshToken)
        }
    }

    @Test
    fun `a refused refresh signs out and clears the stored tokens`() = runTest {
        Stub { text("Unauthorized", 401) }.use { stub ->
            val store = RecordingStore()
            val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 3_600_000), store)
            val client = AudiobookshelfClient(stub.host, tokens, http)
            try {
                client.bookLibraries()
                fail("expected SignedOut")
            } catch (_: AudiobookshelfError.SignedOut) {
            }
            assertEquals(listOf<Tokens?>(null), store.saved.toList())
            assertNull(tokens.current)
            // Signed out, nothing more is sent.
            val before = stub.requests.size
            try {
                client.bookLibraries()
                fail("expected SignedOut")
            } catch (_: AudiobookshelfError.SignedOut) {
            }
            assertEquals(before, stub.requests.size)
        }
    }

    @Test
    fun `a refresh that fails on the network keeps the tokens`() = runTest {
        val stub = Stub { text("Unauthorized", 401) }
        val host = stub.host
        stub.close()
        val store = RecordingStore()
        val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 1_000), store)
        try {
            AudiobookshelfClient(host, tokens, http).bookLibraries()
            fail("expected Network")
        } catch (_: AudiobookshelfError.Network) {
        }
        assertEquals("r1", tokens.current?.refreshToken)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a legacy token that gets a 401 is signed out, not refreshed`() = runTest {
        Stub { text("Unauthorized", 401) }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens("legacy"), http)
            try {
                client.bookLibraries()
                fail("expected SignedOut")
            } catch (_: AudiobookshelfError.SignedOut) {
            }
            assertEquals(1, stub.requests.size)
        }
    }

    @Test
    fun `sign-out sends both tokens and forgets them even when the server fails`() = runTest {
        Stub { request ->
            assertEquals("/logout", request.url.encodedPath)
            assertEquals("Bearer a", request.headers["Authorization"])
            assertEquals("r", request.headers["x-refresh-token"])
            text("Internal Server Error", 500)
        }.use { stub ->
            val store = RecordingStore()
            val tokens = TokenSession(Tokens("a", "r", Tokens.NEVER), store)
            AudiobookshelfClient(stub.host, tokens, http).signOut()
            assertNull(tokens.current)
            assertEquals(listOf<Tokens?>(null), store.saved.toList())
            assertEquals(1, stub.requests.size)
        }
    }

    @Test
    fun `a media URL refused mid-book gets a fresh token, refreshed once`() = runTest {
        val fresh = jwt(3600, id = "a2")
        Stub { json(userJson(fresh, refresh = "r2")) }.use { stub ->
            val tokens = TokenSession(Tokens("old", "r1", System.currentTimeMillis() + 3_600_000))
            val client = AudiobookshelfClient(stub.host, tokens, http)
            assertEquals("old", client.freshToken())
            assertEquals(fresh, client.tokenAfterRefusal("old"))
            // A second refusal of the same old token takes the new one without refreshing again.
            assertEquals(fresh, client.tokenAfterRefusal("old"))
            assertEquals(listOf("/auth/refresh"), stub.paths)
        }
    }

    @Test
    fun `the JWT's exp is read, anything else has no expiry`() {
        val token = jwt(100)
        val exp = Tokens.expiryOfJwt(token)!!
        assertTrue(kotlin.math.abs(exp - System.currentTimeMillis() - 100_000) < 5_000)
        assertNull(Tokens.expiryOfJwt("legacy-token"))
        assertNull(Tokens.expiryOfJwt("a.%%%.c"))
        assertNull(Tokens.expiryOfJwt("${b64url("{}")}.${b64url("""{"sub":"x"}""")}.s"))
    }

    @Test
    fun `tokens never print themselves`() {
        val t = Tokens("secret-access", "secret-refresh", 1)
        assertTrue(!t.toString().contains("secret"))
    }
}
