package audio.own.podcast.library

import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

private fun podcastJson(id: String, title: String = "Show $id", visibility: String = "private") =
    """{"id":"$id","title":"$title","feed_url":"https://pub.example/$id.xml","description":"<p>About <b>it</b></p>","visibility":"$visibility","owner_id":"u1","created_at":"2026-10-01T00:00:00Z"}"""

private fun episodeJson(id: String, podcast: String, day: Int) =
    """{"id":"$id","podcast_id":"$podcast","guid":"$id","title":"Episode $id &amp; more","published_at":"2026-10-${"%02d".format(day)}T08:00:00Z","audio_url":"https://pub.example/$id.mp3"}"""

private fun progressJson(id: String, positionMs: Long, finished: Boolean, at: String) =
    """{"kind":"episode","id":"$id","position_ms":$positionMs,"finished":$finished,"updated_at":"$at"}"""

private fun change(kind: String, id: String, record: String?) =
    if (record == null) """{"kind":"$kind","id":"$id","op":"delete"}""" else """{"kind":"$kind","id":"$id","op":"upsert","record":$record}"""

private fun changes(vararg items: String, cursor: String, hasMore: Boolean = false, reset: Boolean = false) =
    """{"changes":[${items.joinToString(",")}],"cursor":"$cursor","has_more":$hasMore,"reset":$reset}"""

private fun problem(code: String, status: Int) =
    json("""{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"}""", status, "application/problem+json")

/**
 * An own.audio server with podcasts: a snapshot of what [records] holds, then the change-feed
 * answers queued in [changeAnswers] (an empty page once they run out). Other routes answer
 * through [routes] first.
 */
private class OwnAudioServer {
    /** `kind` to `id` to record, in snapshot order. */
    val records = mutableListOf<Triple<String, String, String>>()
    val changeAnswers = ConcurrentLinkedQueue<MockResponse>()
    var routes: (RecordedRequest) -> MockResponse? = { null }

    /** The next snapshot stops after this many records, without its `end` line. */
    var cutAfter: Int? = null
    var snapshots = 0

    val stub = ServerStub { request -> routes(request) ?: route(request) }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.removePrefix("/api/v2")
        return when {
            path == "/sync/snapshot" -> {
                snapshots++
                val after = request.url.queryParameter("after")
                val start = after?.let { a -> records.indexOfFirst { "${it.first}:${it.second}" == a } + 1 } ?: 0
                val cut = cutAfter
                cutAfter = null
                val lines = mutableListOf("""{"type":"begin","cursor":"c0"}""")
                val sent = records.drop(start).let { if (cut != null) it.take(cut) else it }
                sent.forEach { (kind, id, record) -> lines += """{"type":"record","kind":"$kind","id":"$id","record":$record}""" }
                if (cut == null) lines += """{"type":"end","cursor":"c0"}"""
                json(lines.joinToString("\n", postfix = "\n"), type = "application/x-ndjson")
            }
            path == "/sync/changes" -> changeAnswers.poll() ?: json(changes(cursor = request.url.queryParameter("cursor") ?: "c0"))
            else -> status(404)
        }
    }

    fun podcast(id: String, title: String = "Show $id", visibility: String = "private") = apply { records += Triple("podcast", id, podcastJson(id, title, visibility)) }

    fun episode(id: String, podcast: String, day: Int) = apply { records += Triple("episode", id, episodeJson(id, podcast, day)) }

    fun progress(id: String, positionMs: Long, finished: Boolean, at: String) =
        apply { records += Triple("progress.episode", id, progressJson(id, positionMs, finished, at)) }
}

class OwnAudioPodcastSourceTest {
    private val servers = mutableListOf<OwnAudioServer>()
    private val clock = TestClock()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val catalogueBox = InMemoryValueStore.Box<OwnAudioCatalogue>()
    private val listeningBox = InMemoryValueStore.Box<ListeningState>()

    @After
    fun close() = servers.forEach { it.stub.close() }

    private fun server() = OwnAudioServer().also { servers += it }

    /** Two shows: p1 with three episodes, p2 with one. */
    private fun library() = server()
        .podcast("p1", "Show &amp; Co").podcast("p2")
        .episode("e1", "p1", 1).episode("e2", "p1", 5).episode("e3", "p1", 3).episode("e4", "p2", 2)

    private fun source(server: OwnAudioServer, modules: Set<String> = emptySet()): OwnAudioPodcastSource {
        var keys = 0
        return OwnAudioPodcastSource(
            client = OwnAudioClient(
                baseUrl = server.stub.url,
                http = OkHttpClient(),
                userAgent = "own.audio Podcasts/0-test (JVM)",
                tokens = TokenSession(Tokens("a", "r", Instant.now().plusSeconds(3600))),
            ),
            store = ListeningStore(InMemoryValueStore(listeningBox), now = { clock.time }),
            catalogueStore = InMemoryValueStore(catalogueBox),
            modules = modules,
            now = { clock.time },
            newKey = { "key-${++keys}" },
            sleep = { sleeps += it },
        )
    }

    @Test
    fun `the catalogue comes from a snapshot and the change feed, never one request per show`() = runBlocking {
        val server = library().progress("e3", 5_000, false, "2026-10-20T00:00:00Z")
        val oa = source(server)
        val shows = oa.shows()
        assertEquals("entities decoded", listOf("Show & Co", "Show p2"), shows.map { it.title })
        assertEquals("description as text", "About it", shows[0].description)
        assertEquals(listOf("e2", "e3", "e1"), oa.episodes("p1").map { it.id })
        assertEquals("Episode e2 & more", oa.episodes("p1")[0].title)
        assertEquals("first sight: each show's newest", listOf("e2", "e4"), oa.inbox().map { it.id })
        assertEquals(5_000L, oa.allProgress()[EpisodeKey("p1", "e3")]?.positionMs)
        val calls = server.stub.calls
        assertEquals(1, server.snapshots)
        assertTrue("no per-show lists: $calls", calls.none { it.contains("/podcasts/") })
        val snapshot = server.stub.requests.first { it.url.encodedPath.endsWith("/sync/snapshot") }
        assertEquals("podcast,episode,progress", snapshot.url.queryParameter("kinds"))
        val changes = server.stub.requests.first { it.url.encodedPath.endsWith("/sync/changes") }
        assertEquals("c0", changes.url.queryParameter("cursor"))
        assertEquals("podcast,episode,progress.episode", changes.url.queryParameter("kinds"))
        assertEquals("one sync within ten minutes", 1, server.stub.count("GET /api/v2/sync/changes"))
    }

    @Test
    fun `an episode arriving through the change feed is new, and a refresh returns it`() = runBlocking {
        val server = library()
        val oa = source(server)
        oa.inbox()
        clock.advance(3_600)
        server.changeAnswers += json(changes(change("episode", "e5", episodeJson("e5", "p2", 9)), cursor = "c1"))
        val fresh = oa.refresh(showId = null, force = false)
        assertEquals(listOf("e5"), fresh.map { it.id })
        assertEquals(listOf("e5", "e2", "e4"), oa.inbox().map { it.id })
        assertTrue("an automatic refresh asks no feed to be read", server.stub.calls.none { it.endsWith("/refresh") })
        assertEquals("the cursor goes on from the last page", "c1", catalogueBox.value?.cursor)
    }

    @Test
    fun `finished on another device leaves New Episodes, a removed show leaves everything`() = runBlocking {
        val server = library()
        val oa = source(server)
        oa.inbox()
        clock.advance(3_600)
        server.changeAnswers += json(
            changes(change("progress.episode", "e2", progressJson("e2", 0, true, "2026-10-26T10:00:00Z")), change("podcast", "p2", null), cursor = "c1"),
        )
        oa.sync(force = true)
        assertEquals(emptyList<String>(), oa.inbox().map { it.id })
        assertEquals(listOf("Show & Co"), oa.shows().map { it.title })
        assertThrows<SourceException.NotFound> { oa.episodes("p2") }
        assertTrue("its episodes went with it", catalogueBox.value!!.episodes.values.none { it.podcastId == "p2" })
        assertNull("and what the device knew of it", listeningBox.value!!.showFirstSeen["p2"])
    }

    @Test
    fun `a reset or an expired cursor takes a new snapshot`() = runBlocking {
        val server = library()
        val oa = source(server)
        oa.shows()
        server.changeAnswers += json(changes(cursor = "x", reset = true))
        oa.sync(force = true)
        assertEquals(2, server.snapshots)
        server.changeAnswers += problem("sync_cursor_expired", 410)
        oa.sync(force = true)
        assertEquals(3, server.snapshots)
        assertEquals(2, oa.shows().size)
    }

    @Test
    fun `a snapshot cut short resumes after its last record, with the same cursor`() = runBlocking {
        val server = library()
        server.cutAfter = 2
        val oa = source(server)
        assertEquals(2, oa.shows().size)
        val second = server.stub.requests.filter { it.url.encodedPath.endsWith("/sync/snapshot") }[1]
        assertEquals("podcast:p2", second.url.queryParameter("after"))
        assertEquals("c0", second.url.queryParameter("cursor"))
        assertEquals(4, catalogueBox.value!!.episodes.size)
    }

    @Test
    fun `a failed sync leaves the catalogue as it was, and offline it still lists`() = runBlocking {
        val server = library()
        val oa = source(server)
        oa.shows()
        val before = catalogueBox.value
        clock.advance(3_600)
        server.changeAnswers += status(503)
        assertThrows<SourceException.Server> { oa.sync(force = true) }
        assertEquals(before, catalogueBox.value)
        server.stub.close()
        servers.clear()
        assertEquals("the device's copy when the server is gone", 2, oa.shows().size)
    }

    @Test
    fun `the catalogue outlives the process - a new source reads on from the cursor`() = runBlocking {
        val server = library()
        source(server).shows()
        clock.advance(3_600)
        val again = source(server)
        assertEquals(2, again.shows().size)
        assertEquals(1, server.snapshots)
        assertEquals(2, server.stub.count("GET /api/v2/sync/changes"))
    }

    @Test
    fun `follow - posted with an idempotency key, its episodes through the feed, only the newest new`() = runBlocking {
        val server = library()
        var followBody = ""
        var followKey: String? = null
        server.routes = { request ->
            if (request.method == "POST" && request.url.encodedPath == "/api/v2/podcasts") {
                followBody = request.text()
                followKey = request.headers["Idempotency-Key"]
                json(podcastJson("p3", "New"), 201)
            } else {
                null
            }
        }
        val oa = source(server)
        oa.shows()
        clock.advance(60)
        // The server is still reading the feed: the show first, its episodes a moment later.
        server.changeAnswers += json(changes(change("podcast", "p3", podcastJson("p3", "New")), cursor = "c1"))
        val show = oa.follow("https://pub.example/p3.xml")
        assertEquals("New", show.title)
        assertEquals("https://pub.example/p3.xml", Json.parseToJsonElement(followBody).jsonObject["feed_url"]!!.jsonPrimitive.content)
        assertEquals("key-1", followKey)
        assertTrue(oa.shows().any { it.id == "p3" })
        clock.advance(60)
        server.changeAnswers += json(changes(change("episode", "e6", episodeJson("e6", "p3", 4)), change("episode", "e7", episodeJson("e7", "p3", 8)), cursor = "c2"))
        oa.sync(force = true)
        assertEquals("its back catalogue is not news, its newest is", listOf("e7", "e2", "e4"), oa.inbox().map { it.id })
    }

    @Test
    fun `follows in a row are spaced to stay under the server's limit`() = runBlocking {
        val server = library()
        server.routes = { request ->
            if (request.method == "POST" && request.url.encodedPath == "/api/v2/podcasts") json(podcastJson("p9"), 200) else null
        }
        val oa = source(server)
        oa.follow("https://pub.example/a.xml")
        oa.follow("https://pub.example/b.xml")
        assertEquals(listOf(OwnAudioPodcastSource.FOLLOW_SPACING.toMillis()), sleeps.toList())
        val keys = server.stub.requests.filter { it.method == "POST" }.map { it.headers["Idempotency-Key"] }
        assertEquals("a key per follow", 2, keys.toSet().size)
    }

    @Test
    fun `a feed the server can't read is not a feed`() = runBlocking {
        val server = library()
        server.routes = { request -> if (request.method == "POST") problem("feed_unreadable", 422) else null }
        assertThrows<SourceException.NotAFeed> { source(server).follow("https://pub.example/nope") }
    }

    @Test
    fun `unfollow - deleted on the server and gone here at once, a show gone already is fine`() = runBlocking {
        val server = library()
        server.routes = { request ->
            when (request.method to request.url.encodedPath) {
                "DELETE" to "/api/v2/podcasts/p2" -> status(204)
                "DELETE" to "/api/v2/podcasts/p1" -> problem("not_found", 404)
                else -> null
            }
        }
        val oa = source(server)
        oa.inbox()
        oa.unfollow("p2")
        assertEquals(listOf("p1"), oa.shows().map { it.id })
        assertEquals(listOf("e2"), oa.inbox().map { it.id })
        oa.unfollow("p1")
        assertTrue(oa.shows().isEmpty())
    }

    @Test
    fun `refresh - the listener's asks the server to read the feeds, one show or all`() = runBlocking {
        val server = library()
        server.routes = { request -> if (request.url.encodedPath.endsWith("/refresh")) json("""{"queued":1}""", 202) else null }
        val oa = source(server)
        oa.refresh("p1", force = true)
        oa.refresh(null, force = true)
        assertEquals(1, server.stub.count("POST /api/v2/podcasts/p1/refresh"))
        assertEquals(1, server.stub.count("POST /api/v2/podcasts/refresh"))
        assertTrue(Capability.REFRESH_ON_SERVER in oa.capabilities && Capability.UNFOLLOW in oa.capabilities)
    }

    @Test
    fun `progress - sent with its time, and a newer one from another device wins`() = runBlocking {
        val server = library()
        var answer = """{"applied":true,"progress":${progressJson("e1", 61_000, false, "2026-10-26T10:00:00Z")}}"""
        var body = ""
        server.routes = { request ->
            if (request.method == "PUT" && request.url.encodedPath == "/api/v2/me/progress/episode/e1") {
                body = request.text()
                json(answer)
            } else {
                null
            }
        }
        val oa = source(server)
        oa.shows()
        val key = EpisodeKey("p1", "e1")
        val mine = ListeningProgress(61_000, false, Instant.parse("2026-10-26T10:00:00Z"))
        assertEquals(mine, oa.saveProgress(mine, key))
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(61_000L, sent["position_ms"]!!.jsonPrimitive.long)
        assertFalse(sent["finished"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-26T10:00:00Z", sent["at"]!!.jsonPrimitive.content)

        answer = """{"applied":false,"progress":${progressJson("e1", 900_000, false, "2026-10-26T11:00:00Z")}}"""
        val later = ListeningProgress(70_000, false, Instant.parse("2026-10-26T10:30:00Z"))
        val held = oa.saveProgress(later, key)
        assertEquals("the server's newer position", 900_000L, held.positionMs)
        assertEquals("and the device keeps it", 900_000L, oa.allProgress()[key]?.positionMs)
    }

    @Test
    fun `positions kept offline go in one push, each answered on its own`() = runBlocking {
        val server = library()
        var pushed = ""
        server.routes = { request ->
            if (request.url.encodedPath == "/api/v2/sync/push") {
                pushed = request.text()
                json("""[{"client_id":"e1@1000","status":"ok"},{"client_id":"e2@2000","status":"stale"}]""")
            } else {
                null
            }
        }
        val results = source(server).pushProgress(
            listOf(
                EpisodeKey("p1", "e2") to ListeningProgress(2, true, Instant.ofEpochMilli(2000)),
                EpisodeKey("p1", "e1") to ListeningProgress(1, false, Instant.ofEpochMilli(1000)),
            ),
        )
        assertEquals(mapOf(EpisodeKey("p1", "e1") to "ok", EpisodeKey("p1", "e2") to "stale"), results)
        val ops = Json.parseToJsonElement(pushed).jsonObject["changes"]!!.jsonArray
        assertEquals("oldest first", listOf("e1", "e2"), ops.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("episode", ops[0].jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `playing - the publisher's file or the server's signed copy, as the server decides`() = runBlocking {
        val server = library()
        var kindAsked = ""
        server.routes = { request ->
            if (request.url.encodedPath == "/api/v2/playback-info") {
                val body = Json.parseToJsonElement(request.text()).jsonObject
                kindAsked = body["kind"]!!.jsonPrimitive.content
                val reasons = if (body["id"]!!.jsonPrimitive.content == "e1") """["publisher_file"]""" else "[]"
                val url = if (body["id"]!!.jsonPrimitive.content == "e1") "https://pub.example/e1.mp3" else "https://media.example/s/e2?sig=x"
                json("""{"decision":"direct","url":"$url","expires_at":"2026-10-27T00:00:00Z","reasons":$reasons}""")
            } else {
                null
            }
        }
        val oa = source(server)
        val publisher = oa.playback(EpisodeKey("p1", "e1"))
        assertEquals("episode", kindAsked)
        assertFalse(publisher.isServerFile)
        assertEquals("https://pub.example/e1.mp3", publisher.url)
        val stored = oa.playback(EpisodeKey("p1", "e2"))
        assertTrue(stored.isServerFile && !stored.isTranscode)
        assertEquals("the signed link's expiry goes with it", java.time.Instant.parse("2026-10-27T00:00:00Z"), stored.expiresAt)
    }

    @Test
    fun `unfollowing is offered to the show's owner, and to a household admin for a shared show only`() = runBlocking {
        var role = "member"
        val server = server().podcast("p1").podcast("p2", visibility = "family")
        server.routes = { request ->
            when (request.method to request.url.encodedPath) {
                "GET" to "/api/v2/me" -> json("""{"id":"u2","email":"m@example.com","display_name":"M","family":{"id":"f1","role":"$role"}}""")
                else -> null
            }
        }
        val member = source(server)
        member.shows()
        assertTrue("the owner", member.mayUnfollow("p1", "u1"))
        assertFalse("a member, a private show", member.mayUnfollow("p1", "u2"))
        assertFalse("a member, a shared show: the server refuses", member.mayUnfollow("p2", "u2"))
        role = "admin"
        val admin = source(server)
        assertTrue("an admin, a shared show", admin.mayUnfollow("p2", "u2"))
        assertFalse("an admin, someone's private show", admin.mayUnfollow("p1", "u2"))
    }

    @Test
    fun `sharing with the family, and the unplayed counts`() = runBlocking {
        val server = library()
        server.routes = { request ->
            when (request.method to request.url.encodedPath) {
                "PUT" to "/api/v2/podcasts/p1/visibility" -> json(request.text())
                "GET" to "/api/v2/me/podcasts/unplayed" -> json("""{"items":[{"podcast_id":"p1","unplayed":3},{"podcast_id":"p2","unplayed":0}]}""")
                else -> null
            }
        }
        val oa = source(server)
        oa.shows()
        assertEquals("u1", oa.owner("p1"))
        assertEquals(null, oa.owner("nope"))
        assertFalse(oa.isSharedWithFamily("p1"))
        oa.setSharedWithFamily("p1", true)
        assertTrue(oa.isSharedWithFamily("p1"))
        assertEquals("""{"visibility":"family"}""", server.stub.requests.last { it.method == "PUT" }.text())
        assertEquals(mapOf("p1" to 3L, "p2" to 0L), oa.unplayedCounts())
    }

    @Test
    fun `the directory is there only with its modules, and a module the server turned off is unsupported`() = runBlocking {
        val server = library()
        server.routes = { request ->
            when (request.url.encodedPath) {
                "/api/v2/podcasts/directory/search" -> json("""{"items":[{"title":"Found","feed_url":"https://pub.example/f.xml","podcast_id":"p1"}]}""")
                "/api/v2/podcasts/directory/categories" -> problem("feature_unavailable", 501)
                else -> null
            }
        }
        assertNull(source(server).directory)
        assertFalse(Capability.SEARCH in source(server).capabilities)
        val searching = source(server, setOf(OwnAudioPodcastSource.MODULE_SEARCH))
        assertTrue(Capability.SEARCH in searching.capabilities)
        val directory = searching.directory!!
        val found = directory.search("fo")
        assertEquals("p1", found.single().podcastId)
        assertThrows<SourceException.Unsupported> { directory.categories() }
        assertEquals("not asked: the module is off", 0, server.stub.count("GET /api/v2/podcasts/directory/categories"))
        val browsing = source(server, setOf(OwnAudioPodcastSource.MODULE_DISCOVERY)).directory!!
        assertThrows<SourceException.Unsupported> { browsing.categories() }
        assertEquals(1, server.stub.count("GET /api/v2/podcasts/directory/categories"))
    }

    @Test
    fun `a library event reads the change feed`() = runBlocking {
        val server = library()
        server.routes = { request ->
            if (request.url.encodedPath == "/api/v2/events") {
                json("event: library\nid: 7\ndata: {}\n\n", type = "text/event-stream")
            } else {
                null
            }
        }
        val oa = source(server)
        oa.shows()
        val before = server.stub.count("GET /api/v2/sync/changes")
        val live = launch { oa.followLiveUpdates(debounceMs = 10, firstRetryMs = 10) }
        withTimeout(5_000) {
            while (server.stub.count("GET /api/v2/sync/changes") == before) delay(20)
        }
        live.cancel()
        val reconnect = server.stub.requests.filter { it.url.encodedPath == "/api/v2/events" }.getOrNull(1)
        if (reconnect != null) assertEquals("resumed from the last event", "7", reconnect.headers["Last-Event-ID"])
    }
}
