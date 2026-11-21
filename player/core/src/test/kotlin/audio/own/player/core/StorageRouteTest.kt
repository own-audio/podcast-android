package audio.own.player.core

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Port of music-ios `StorageRouteTests`: own.audio's stream link and its way through the media
 * proxy when storage is blocked. The two tests that run the file cache and the streaming decoder
 * through a stub server belong to the data source and come with it.
 */
class StorageRouteTest {
    companion object {
        const val LINK = "https://server.example/api/v2/stream?i=book:1&f=orig&b=0&o=0&u=u1&e=1900000000&k=1&s=ab%2Bcd%2F_e"
        const val STORAGE = "https://acct.r2.cloudflarestorage.com/bucket/file.m4b?X-Amz-Signature=1"
    }

    private class Clock(var now: Long = 2_000_000_000_000)

    /** A fetched body with what the server answered. */
    private data class Fetched(val body: String, val status: Int, val url: String)

    private val answer = { f: Fetched -> ProxyMemory.Answer(f.status, f.url) }

    @Test
    fun links() {
        assertTrue(StorageRoute.takesProxyRoute(LINK))
        assertFalse(StorageRoute.takesProxyRoute("https://abs.example/api/items/1/file/2?token=x"))
        assertFalse(StorageRoute.takesProxyRoute("https://feeds.example/episode.mp3?route=a"))
        assertFalse(StorageRoute.takesProxyRoute("file:///tmp/api/v2/stream?s=1"))
        val proxied = StorageRoute.proxied(LINK)
        assertEquals("$LINK&route=proxy", proxied)
        assertTrue(StorageRoute.isProxied(proxied) && !StorageRoute.isProxied(LINK))
        assertEquals("once is enough", proxied, StorageRoute.proxied(proxied))
        assertEquals(LINK, StorageRoute.direct(proxied))
        assertEquals(proxied, StorageRoute.alternate(LINK))
        assertEquals(LINK, StorageRoute.alternate(proxied))
        assertNull("another host's address is never changed", StorageRoute.alternate(STORAGE))
    }

    @Test
    fun blockingErrors() {
        val errors = listOf(
            UnknownHostException("acct.r2.cloudflarestorage.com"),
            ConnectException("Connection refused"),
            NoRouteToHostException(),
            SocketException("Connection reset"),
            SocketTimeoutException(),
            SSLHandshakeException("handshake"),
            SSLPeerUnverifiedException("bad certificate"),
        )
        for (error in errors) {
            assertTrue("$error", StorageRoute.isBlocked(error, LINK, failingUrl = STORAGE))
            assertTrue("wrapped $error", StorageRoute.isBlocked(StorageRoute.Failure(STORAGE, error), LINK))
        }
    }

    @Test
    fun notBlocked() {
        assertFalse("a cancel", StorageRoute.isBlocked(SocketException("Socket closed"), LINK, STORAGE))
        assertFalse(StorageRoute.isBlocked(CancellationException(), LINK, STORAGE))
        assertFalse(StorageRoute.isBlocked(IOException("unexpected end of stream"), LINK, STORAGE))
        assertFalse("the server's own host", StorageRoute.isBlocked(UnknownHostException(), LINK, LINK))
        assertFalse("no host to tell", StorageRoute.isBlocked(UnknownHostException(), LINK, null))
    }

    @Test
    fun blockingAnswers() {
        for (status in listOf(403, 407, 451)) {
            assertTrue("$status", StorageRoute.isBlocked(status, STORAGE, LINK))
        }
        assertFalse("invalid_link: renewed, not rerouted", StorageRoute.isBlocked(403, LINK, LINK))
        assertFalse(StorageRoute.isBlocked(404, STORAGE, LINK))
        assertFalse(StorageRoute.isBlocked(206, STORAGE, LINK))
        assertTrue("another port is another host", StorageRoute.isBlocked(403, "https://server.example:8443/x", LINK))
        assertFalse("the default port is the same host", StorageRoute.isBlocked(403, "https://server.example:443/x", LINK))
    }

    @Test
    fun memoryPerNetwork() {
        val clock = Clock()
        val store = ProxyMemory.InMemoryStore()
        val memory = ProxyMemory(store, now = { clock.now })
        memory.network = "wifi-office"
        assertEquals(LINK, memory.route(LINK))
        memory.worked(StorageRoute.proxied(LINK))
        assertTrue(memory.usesProxy(LINK))
        assertEquals(StorageRoute.proxied(LINK), memory.route(LINK))
        assertEquals("only stream links are routed", STORAGE, memory.route(STORAGE))
        assertFalse("another server", memory.usesProxy("https://other.example/api/v2/stream?s=1"))

        memory.network = "cellular"
        assertFalse("another network goes straight to storage", memory.usesProxy(LINK))

        val relaunched = ProxyMemory(store, now = { clock.now })
        relaunched.network = "wifi-office"
        assertTrue("kept in the store", relaunched.usesProxy(LINK))

        clock.now += ProxyMemory.DEFAULT_LIFETIME_MS + 1
        assertFalse("a week later storage is tried again", relaunched.usesProxy(LINK))
    }

    @Test
    fun memoryKeepsTheNewest32() {
        val clock = Clock()
        val memory = ProxyMemory(now = { clock.now })
        for (n in 0..32) {
            memory.network = "net$n"
            memory.worked(StorageRoute.proxied(LINK))
            clock.now += 1
        }
        memory.network = "net0"
        assertFalse("the oldest is dropped", memory.usesProxy(LINK))
        memory.network = "net32"
        assertTrue(memory.usesProxy(LINK))
        memory.network = "net1"
        assertTrue(memory.usesProxy(LINK))
    }

    @Test
    fun fetchFallsBack() = runTest {
        val memory = ProxyMemory()
        memory.network = "office"
        val tried = mutableListOf<String>()
        val result = memory.fetch(LINK, answer) { url ->
            tried += url
            if (StorageRoute.isProxied(url)) return@fetch Fetched("audio", 206, url)
            throw StorageRoute.Failure(STORAGE, UnknownHostException())
        }
        assertEquals("audio", result.body)
        assertEquals(listOf(LINK, StorageRoute.proxied(LINK)), tried)
        assertTrue(memory.usesProxy(LINK))

        memory.fetch(LINK, answer) { url ->
            tried += url
            Fetched("", 206, url)
        }
        assertEquals("straight to the proxy", 3, tried.size)
        assertEquals(StorageRoute.proxied(LINK), tried.last())
    }

    @Test
    fun fetchFallsBackOnAProxysAnswer() = runTest {
        val memory = ProxyMemory()
        val released = mutableListOf<Fetched>()
        val result = memory.fetch(LINK, answer, release = { released += it }) { url ->
            if (StorageRoute.isProxied(url)) Fetched("audio", 200, "https://media.example/file")
            else Fetched("Blocked by policy", 403, STORAGE)
        }
        assertEquals("audio", result.body)
        assertEquals("the dropped answer is closed", listOf("Blocked by policy"), released.map { it.body })
        assertTrue(memory.usesProxy(LINK))
    }

    @Test
    fun forgetsWhenStorageWorks() = runTest {
        val memory = ProxyMemory()
        memory.worked(StorageRoute.proxied(LINK))
        memory.fetch(LINK, answer) { url ->
            if (StorageRoute.isProxied(url)) throw StorageRoute.Failure("https://media.example/file", ConnectException())
            Fetched("", 206, STORAGE)
        }
        assertFalse(memory.usesProxy(LINK))
    }

    @Test
    fun noFallbackOtherwise() = runTest {
        val memory = ProxyMemory()
        val tried = mutableListOf<String>()
        try {
            memory.fetch<Fetched>(LINK, answer) { url ->
                tried += url
                throw IOException("unexpected end of stream")
            }
            fail("the error comes back")
        } catch (_: IOException) {
        }
        // A 403 from the server itself goes back to the caller, which renews the link.
        val result = memory.fetch(LINK, answer) { url ->
            tried += url
            Fetched("", 403, url)
        }
        assertEquals(403, result.status)
        assertEquals(listOf(LINK, LINK), tried)
        assertFalse(memory.usesProxy(LINK))
    }
}
