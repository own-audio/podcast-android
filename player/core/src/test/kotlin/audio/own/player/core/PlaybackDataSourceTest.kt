package audio.own.player.core

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stream data source against a local server: links asked for when a source opens and again
 * after they expire, one renewal on 403/404/410, the storage route through the media proxy, and
 * the listening cache keyed by media id rather than URL.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackDataSourceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val audio = javaClass.getResource("/fixtures/test-tone.mp3")!!.readBytes()

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var answer: (RecordedRequest) -> MockResponse = { audioResponse() }

    private var clock = 1_000_000L
    private var resolves = 0
    private var expiresInMs: Long? = null
    private val resolver = PlaybackLinkResolver { request ->
        resolves += 1
        PlaybackLink(
            url = server.url("/api/v2/stream?i=${request.mediaId}&e=1&s=sig$resolves").toString(),
            expiresAtMs = expiresInMs?.let { clock + it },
        )
    }

    private var cache: SimpleCache? = null
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return answer(request)
            }
        }
        server.start()
        cacheDir = Files.createTempDirectory("listening").toFile()
    }

    @After
    fun tearDown() {
        cache?.release()
        cacheDir.deleteRecursively()
        server.close()
    }

    private fun audioResponse(type: String = "application/octet-stream") =
        MockResponse.Builder().code(200).setHeader("Content-Type", type).body(Buffer().write(audio)).build()

    private fun sources(withCache: Boolean = false, memory: ProxyMemory = ProxyMemory()): PlaybackDataSources {
        if (withCache) cache = SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        return PlaybackDataSources(context, resolver, OkHttpClient(), cache, memory, now = { clock })
    }

    private fun readAll(factory: DataSource.Factory, request: PlaybackLinkRequest = PlaybackLinkRequest("song:1")): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(PlaybackUris.forRequest(request)))
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == -1) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun paths() = synchronized(requests) { requests.map { it.url.toString() } }

    @Test
    fun `the link is asked for when the source opens and reused until it expires`() {
        expiresInMs = 6 * 3600_000L
        val sources = sources()
        assertEquals(0, resolves)
        assertArrayEquals(audio, readAll(sources.linkDataSourceFactory))
        assertArrayEquals(audio, readAll(sources.linkDataSourceFactory))
        assertEquals(1, resolves)
        clock += 6 * 3600_000L
        readAll(sources.linkDataSourceFactory)
        assertEquals(2, resolves)
    }

    @Test
    fun `a 403 from the stream renews the link once`() {
        answer = { if (it.url.queryParameter("s") == "sig1") MockResponse.Builder().code(403).build() else audioResponse() }
        assertArrayEquals(audio, readAll(sources().linkDataSourceFactory))
        assertEquals(2, resolves)
    }

    @Test
    fun `a second failure is the error, never a loop`() {
        answer = { MockResponse.Builder().code(410).build() }
        try {
            readAll(sources().linkDataSourceFactory)
            fail("expected an error")
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(410, error.responseCode)
        }
        assertEquals(2, resolves)
        assertEquals(2, paths().size)
    }

    @Test
    fun `blocked storage goes through the media proxy, and later streams go straight there`() {
        answer = { request ->
            if (request.url.queryParameter("route") == "proxy") {
                audioResponse()
            } else {
                // Storage on a host nothing answers on: the connection is refused.
                MockResponse.Builder().code(307).setHeader("Location", "http://127.0.0.1:1/bucket/file.mp3").build()
            }
        }
        val sources = sources()
        assertArrayEquals(audio, readAll(sources.linkDataSourceFactory))
        assertEquals(listOf(null, "proxy"), paths().map { it.toHttpUrl().queryParameter("route") })

        requests.clear()
        readAll(sources.linkDataSourceFactory, PlaybackLinkRequest("song:2"))
        assertEquals(listOf("proxy"), paths().map { it.toHttpUrl().queryParameter("route") })
    }

    @Test
    fun `a filtering proxy's 403 at the storage host also takes the proxy route`() {
        val storage = MockWebServer()
        storage.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(403).body("blocked by policy").build()
        }
        storage.start()
        try {
            answer = { request ->
                if (request.url.queryParameter("route") == "proxy") {
                    audioResponse()
                } else {
                    MockResponse.Builder().code(307).setHeader("Location", storage.url("/bucket/file.mp3").toString()).build()
                }
            }
            val memory = ProxyMemory()
            assertArrayEquals(audio, readAll(sources(memory = memory).linkDataSourceFactory))
            assertTrue(memory.usesProxy(server.url("/api/v2/stream?s=x").toString()))
            // Not an expired link: no second resolve.
            assertEquals(1, resolves)
        } finally {
            storage.close()
        }
    }

    @Test
    fun `an error page is refused before it reaches the cache`() {
        answer = { audioResponse(type = "application/json") }
        try {
            readAll(sources().linkDataSourceFactory)
            fail("expected an error")
        } catch (_: HttpDataSource.InvalidContentTypeException) {
        } catch (error: IOException) {
            fail("wrong error: $error")
        }
    }

    @Test
    fun `the cache key is the media id, not the signed URL`() {
        val sources = sources(withCache = true)
        assertArrayEquals(audio, readAll(sources.dataSourceFactory))
        assertTrue(sources.isFullyCached(PlaybackLinkRequest("song:1")))
        // Played again: from the cache, without a link or a request, though a new link would
        // have had another signature.
        assertArrayEquals(audio, readAll(sources.dataSourceFactory))
        assertEquals(1, resolves)
        assertEquals(1, paths().size)
    }

    @Test
    fun `a reduced copy is cached apart, and a cached original wins over it`() {
        val sources = sources(withCache = true)
        val reduced = PlaybackLinkRequest("song:1", maxBitrateKbps = 128)
        assertEquals("song:1~reduced", PlaybackUris.cacheKey(reduced))
        assertEquals("song:1#f2", PlaybackUris.cacheKey(PlaybackLinkRequest("song:1", fileId = "f2")))

        readAll(sources.dataSourceFactory, reduced)
        assertTrue(sources.isFullyCached(reduced))
        assertFalse(sources.isFullyCached(PlaybackLinkRequest("song:1")))

        readAll(sources.dataSourceFactory, PlaybackLinkRequest("song:1"))
        assertEquals(2, paths().size)
        // Now the original is complete: a smaller stream wanted still plays the original.
        readAll(sources.dataSourceFactory, reduced)
        assertEquals(2, paths().size)
    }
}
