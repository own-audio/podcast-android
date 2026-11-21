package audio.own.kit.feed

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class FeedFetcherTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test
    fun `a fetch keeps the validators, the next one sends them and a 304 costs nothing`() = runTest {
        val body = fixture("itunes.xml")
        serve { request ->
            if (request.headers["If-None-Match"] == "\"v1\"") {
                MockResponse.Builder().code(304).build()
            } else {
                MockResponse.Builder().code(200).body(Buffer().write(body))
                    .addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Fri, 16 Oct 2026 08:30:00 GMT")
                    .addHeader("Content-Type", "application/rss+xml").build()
            }
        }
        val fetcher = FeedFetcher(client, testParser)
        val url = server.url("/workshop/feed.xml").toString()
        val result = fetcher.fetch(url)
        if (result !is FeedFetcher.Result.Fetched) return@runTest fail("the first fetch should bring the feed")
        assertEquals("The Workshop Hour", result.document.show.title)
        assertEquals(3, result.document.episodes.size)
        assertEquals(FeedFetcher.Validators("\"v1\"", "Fri, 16 Oct 2026 08:30:00 GMT"), result.validators)
        assertEquals(url, result.finalUrl)
        assertEquals(FeedFetcher.Result.NotModified, fetcher.fetch(url, result.validators))

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Fri, 16 Oct 2026 08:30:00 GMT", second.headers["If-Modified-Since"])
        for (request in listOf(first, second)) {
            assertEquals("own.audio Podcasts", request.headers["User-Agent"])
            assertEquals(FeedFetcher.ACCEPT, request.headers["Accept"])
            assertEquals(null, request.headers["Cookie"])
        }
    }

    @Test
    fun `the final URL after a redirect is reported`() = runTest {
        serve { request ->
            when (request.url.encodedPath) {
                "/old" -> MockResponse.Builder().code(301).addHeader("Location", "/new.xml").build()
                else -> MockResponse.Builder().code(200).body(Buffer().write(fixture("atom.xml"))).build()
            }
        }
        val result = FeedFetcher(client, testParser).fetch(server.url("/old").toString())
        assertTrue(result is FeedFetcher.Result.Fetched)
        assertEquals(server.url("/new.xml").toString(), (result as FeedFetcher.Result.Fetched).finalUrl)
    }

    @Test
    fun `errors say what went wrong - an HTTP status, an answer too large, an HTML page`() = runTest {
        serve { request ->
            when (request.url.encodedPath) {
                "/gone" -> MockResponse.Builder().code(410).build()
                "/huge" -> MockResponse.Builder().code(200).body(Buffer().write(ByteArray(2048))).build()
                "/chunked" -> MockResponse.Builder().code(200).chunkedBody(Buffer().write(ByteArray(4096)), 512).build()
                else -> MockResponse.Builder().code(200).body("<!DOCTYPE html><html><body>Hi</body></html>").build()
            }
        }
        val fetcher = FeedFetcher(client, testParser, maxBytes = 1024)
        expect<FeedException.Http> { fetcher.fetch(server.url("/gone").toString()) }.also { assertEquals(410, it.status) }
        expect<FeedException.TooLarge> { fetcher.fetch(server.url("/huge").toString()) }
        expect<FeedException.TooLarge> { fetcher.fetch(server.url("/chunked").toString()) }
        expect<FeedException.NotAFeed> { fetcher.fetch(server.url("/page").toString()) }
    }

    @Test
    fun `a network failure is a network error`() = runTest {
        val url = server.url("/feed.xml").toString()
        server.close()
        expect<FeedException.Network> { FeedFetcher(client, testParser).fetch(url) }
    }

    @Test
    fun `a chapters document is fetched and parsed`() = runTest {
        serve { MockResponse.Builder().code(200).body(Buffer().write(fixture("chapters.json"))).build() }
        val chapters = FeedFetcher(client, testParser).chapters(server.url("/rain.chapters.json").toString())
        assertEquals(4, chapters.size)
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }
}

/**
 * Real feeds from the wild, named in `FEED_URLS` (space-separated) or listed in
 * `real-feeds.txt` when `REAL_FEEDS=1`: each parses, has a title and episodes with enclosures.
 * Not run by default; the fixtures cover the forms.
 */
class LiveFeedTest {
    @Test
    fun `every listed feed parses into a show with playable episodes`() = runTest {
        val fromEnv = System.getenv("FEED_URLS").orEmpty().split(' ').filter { it.isNotBlank() }
        val fromFile = if (System.getenv("REAL_FEEDS") == "1") {
            String(fixtureOrEmpty("real-feeds.txt")).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            emptyList()
        }
        val urls = fromEnv + fromFile
        assumeTrue("set FEED_URLS or REAL_FEEDS=1 to fetch real feeds", urls.isNotEmpty())
        val fetcher = FeedFetcher(OkHttpClient(), testParser, userAgent = "own.audio Podcasts (tests)")
        val failures = mutableListOf<String>()
        for (url in urls) {
            try {
                val result = fetcher.fetch(url)
                if (result !is FeedFetcher.Result.Fetched) {
                    failures += "$url answered 304 to a first fetch"; continue
                }
                val document = result.document
                val playable = document.episodes.filter { it.enclosure != null }
                if (document.show.title.isEmpty()) failures += "$url has no title"
                if (playable.isEmpty()) failures += "$url has no episodes with audio"
                if (document.episodes.map { it.id }.toSet().size != document.episodes.size) failures += "$url: episode ids are not unique"
                playable.firstNotNullOfOrNull { it.chaptersUrl }?.let { chapters ->
                    if (fetcher.chapters(chapters).isEmpty()) failures += "$chapters holds no chapters"
                }
                val withChapters = playable.count { it.chaptersUrl != null || it.inlineChapters.isNotEmpty() }
                println("$url: ${document.show.title} — ${playable.size} episodes, ${playable.count { it.durationMs != null }} with durations, $withChapters with chapters")
            } catch (error: FeedException) {
                failures += "$url: $error"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fixtureOrEmpty(name: String): ByteArray =
        javaClass.getResourceAsStream("/$name")?.use { it.readBytes() } ?: ByteArray(0)
}
