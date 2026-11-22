package audio.own.podcast.library

import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.TokenSession
import audio.own.kit.audiobookshelf.Tokens
import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedEnclosure
import audio.own.kit.feed.FeedEpisode
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedShow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

private const val FEED_URL = "https://pub.example/feed.xml"

private fun at(day: Int) = LocalDate.of(2026, 10, day).atTime(8, 0).toInstant(ZoneOffset.UTC)

/**
 * A server with one podcast library and one show of two episodes on the server (ep1 in MP3, ep2
 * in WebM), whose feed has a third the server never downloaded.
 */
private class AbsServer(progress: Map<String, Triple<Double, Boolean, Long>> = emptyMap()) {
    val patches = CopyOnWriteArrayList<String>()
    var playBody: String? = null

    private val episodes = """
        [{"id":"ep1","libraryItemId":"show1","title":"On the server one","publishedAt":1791000000000,"duration":600,"guid":"g1","enclosure":{"url":"https://pub.example/1.mp3"},"audioFile":{"ino":"101","mimeType":"audio/mpeg","duration":600},"chapters":[{"id":0,"start":0,"end":300,"title":"A"},{"id":1,"start":300,"end":600,"title":"B"}]},
         {"id":"ep2","libraryItemId":"show1","title":"On the server two","publishedAt":1792000000000,"duration":1200,"audioFile":{"ino":"102","mimeType":"audio/webm","duration":1200},"chapters":[]}]
    """.trimIndent()

    private fun progressJson(id: String, value: Triple<Double, Boolean, Long>) =
        """{"id":"p-$id","libraryItemId":"show1","episodeId":"$id","duration":600,"progress":0.1,"currentTime":${value.first},"isFinished":${value.second},"hideFromContinueListening":false,"lastUpdate":${value.third}}"""

    val stub = ServerStub { request ->
        val path = request.url.encodedPath
        when {
            request.method == "GET" && path == "/api/libraries" -> json(
                """{"libraries":[{"id":"lib1","name":"Podcasts","mediaType":"podcast","folders":[{"id":"f1","fullPath":"/podcasts"}]},{"id":"lib2","name":"Books","mediaType":"book"}]}""",
            )
            request.method == "GET" && path == "/api/libraries/lib1/items" -> json(
                """{"results":[{"id":"show1","libraryId":"lib1","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"Show &amp; I&#39;m","feedUrl":"$FEED_URL","type":"episodic"},"coverPath":"/c.jpg","numEpisodes":2}}],"total":1,"limit":200,"page":0}""",
            )
            request.method == "GET" && path == "/api/items/show1" -> json(
                """{"id":"show1","libraryId":"lib1","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"Show","feedUrl":"$FEED_URL"},"coverPath":"/c.jpg","episodes":$episodes}}""",
            )
            request.method == "GET" && path == "/api/libraries/lib1/recent-episodes" -> json("""{"episodes":$episodes,"limit":100,"page":0}""")
            request.method == "GET" && path == "/api/me" -> json(
                """{"id":"u1","username":"t","type":"user","mediaProgress":[${progress.map { (k, v) -> progressJson(k, v) }.joinToString(",")}]}""",
            )
            request.method == "GET" && path.startsWith("/api/me/progress/show1/") -> {
                val id = path.substringAfterLast('/')
                progress[id]?.let { json(progressJson(id, it)) } ?: status(404)
            }
            request.method == "PATCH" -> {
                patches += "$path ${request.text()}"
                json("OK", type = "text/plain")
            }
            request.method == "POST" && path == "/api/items/show1/play/ep2" -> {
                playBody = request.text()
                json("""{"id":"s1","libraryItemId":"show1","episodeId":"ep2","playMethod":2,"audioTracks":[{"contentUrl":"/hls/s1/output.m3u8","mimeType":"application/vnd.apple.mpegurl"}]}""")
            }
            else -> status(404)
        }
    }

    val host: HttpUrl get() = stub.url.toHttpUrl()
}

/** The show's feed, as a publisher serves it; [extra] adds episodes the feed gained since. */
private class FeedServer {
    val reads = CopyOnWriteArrayList<String>()
    var extra: List<FeedEpisode> = emptyList()

    val loader = FeedLoader { url, _ ->
        reads += url
        FeedFetcher.Result.Fetched(
            FeedDocument(
                FeedShow(title = "Show"),
                listOf(
                    FeedEpisode(id = "g1", title = "On the server one", enclosure = FeedEnclosure("https://pub.example/1.mp3")),
                    FeedEpisode(id = "other-guid", title = "On the server two", enclosure = FeedEnclosure("https://pub.example/2.mp3")),
                    FeedEpisode(id = "g3", title = "Only in the feed", publishedAt = at(12), enclosure = FeedEnclosure("https://pub.example/3.mp3")),
                    FeedEpisode(id = "post", title = "A post without audio"),
                ) + extra,
            ),
            FeedFetcher.Validators(etag = "\"1\""),
            url,
        )
    }
}

class AudiobookshelfPodcastSourceTest {
    private val servers = mutableListOf<AbsServer>()
    private val clock = TestClock()

    @After
    fun close() = servers.forEach { it.stub.close() }

    private fun server(progress: Map<String, Triple<Double, Boolean, Long>> = emptyMap()) = AbsServer(progress).also { servers += it }

    private fun client(host: HttpUrl) = AudiobookshelfClient(
        host = host,
        tokens = TokenSession(Tokens("token", null, Tokens.NEVER)),
        http = AudiobookshelfHttp(OkHttpClient(), userAgent = "own.audio Podcasts/0-test (JVM)"),
    )

    private fun source(
        server: AbsServer,
        feeds: FeedServer = FeedServer(),
        canAddShows: Boolean = false,
        canDecode: (String?) -> Boolean = PlayableAudio::isPlayable,
        awaited: MutableList<HttpUrl> = mutableListOf(),
    ) = AudiobookshelfPodcastSource(
        client = client(server.host),
        store = ListeningStore(InMemoryValueStore(), now = { clock.time }),
        feeds = feeds.loader,
        canAddShows = canAddShows,
        canDecode = canDecode,
        now = { clock.time },
        awaitPlaylist = { awaited += it },
    )

    @Test
    fun `a show lists the server's episodes and the feed's others, matched by GUID, enclosure and title`() = runBlocking {
        val abs = source(server())
        val shows = abs.shows()
        assertEquals("HTML entities in the server's titles are decoded", listOf("Show & I'm"), shows.map { it.title })
        assertEquals("/api/items/show1/cover", shows[0].imageUrl!!.toHttpUrl().encodedPath)
        assertEquals("lib1", shows[0].collection)
        val episodes = abs.episodes("show1")
        assertEquals("g1 matches by GUID, two by title; only g3 is the feed's own", setOf("ep1", "ep2", "feed:g3"), episodes.map { it.id }.toSet())
        val one = episodes.first { it.id == "ep1" }
        assertEquals(600_000L, one.durationMs)
        assertEquals(listOf("A", "B"), one.chapters.map { it.title })
        assertEquals(300_000L, one.chapters[1].startMs)
        assertEquals("https://pub.example/3.mp3", episodes.first { it.id == "feed:g3" }.audioUrl)
    }

    @Test
    fun `playing - the server's copy with a token, a feed-only episode from the publisher`() = runBlocking {
        val server = server()
        val abs = source(server)
        abs.episodes("show1")
        val file = abs.playback(EpisodeKey("show1", "ep1"))
        assertTrue(file.isServerFile)
        assertFalse(file.isHls)
        assertEquals("${server.host}api/items/show1/file/101?token=token", file.url)
        val feed = abs.playback(EpisodeKey("show1", "feed:g3"))
        assertFalse(feed.isServerFile)
        assertEquals("https://pub.example/3.mp3", feed.url)
    }

    @Test
    fun `WebM plays as the file on Android, and through the server's HLS only when the device can't decode it`() = runBlocking {
        val server = server()
        val direct = source(server).playback(EpisodeKey("show1", "ep2"))
        assertTrue("Media3 reads WebM itself", direct.url.contains("/api/items/show1/file/102?token=token") && !direct.isHls)
        assertEquals(null, server.playBody)

        val awaited = mutableListOf<HttpUrl>()
        val strict = source(server, canDecode = { it != "audio/webm" && PlayableAudio.isPlayable(it) }, awaited = awaited)
        val hls = strict.playback(EpisodeKey("show1", "ep2"))
        assertTrue(hls.isHls && hls.isTranscode && hls.isServerFile)
        assertEquals("${server.host}hls/s1/output.m3u8?token=token", hls.url)
        assertEquals("the playlist is waited for before playing", listOf(hls.url), awaited.map { it.toString() })
        val supported = Json.parseToJsonElement(server.playBody!!).jsonObject["supportedMimeTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the session says what the device plays", "audio/mpeg" in supported && "audio/webm" !in supported)
    }

    @Test
    fun `waiting for a playlist - asked again until it and its first segment answer`() = runBlocking {
        var asked = 0
        val hls = ServerStub { request ->
            when (request.url.encodedPath) {
                "/hls/output.m3u8" -> if (++asked < 3) status(404) else json("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\noutput-0.ts\n", type = "application/vnd.apple.mpegurl")
                "/hls/output-0.ts" -> json("ts", type = "video/mp2t")
                else -> status(404)
            }
        }
        hls.use {
            AudiobookshelfPodcastSource.waitForPlaylist(OkHttpClient(), "${hls.url}hls/output.m3u8".toHttpUrl(), pauseMs = 10)
            assertEquals(3, hls.count("GET /hls/output.m3u8"))
            assertEquals(1, hls.count("GET /hls/output-0.ts"))
        }
    }

    @Test
    fun `New Episodes from the recent list - the newest of a show seen for the first time`() = runBlocking {
        val abs = source(server())
        assertEquals("a show seen for the first time brings its newest, not its archive", listOf("ep2"), abs.inbox(10).map { it.id })
        abs.dismissFromInbox(listOf(EpisodeKey("show1", "ep2")))
        assertTrue(abs.inbox(10).isEmpty())
    }

    @Test
    fun `New Episodes takes the feed-only episodes too, once the feed gains one`() = runBlocking {
        val feeds = FeedServer()
        val abs = source(server(), feeds)
        abs.episodes("show1")
        assertEquals("first sight: the newest only", listOf("ep2"), abs.inbox(10).map { it.id })
        clock.advance(3_600)
        feeds.extra = listOf(FeedEpisode(id = "g4", title = "New in the feed", publishedAt = at(27), enclosure = FeedEnclosure("https://pub.example/4.mp3")))
        val fresh = abs.refresh(showId = null, force = true)
        assertEquals("the refresh returns what is new", listOf("feed:g4"), fresh.map { it.id })
        assertEquals(listOf("feed:g4", "ep2"), abs.inbox(10).map { it.id })
        abs.markPlayed(EpisodeKey("show1", "feed:g4"), true)
        assertEquals(listOf("ep2"), abs.inbox(10).map { it.id })
    }

    @Test
    fun `positions - the newer of device and server, feed-only ones on the device, a server-side finish counts`() = runBlocking {
        val server = server(mapOf("ep1" to Triple(120.0, false, 1_792_900_000_000), "ep2" to Triple(1199.0, true, 1_792_900_000_000)))
        val abs = source(server)
        val key = EpisodeKey("show1", "ep1")
        assertEquals("nothing on the device: the server's", 120_000L, abs.progress(key)?.positionMs)
        val newer = ListeningProgress(200_000, false, Instant.ofEpochSecond(1_792_950_000))
        assertEquals(newer, abs.saveProgress(newer, key))
        assertEquals("the device's is newer now", 200_000L, abs.progress(key)?.positionMs)
        val patch = server.patches.single()
        assertTrue(patch, patch.startsWith("/api/me/progress/show1/ep1 "))
        val body = Json.parseToJsonElement(patch.substringAfter(' ')).jsonObject
        assertEquals("seconds on the wire", 200.0, body["currentTime"]!!.jsonPrimitive.double, 0.0)
        assertEquals(600.0, body["duration"]!!.jsonPrimitive.double, 0.0)
        assertFalse(body["isFinished"]!!.jsonPrimitive.boolean)

        val older = ListeningProgress(10_000, false, Instant.ofEpochSecond(1_792_000_000))
        assertEquals("an older report does not move it back", newer, abs.saveProgress(older, key))
        assertEquals(1, server.patches.size)

        assertEquals(true, abs.allProgress()[EpisodeKey("show1", "ep2")]?.played)
        val feedKey = EpisodeKey("show1", "feed:g3")
        abs.saveProgress(ListeningProgress(5_000, false, clock.time), feedKey)
        assertEquals(5_000L, abs.progress(feedKey)?.positionMs)
        assertEquals("a feed-only position never goes to the server", 1, server.patches.size)
    }

    @Test
    fun `an automatic refresh keeps a feed read in the last ten minutes, one the listener asks for reads it again`() = runBlocking {
        val feeds = FeedServer()
        val abs = source(server(), feeds)
        abs.episodes("show1")
        assertEquals(1, feeds.reads.size)
        abs.refresh(showId = null, force = false)
        assertEquals("read a moment ago: not asked again", 1, feeds.reads.size)
        abs.refresh(showId = null, force = true)
        assertEquals("a pull to refresh asks the feed", 2, feeds.reads.size)
        clock.advance(11 * 60)
        abs.refresh(showId = null, force = false)
        assertEquals("ten minutes on, an automatic refresh asks too", 3, feeds.reads.size)
    }

    @Test
    fun `an account that may not upload cannot add shows, and nobody removes them from a player`() = runBlocking {
        val abs = source(server())
        assertFalse(Capability.ADD_BY_URL in abs.capabilities)
        assertFalse(Capability.UNFOLLOW in abs.capabilities)
        assertThrows<SourceException.Unsupported> { abs.follow("https://x.example/rss") }
        assertThrows<SourceException.Unsupported> { abs.unfollow("show1") }
        assertTrue(Capability.ADD_BY_URL in source(server(), canAddShows = true).capabilities)
    }

    @Test
    fun `the libraries are listed in order, and a show is added into the one chosen`() = runBlocking {
        var sent = ""
        val stub = ServerStub { request ->
            when (request.method to request.url.encodedPath) {
                "GET" to "/api/libraries" -> json(
                    """{"libraries":[{"id":"lib3","name":"YouTube","mediaType":"podcast","displayOrder":2,"folders":[{"id":"f3","fullPath":"/youtube"}]},{"id":"lib1","name":"Podcasts","mediaType":"podcast","displayOrder":1,"folders":[{"id":"f1","fullPath":"/podcasts"}]}]}""",
                )
                "GET" to "/api/libraries/lib1/items", "GET" to "/api/libraries/lib3/items" -> json("""{"results":[],"total":0,"limit":200,"page":0}""")
                "POST" to "/api/podcasts" -> {
                    sent = request.text()
                    json("""{"id":"new1","libraryId":"lib3","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"New Show","feedUrl":"$FEED_URL"},"episodes":[]}}""")
                }
                else -> status(404)
            }
        }
        stub.use {
            val abs = AudiobookshelfPodcastSource(
                client = client(stub.url.toHttpUrl()),
                store = ListeningStore(InMemoryValueStore()),
                feeds = FeedServer().loader,
                canAddShows = true,
            )
            assertEquals("in the server's order", listOf("Podcasts", "YouTube"), abs.podcastLibraries().map { it.name })
            val show = abs.follow(FEED_URL, libraryId = "lib3")
            assertEquals("lib3", show.collection)
            val body = Json.parseToJsonElement(sent).jsonObject
            assertEquals("lib3", body["libraryId"]!!.jsonPrimitive.content)
            assertEquals("into the chosen library's folder", "f3", body["folderId"]!!.jsonPrimitive.content)
            assertEquals("/youtube/Show", body["path"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a failing feed leaves the server's episodes as they are`() = runBlocking {
        val abs = AudiobookshelfPodcastSource(
            client = client(server().host),
            store = ListeningStore(InMemoryValueStore()),
            feeds = FeedLoader { _, _ -> throw java.io.IOException("down") },
            canAddShows = false,
        )
        assertEquals(setOf("ep1", "ep2"), abs.episodes("show1").map { it.id }.toSet())
    }

    @Test
    fun `a file link lives as long as its token, and a refused one is renewed once`() = runBlocking {
        val expires = 1_900_000_000_000L
        val tokens = TokenSession(Tokens("old", "r1", expires))
        val absClient = AudiobookshelfClient(server().host, tokens, AudiobookshelfHttp(OkHttpClient(), userAgent = "test"))
        val abs = AudiobookshelfPodcastSource(absClient, ListeningStore(InMemoryValueStore()), FeedServer().loader, canAddShows = false)
        val playback = abs.playback(EpisodeKey("show1", "ep1"))
        assertTrue(playback.url.contains("token=old"))
        assertEquals(Instant.ofEpochMilli(expires), playback.expiresAt)

        var refreshes = 0
        val renewing = ServerStub { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshes++
                json("""{"user":{"id":"u1","username":"t","accessToken":"fresh","refreshToken":"r2"}}""")
            } else {
                status(404)
            }
        }
        renewing.use {
            val session = TokenSession(Tokens("old", "r1", expires))
            val other = AudiobookshelfClient(renewing.url.toHttpUrl(), session, AudiobookshelfHttp(OkHttpClient(), userAgent = "test"))
            val source = AudiobookshelfPodcastSource(other, ListeningStore(InMemoryValueStore()), FeedServer().loader, canAddShows = false)
            source.renewAfterRefusal("${renewing.url}api/items/show1/file/101?token=old")
            source.renewAfterRefusal("${renewing.url}api/items/show1/file/101?token=old")
            assertEquals("fresh", session.current?.accessToken)
            assertEquals("a second report of the same stale token is not a second refresh", 1, refreshes)
        }
    }

    @Test
    fun `a refused token is a sign-in to do again, a missing show is not found`() = runBlocking {
        val stub = ServerStub { request -> if (request.url.encodedPath == "/api/libraries") status(401) else status(404) }
        stub.use {
            val abs = AudiobookshelfPodcastSource(client(stub.url.toHttpUrl()), ListeningStore(InMemoryValueStore()), FeedServer().loader, canAddShows = false)
            assertThrows<SourceException.SignInRejected> { abs.shows() }
            assertThrows<SourceException.NotFound> { abs.episodes("nope") }
        }
    }
}

/** Fails unless [block] throws an [E]. */
internal suspend inline fun <reified E : Throwable> assertThrows(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) return
        throw AssertionError("expected ${E::class.simpleName}, got $e", e)
    }
    fail("expected ${E::class.simpleName}")
}
