package audio.own.podcast.library

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ShowCatalogueTest {
    private fun apple(answer: (RecordedRequest) -> MockResponse): Pair<MockWebServer, AppleShowCatalogue> {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answer(request)
        }
        server.start()
        return server to AppleShowCatalogue(OkHttpClient(), "CZ", server.url("/"))
    }

    private fun json(text: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(text).build()

    @Test
    fun `Apple's chart is read in its order with one lookup for the feed addresses`() = runBlocking {
        val (server, catalogue) = apple { request ->
            when (request.url.encodedPath) {
                "/cz/rss/toppodcasts/limit=50/genre=1318/json" -> json(
                    """{"feed":{"entry":[{"id":{"attributes":{"im:id":"2"}}},{"id":{"attributes":{"im:id":"1"}}},{"id":{"attributes":{"im:id":"3"}}}]}}""",
                )
                "/lookup" -> {
                    assertEquals("2,1,3", request.url.queryParameter("id"))
                    assertEquals("cz", request.url.queryParameter("country"))
                    json(
                        """{"results":[
                        {"collectionId":1,"collectionName":"One","artistName":"A","feedUrl":"https://one.example.com/feed","artworkUrl600":"https://img.example.com/1.jpg","trackCount":12,"primaryGenreName":"Technology"},
                        {"collectionId":2,"collectionName":"Two","feedUrl":"https://two.example.com/feed"},
                        {"collectionId":3,"collectionName":"No feed"}]}""",
                    )
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.use {
            val page = catalogue.browse(CatalogueCategory("technology"))
            assertEquals("the chart's order; a show without a feed is left out", listOf("Two", "One"), page.shows.map { it.title })
            assertEquals(ShowSearchResult("One", "https://one.example.com/feed", "A", "https://img.example.com/1.jpg", 12, "Technology"), page.shows[1])
            assertNull("one page of 50", page.next)
            assertTrue(catalogue.browse(CatalogueCategory("technology"), cursor = "x").shows.isEmpty())
            assertEquals(19, catalogue.categories().size)
            assertNull("Apple can't tell which shows are alike", catalogue.similar("https://one.example.com/feed"))
        }
    }

    @Test
    fun `Apple's search drops results without a feed and repeats`() = runBlocking {
        val (server, catalogue) = apple { request ->
            assertEquals("/search", request.url.encodedPath)
            assertEquals("radio wave", request.url.queryParameter("term"))
            assertEquals("podcast", request.url.queryParameter("media"))
            json(
                """{"results":[{"collectionName":"A","feedUrl":"https://a.example.com/f"},{"collectionName":"B"},{"collectionName":"A again","feedUrl":"https://a.example.com/f"}]}""",
            )
        }
        server.use { assertEquals(listOf("A"), catalogue.search("  radio wave ").map { it.title }) }
    }

    @Test
    fun `a failing directory is a network error`() = runBlocking {
        val (server, catalogue) = apple { MockResponse.Builder().code(503).build() }
        server.use {
            try {
                catalogue.search("x")
                throw AssertionError("expected a failure")
            } catch (_: SourceException.Network) {
            }
        }
    }

    @Test
    fun `feed links from other apps become https addresses`() {
        assertEquals("https://example.com/feed.xml", FeedAddress.normalized("feed://example.com/feed.xml"))
        assertEquals("https://example.com/feed.xml", FeedAddress.normalized("podcast://example.com/feed.xml"))
        assertEquals("https://example.com/feed.xml", FeedAddress.normalized("pcast://example.com/feed.xml"))
        assertEquals("https://example.com/feed.xml", FeedAddress.normalized("feed:https://example.com/feed.xml"))
        assertEquals("https://example.com/feed.xml", FeedAddress.normalized(" example.com/feed.xml "))
        assertEquals("http://example.com/rss", FeedAddress.normalized("http://example.com/rss"))
        assertNull(FeedAddress.normalized(""))
        assertNull(FeedAddress.normalized("not a feed"))
    }

    @Test
    fun `chapters are sorted, end at the next one and stop at the end of the episode`() {
        val marks = listOf(
            EpisodeChapters.Mark(600_000, null, "Two"),
            EpisodeChapters.Mark(0, 30_000, "Intro"),
            EpisodeChapters.Mark(4_000_000, null, "Past the end"),
        )
        val closed = EpisodeChapters.closed(marks, durationMs = 1_800_000)
        assertEquals(listOf("Intro", "Two"), closed.map { it.title })
        assertEquals(listOf(30_000L, 1_800_000L), closed.map { it.endMs })
        val unknown = EpisodeChapters.closed(marks.take(2), durationMs = 0)
        assertEquals("unknown length: the last runs an hour", 600_000L + EpisodeChapters.UNKNOWN_END_MS, unknown.last().endMs)
    }

    @Test
    fun `positions go to the source once a minute while playing, at once otherwise`() {
        val throttle = PositionThrottle()
        assertTrue(throttle.isDue(periodic = true, id = "a", played = false, nowMs = 0))
        assertTrue(!throttle.isDue(periodic = true, id = "a", played = false, nowMs = 10_000))
        assertTrue("a pause is sent at once", throttle.isDue(periodic = false, id = "a", played = false, nowMs = 20_000))
        assertTrue(!throttle.isDue(periodic = true, id = "a", played = false, nowMs = 70_000))
        assertTrue(throttle.isDue(periodic = true, id = "a", played = false, nowMs = 80_000))
        assertTrue("another episode at once", throttle.isDue(periodic = true, id = "b", played = false, nowMs = 81_000))
        assertTrue("played at once", throttle.isDue(periodic = true, id = "b", played = true, nowMs = 82_000))
    }

    @Test
    fun `the last played position is stored when the source missed it`() {
        val at = Instant.parse("2026-11-21T10:00:00Z")
        val last = LastPlayed("phone", Episode(id = "e", showId = "s", title = "E", firstSeenAt = at), positionMs = 90_000, at = at)
        assertEquals("phone/s|e", last.id)
        assertEquals(ListeningProgress(90_000, false, at), last.missedBy(null))
        assertEquals(ListeningProgress(90_000, false, at), last.missedBy(ListeningProgress(30_000, false, at.minusSeconds(60))))
        assertNull(last.missedBy(ListeningProgress(30_000, false, at.plusSeconds(1))))
        assertNull(last.missedBy(ListeningProgress(90_000, false, at.minusSeconds(60))))
    }
}
