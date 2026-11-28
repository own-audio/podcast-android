package audio.own.podcast.library

import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedEnclosure
import audio.own.kit.feed.FeedEpisode
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedShow
import audio.own.kit.gpodder.GPodderClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** A gPodder account kept in memory, served as the API serves it. */
private class FakeAccount : Dispatcher() {
    private var clock = 100L
    private val subscriptions = mutableListOf<Triple<String, Boolean, Long>>()
    private val actions = mutableListOf<Pair<JsonObject, Long>>()

    /** Seconds' worth of log rows handed out again: a server answering with an overlap. */
    @Volatile var overlap = 0L

    /** A read-only account (a public demo): uploads answer 403. */
    @Volatile var readOnly = false

    /**
     * Subscriptions kept per device, as gpodder.net and podfetch keep them: the device's own list
     * is empty, and only the account-wide list has what other apps added.
     */
    @Volatile var perDevice = false

    /** The size of every episode-action upload. */
    val uploadBatches = mutableListOf<Int>()

    @Synchronized fun serverAdds(feed: String) {
        subscriptions += Triple(feed, true, ++clock)
    }

    @Synchronized fun serverRemoves(feed: String) {
        subscriptions += Triple(feed, false, ++clock)
    }

    @Synchronized fun serverPlays(action: JsonObject) {
        actions += action to ++clock
    }

    val feeds: Set<String>
        @Synchronized get() {
            val out = mutableSetOf<String>()
            for ((feed, added) in subscriptions) if (added) out += feed else out -= feed
            return out
        }

    val uploadedActions: List<JsonObject> @Synchronized get() = actions.map { it.first }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val since = url.queryParameter("since")?.toLong() ?: 0
        val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let(Json::parseToJsonElement)
        if (readOnly && request.method == "POST") return status(403)
        return when (request.method to url.encodedPath) {
            "GET" to "/api/2/subscriptions/jo/phone.json" -> if (perDevice) reply(changes(emptyMap())) else reply(changes(since))
            "GET" to "/api/2/subscriptions/jo.json" -> if (perDevice) reply(changes(since)) else status(404)
            "POST" to "/api/2/subscriptions/jo/phone.json" -> {
                clock += 1
                body!!.jsonObject["add"]?.jsonArray?.forEach { subscriptions += Triple(it.jsonPrimitive.content, true, clock) }
                body.jsonObject["remove"]?.jsonArray?.forEach { subscriptions += Triple(it.jsonPrimitive.content, false, clock) }
                reply(buildJsonObject { put("timestamp", clock); putJsonArray("update_urls") {} })
            }
            "GET" to "/api/2/episodes/jo.json" -> reply(
                buildJsonObject {
                    put("actions", JsonArray(actions.filter { it.second > since }.map { it.first }))
                    put("timestamp", clock)
                },
            )
            "POST" to "/api/2/episodes/jo.json" -> {
                clock += 1
                val uploaded = body!!.jsonArray.map { it.jsonObject }
                uploadBatches += uploaded.size
                uploaded.forEach { actions += it to clock }
                reply(buildJsonObject { put("timestamp", clock); putJsonArray("update_urls") {} })
            }
            else -> status(404)
        }
    }

    private fun changes(since: Long): JsonObject {
        val last = linkedMapOf<String, Boolean>()
        for ((feed, added, at) in subscriptions) if (at > since - overlap) last[feed] = added
        return changes(last)
    }

    private fun changes(last: Map<String, Boolean>) = buildJsonObject {
        put("add", JsonArray(last.filterValues { it }.keys.sorted().map(::JsonPrimitive)))
        put("remove", JsonArray(last.filterValues { !it }.keys.sorted().map(::JsonPrimitive)))
        put("timestamp", clock)
    }

    private fun reply(body: JsonElement) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()
}

private fun feed(id: String) = "https://feeds.example.com/$id.xml"

/** Every feed has one ten-minute episode `<id>-1`; the feed `big` has 450. */
private val feeds = FeedLoader { url, _ ->
    val id = url.substringAfterLast('/').removeSuffix(".xml")
    val episodes = (1..if (id == "big") 450 else 1).map { n ->
        FeedEpisode(
            id = "$id-$n",
            title = "Episode $n",
            publishedAt = Instant.parse("2026-10-01T08:00:00Z").plusSeconds(n.toLong()),
            durationMs = 600_000,
            enclosure = FeedEnclosure("https://cdn.example.com/$id-$n.mp3", "audio/mpeg", 1),
        )
    }
    FeedFetcher.Result.Fetched(FeedDocument(FeedShow(title = "Show $id"), episodes), FeedFetcher.Validators(), url)
}

private fun play(vararg fields: Pair<String, Any>) = JsonObject(
    fields.associate { (name, value) -> name to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString()) },
)

class GPodderSyncTest {
    private val server = MockWebServer()
    private val account = FakeAccount()
    private val library = FeedLibrary(InMemoryFeedLibraryStore(), feeds)
    private val state = InMemoryValueStore.Box<GPodderSyncState>()

    @Before fun start() {
        server.dispatcher = account
        server.start()
    }

    @After fun stop() = server.close()

    /** A new object over the same stored state each time, as after a restart. */
    private fun sync() = GPodderSync(GPodderClient(server.url("/").toString(), "jo", "pw", OkHttpClient()), library, "phone", InMemoryValueStore(state))

    private suspend fun localFeeds() = library.shows().mapNotNull { it.feedUrl }.toSet()

    @Test
    fun `subscriptions go both ways - the first sync merges and removes nothing`() = runTest {
        account.serverAdds(feed("a"))
        library.follow(feed("b"))

        val first = sync().sync()
        assertEquals(1, first.followed)
        assertEquals(1, first.uploadedShows)
        assertEquals(setOf(feed("a"), feed("b")), localFeeds())
        assertEquals(setOf(feed("a"), feed("b")), account.feeds)

        // Removed on the device goes away on the account; removed on the account goes away here.
        library.unfollow(library.shows().first { it.feedUrl == feed("b") }.id)
        account.serverRemoves(feed("a"))
        account.serverAdds(feed("c"))
        val second = sync().sync()
        assertEquals(1, second.unfollowed)
        assertEquals(1, second.followed)
        assertEquals(setOf(feed("c")), localFeeds())
        assertEquals(setOf(feed("c")), account.feeds)
        assertEquals(setOf(feed("c")), sync().accountFeeds())
    }

    @Test
    fun `a show unfollowed on the phone stays unfollowed when the server repeats its add`() = runTest {
        account.overlap = 10
        library.follow(feed("a"))
        sync().sync()
        assertEquals(setOf(feed("a")), account.feeds)
        library.unfollow(library.shows()[0].id)
        val report = sync().sync()
        assertEquals("the repeated add does not bring it back", 0, report.followed)
        assertTrue(library.shows().isEmpty())
        assertTrue("and the removal reaches the account", account.feeds.isEmpty())
    }

    @Test
    fun `a read-only account still brings its shows down - refused uploads don't stop the sync`() = runTest {
        account.serverAdds(feed("a"))
        account.readOnly = true
        val b = library.follow(feed("b"))
        library.record(60_000, EpisodeKey(b.id, "b-1"))
        val report = sync().sync()
        assertEquals(1, report.followed)
        assertEquals(setOf(feed("a"), feed("b")), localFeeds())
        assertEquals("nothing went up", setOf(feed("a")), account.feeds)
        assertEquals(0, report.positionsSent)
    }

    @Test
    fun `a server with per-device lists - a show added in another app reaches the phone`() = runTest {
        account.perDevice = true
        account.serverAdds(feed("a"))
        val report = sync().sync()
        assertEquals(1, report.followed)
        assertEquals(setOf(feed("a")), localFeeds())
    }

    @Test
    fun `positions - the newer side wins, and what came from the account is not sent back`() = runTest {
        account.serverAdds(feed("a"))
        sync().sync()
        val key = EpisodeKey(library.shows()[0].id, "a-1")

        account.serverPlays(play("podcast" to feed("a"), "episode" to "https://cdn.example.com/a-1.mp3", "action" to "play", "timestamp" to "2026-10-17T08:00:00", "started" to 0, "position" to 120, "total" to 600, "device" to "laptop"))
        val pulled = sync().sync()
        assertEquals(1, pulled.positionsApplied)
        assertEquals(0, pulled.positionsSent)
        assertEquals(120_000L, library.progress(key)?.positionMs)

        // Listened on the phone later: that goes up, as the API's play action.
        library.record(590_000, key, at = Instant.parse("2026-10-17T09:00:00Z"))
        val pushed = sync().sync()
        assertEquals(1, pushed.positionsSent)
        val sent = account.uploadedActions.last { it["device"]?.jsonPrimitive?.content == "phone" }
        assertEquals("play", sent["action"]!!.jsonPrimitive.content)
        assertEquals(600, sent["position"]!!.jsonPrimitive.int)
        assertEquals(600, sent["total"]!!.jsonPrimitive.int)
        assertEquals("a-1", sent["guid"]!!.jsonPrimitive.content)
        assertEquals("2026-10-17T09:00:00", sent["timestamp"]!!.jsonPrimitive.content)
        assertEquals(true, library.progress(key)?.played)

        // An older report from another device does not move it back.
        account.serverPlays(play("podcast" to feed("a"), "episode" to "https://cdn.example.com/a-1.mp3", "action" to "play", "timestamp" to "2026-10-17T08:30:00", "position" to 10, "total" to 600))
        val stale = sync().sync()
        assertEquals(0, stale.positionsApplied)
        assertEquals(true, library.progress(key)?.played)
    }

    @Test
    fun `this phone's own actions are skipped unless clearly newer than what it holds`() = runTest {
        account.serverAdds(feed("a"))
        sync().sync()
        val key = EpisodeKey(library.shows()[0].id, "a-1")
        library.record(100_000, key, at = Instant.parse("2026-10-17T09:00:00.400Z"))
        sync().sync()

        // Under a second newer: the same report, rounded by the server.
        account.serverPlays(play("podcast" to feed("a"), "episode" to "https://cdn.example.com/a-1.mp3", "action" to "play", "timestamp" to "2026-10-17T09:00:01", "position" to 200, "total" to 600, "device" to "phone"))
        assertEquals(0, sync().sync().positionsApplied)
        assertEquals(100_000L, library.progress(key)?.positionMs)

        // podfetch updates its row in place under the device that created it: another app's newer position.
        account.serverPlays(play("podcast" to feed("a"), "episode" to "https://cdn.example.com/a-1.mp3", "action" to "play", "timestamp" to "2026-10-17T09:00:05", "position" to 300, "total" to 600, "device" to "phone"))
        assertEquals(1, sync().sync().positionsApplied)
        assertEquals(300_000L, library.progress(key)?.positionMs)
    }

    @Test
    fun `episodes are matched by guid when the enclosure URL differs, and new marks them unplayed`() = runTest {
        account.serverAdds(feed("a"))
        sync().sync()
        val key = EpisodeKey(library.shows()[0].id, "a-1")
        library.markPlayed(key, true)
        sync().sync()
        account.serverPlays(play("podcast" to feed("a"), "episode" to "https://tracker.example/a-1.mp3", "guid" to "a-1", "action" to "new", "timestamp" to "2099-01-01T00:00:00", "device" to "laptop"))
        assertEquals(1, sync().sync().positionsApplied)
        assertEquals(false, library.progress(key)?.played)
    }

    @Test
    fun `positions go up in batches of 200`() = runTest {
        account.serverAdds(feed("big"))
        sync().sync()
        val show = library.shows()[0]
        for (n in 1..450) library.record(60_000, EpisodeKey(show.id, "big-$n"))
        val report = sync().sync()
        assertEquals(450, report.positionsSent)
        assertEquals(listOf(200, 200, 50), account.uploadBatches)
        assertEquals("nothing is sent twice", 0, sync().sync().positionsSent)
    }
    // Found on the emulator: a feed that answers each subscriber with a personal address, or one
    // that redirects, came down from the account as a second copy of a show already followed.

    private val fetched = mutableListOf<String>()

    /** `personal?u=…` is one show whatever the token; `old/r.xml` redirects to `r.xml`; `x/` and `y/` are one show by title and website. */
    private val sameShowLibrary = FeedLibrary(InMemoryFeedLibraryStore(), FeedLoader { url, _ ->
        fetched += url
        val episode = FeedEpisode(id = "e-1", title = "Episode 1", publishedAt = Instant.parse("2026-10-01T08:00:00Z"), durationMs = 600_000, enclosure = FeedEnclosure("https://cdn.example.com/e-1.mp3", "audio/mpeg", 1))
        val (show, final) = when {
            "personal" in url -> FeedShow(title = "Members Show", podcastGuid = "9b0c1f2e-guid") to url
            url == "http://old.example.com/r.xml" -> FeedShow(title = "Moved Show") to "https://feeds.example.com/r.xml"
            "/x/" in url || "/y/" in url -> FeedShow(title = "Twin", link = "https://twin.example.com/") to url
            else -> FeedShow(title = "Other") to url
        }
        FeedFetcher.Result.Fetched(FeedDocument(show, listOf(episode)), FeedFetcher.Validators(), final)
    })

    private fun sameShowSync() = GPodderSync(GPodderClient(server.url("/").toString(), "jo", "pw", OkHttpClient()), sameShowLibrary, "phone", InMemoryValueStore(state))

    @Test
    fun `a personal feed address pulled from the account is the show already followed, by podcast guid`() = runTest {
        sameShowLibrary.follow("https://members.example.com/personal?u=phone")
        account.serverAdds("https://members.example.com/personal?u=laptop")
        val first = sameShowSync().sync()
        assertEquals(0, first.followed)
        assertEquals(1, sameShowLibrary.shows().size)

        fetched.clear()
        val second = sameShowSync().sync()
        assertEquals(0, second.followed)
        assertEquals(1, sameShowLibrary.shows().size)
        assertTrue("the account's address is not fetched again: $fetched", fetched.isEmpty())
    }

    @Test
    fun `a pulled address that redirects to a followed feed is that show`() = runTest {
        sameShowLibrary.follow("https://feeds.example.com/r.xml")
        account.serverAdds("http://old.example.com/r.xml")
        assertEquals(0, sameShowSync().sync().followed)
        assertEquals(listOf("https://feeds.example.com/r.xml"), sameShowLibrary.shows().map { it.feedUrl })
    }

    @Test
    fun `a pulled address with the same title and website is that show`() = runTest {
        sameShowLibrary.follow("https://a.example.com/x/feed.xml")
        account.serverAdds("https://b.example.com/y/feed.xml")
        assertEquals(0, sameShowSync().sync().followed)
        assertEquals(1, sameShowLibrary.shows().size)
    }

    @Test
    fun `unfollowing a show the account knows by another address removes that address too`() = runTest {
        sameShowLibrary.follow("https://members.example.com/personal?u=phone")
        account.serverAdds("https://members.example.com/personal?u=laptop")
        sameShowSync().sync()
        sameShowLibrary.unfollow(sameShowLibrary.shows().single().id)
        sameShowSync().sync()
        assertTrue(sameShowLibrary.shows().isEmpty())
        assertTrue("${account.feeds}", account.feeds.none { "personal" in it })
    }
}

