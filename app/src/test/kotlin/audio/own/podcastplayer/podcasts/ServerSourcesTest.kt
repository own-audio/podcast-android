package audio.own.podcastplayer.podcasts

import android.app.Application
import android.util.Xml
import androidx.media3.common.util.UnstableApi
import audio.own.core.servers.NetworkState
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerLibraryRef
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedEnclosure
import audio.own.kit.feed.FeedEpisode
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedParser
import audio.own.kit.feed.FeedShow
import audio.own.kit.feed.XmlParserFactory
import audio.own.kit.gpodder.GPodderClient
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import audio.own.player.core.PlaybackLinkRequest
import audio.own.podcast.library.FeedLibrary
import audio.own.podcast.library.FeedLoader
import audio.own.podcast.library.LastPlayed
import audio.own.podcast.library.PendingProgress
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.UpNext
import audio.own.podcast.library.ValueStore
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomFeedLibraryStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcastplayer.R
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.MemoryPreferences
import audio.own.podcastplayer.app.PlaybackSettings
import audio.own.podcastplayer.app.ServerExtras
import audio.own.podcastplayer.app.UserFacingException
import audio.own.podcastplayer.FakeDownloader
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import audio.own.podcastplayer.servers.ConnectionState
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.ServersState
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val FEED = "https://feeds.example.com/show.xml"

/** One MockWebServer answering through [handler], every request kept. */
private class Stub(private val handler: (RecordedRequest) -> MockResponse) : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun count(method: String, path: String) = requests.count { it.method == method && it.url.encodedPath == path }

    override fun close() = server.close()
}

private fun json(text: String, code: Int = 200, type: String = "application/json") =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

private fun status(code: Int) = MockResponse.Builder().code(code).build()

/** own.audio with one show p1 (owned by u1) of one episode, and a signed link that expires. */
private fun ownAudio(visibility: String = "private") = Stub { request ->
    when (request.method to request.url.encodedPath) {
        "GET" to "/api/v2/sync/snapshot" -> json(
            listOf(
                """{"type":"begin","cursor":"c0"}""",
                """{"type":"record","kind":"podcast","id":"p1","record":{"id":"p1","title":"Server Show","feed_url":"https://pub.example/p1.xml","visibility":"$visibility","owner_id":"u1","created_at":"2026-10-01T00:00:00Z"}}""",
                """{"type":"record","kind":"episode","id":"e1","record":{"id":"e1","podcast_id":"p1","guid":"e1","title":"One","published_at":"2026-10-01T08:00:00Z","audio_url":"https://pub.example/e1.mp3"}}""",
                """{"type":"end","cursor":"c0"}""",
            ).joinToString("\n", postfix = "\n"),
            type = "application/x-ndjson",
        )
        "GET" to "/api/v2/sync/changes" -> json("""{"changes":[],"cursor":"c0","has_more":false,"reset":false}""")
        "POST" to "/api/v2/playback-info" -> json("""{"decision":"direct","url":"https://storage.example/e1.mp3?sig=1","expires_at":"2026-11-21T11:00:00Z","reasons":[]}""")
        "PUT" to "/api/v2/podcasts/p1/visibility" -> json(request.body?.utf8() ?: "{}")
        "PATCH" to "/api/v2/me/settings" -> json("""{"playback":{"speed":1.5}}""")
        "GET" to "/api/v2/me/podcasts/unplayed" -> json("""{"items":[]}""")
        "PUT" to "/api/v2/me/progress/episode/e1" -> {
            val position = Regex("\"position_ms\":(\\d+)").find(request.body?.utf8().orEmpty())?.groupValues?.get(1) ?: "0"
            val at = Regex("\"at\":\"([^\"]+)\"").find(request.body?.utf8().orEmpty())?.groupValues?.get(1) ?: "2026-11-21T10:00:00Z"
            json("""{"progress":{"kind":"episode","id":"e1","position_ms":$position,"finished":false,"updated_at":"$at"},"applied":true}""")
        }
        else -> status(404)
    }
}

/** Audiobookshelf with two podcast libraries, one show in each. */
private fun shelf() = Stub { request ->
    val path = request.url.encodedPath
    when {
        path == "/api/libraries" -> json(
            """{"libraries":[{"id":"lib1","name":"Podcasts","mediaType":"podcast","displayOrder":1,"folders":[{"id":"f1","fullPath":"/podcasts"}]},{"id":"lib2","name":"Talks","mediaType":"podcast","displayOrder":2,"folders":[{"id":"f2","fullPath":"/talks"}]}]}""",
        )
        path == "/api/libraries/lib1/items" -> json("""{"results":[{"id":"show1","libraryId":"lib1","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"Shelf Show"},"numEpisodes":1}}],"total":1,"limit":200,"page":0}""")
        path == "/api/libraries/lib2/items" -> json("""{"results":[{"id":"show2","libraryId":"lib2","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"Talk Show"},"numEpisodes":0}}],"total":1,"limit":200,"page":0}""")
        path.endsWith("/recent-episodes") -> json("""{"episodes":[],"limit":100,"page":0}""")
        path == "/api/items/show1" -> json(
            """{"id":"show1","libraryId":"lib1","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"Shelf Show"},"episodes":[{"id":"ep1","libraryItemId":"show1","title":"Shelf One","duration":600,"audioFile":{"ino":"101","mimeType":"audio/mpeg","duration":600},"chapters":[]}]}}""",
        )
        path == "/api/me" -> json("""{"id":"u1","username":"t","type":"user","mediaProgress":[]}""")
        path == "/auth/refresh" -> json("""{"user":{"id":"u1","username":"t","accessToken":"fresh","refreshToken":"r2"}}""")
        else -> status(404)
    }
}

/** A gPodder server keeping subscriptions per device: the uploads it got are kept. */
private class GPodder : AutoCloseable {
    val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val stub = Stub { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/2/subscriptions/alex.json" -> status(404)
            path.startsWith("/api/2/subscriptions/alex/") && request.method == "GET" -> json("""{"add":[],"remove":[],"timestamp":10}""")
            path.startsWith("/api/2/subscriptions/alex/") -> {
                uploads += request.body?.utf8().orEmpty()
                json("""{"timestamp":11,"update_urls":[]}""")
            }
            path == "/api/2/episodes/alex.json" && request.method == "GET" -> json("""{"actions":[],"timestamp":10}""")
            path == "/api/2/episodes/alex.json" -> json("""{"timestamp":11,"update_urls":[]}""")
            else -> status(404)
        }
    }

    override fun close() = stub.close()
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServerSourcesTest {
    private val main = mainThread()
    private val db = PodcastDatabase.inMemory(RuntimeEnvironment.getApplication())
    private var time = Instant.parse("2026-11-21T10:00:00Z")
    private val stubs = mutableListOf<AutoCloseable>()
    private val preferences = MemoryPreferences()
    private val network = MutableStateFlow(NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi"))
    private val downloads = FakeDownloader()
    private val synced = mutableListOf<String>()
    private val libraries = mutableMapOf<String, List<ServerLibraryRef>>()
    private val absExpiry = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli()
    private val absTokens = audio.own.kit.audiobookshelf.TokenSession(audio.own.kit.audiobookshelf.Tokens("tok", "r1", absExpiry), clock = { time.toEpochMilli() })

    private fun episode(id: String, hoursAgo: Long) =
        FeedEpisode(id = id, title = "Feed $id", publishedAt = time.minusSeconds(3600 * hoursAgo), durationMs = 600_000, enclosure = FeedEnclosure("https://cdn.example.com/$id.mp3", "audio/mpeg", 100))

    private var document = FeedDocument(show = FeedShow(title = "Feed Show"), episodes = listOf(episode("f1", 1)))

    @After
    fun close() {
        stubs.forEach { it.close() }
        db.close()
        main.close()
    }

    private fun <T : AutoCloseable> T.kept(): T = also { stubs += it }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(main) { block() } }

    private val oaId = UUID.randomUUID().toString()
    private val absId = UUID.randomUUID().toString()
    private val gpId = UUID.randomUUID().toString()

    private fun ownAudioRecord(url: String, userId: String = "u1") = ServerRecord(
        id = oaId, kind = ServerKinds.OWN_AUDIO, displayName = "Home", host = url,
        auth = ServerAuth.OwnAudio("a@example.com", StoredTokens("a", "r", null)), userId = userId,
    )

    private fun absRecord(url: String) = ServerRecord(
        id = absId, kind = ServerKinds.AUDIOBOOKSHELF, displayName = "Shelf", host = url,
        auth = ServerAuth.Audiobookshelf("t", StoredTokens("tok", null, null)),
        libraries = listOf(ServerLibraryRef("lib1", "Podcasts"), ServerLibraryRef("lib2", "Talks")),
    )

    private fun gpodderRecord(url: String) = ServerRecord(
        id = gpId, kind = ServerKinds.GPODDER, displayName = "Sync", host = url, auth = ServerAuth.GPodder("alex", "pw", "own-audio-podcasts-01234567"),
    )

    /** A new client per address, as the servers model makes them. */
    private fun ownAudioClient(state: ServersState, id: String, clients: MutableMap<String, OwnAudioClient>, http: OkHttpClient): OwnAudioClient? {
        val record = state.server(id)?.takeIf { it.kind == ServerKinds.OWN_AUDIO } ?: return null
        val url = (state.connections[id] as? ConnectionState.Connected)?.url ?: record.host
        return clients.getOrPut(url) { OwnAudioClient(url, http, "test", TokenSession(Tokens("a", "r", Instant.now().plusSeconds(3600)))) }
    }

    private fun model(servers: MutableStateFlow<ServersState>, engine: FakeEngine = FakeEngine()): PodcastModel {
        val http = OkHttpClient()
        val upNext = UpNext(RoomValueStore(db, "up-next", ListSerializer(UpNext.Entry.serializer())))
        val phone = FeedLibrary(RoomFeedLibraryStore(db), FeedLoader { _, _ -> FeedFetcher.Result.Fetched(document, FeedFetcher.Validators(), FEED) }, now = { time })
        val clients = HashMap<String, OwnAudioClient>()
        return PodcastModel(
            db = db,
            phone = phone,
            upNext = upNext,
            pending = PendingProgress(RoomValueStore(db, "pending", ListSerializer(PendingProgress.Entry.serializer()))),
            lastPlayed = object : ValueStore<LastPlayed> {
                override suspend fun load(): LastPlayed? = null
                override suspend fun save(value: LastPlayed?) = Unit
            },
            player = engine,
            servers = servers,
            client = { id -> ownAudioClient(servers.value, id, clients, http) },
            preferences = preferences,
            network = network,
            offlineMode = { false },
            http = http,
            feeds = FeedFetcher(http, FeedParser(XmlParserFactory { Xml.newPullParser() })),
            isFullyCached = { false },
            strings = object : PodcastModel.Strings {
                override val thisDevice = "This Device"
                override fun chapter(number: Int) = "Chapter $number"
            },
            log = AppLog(null, echo = false),
            scope = CoroutineScope(SupervisorJob() + main),
            now = { time },
            country = "us",
            accounts = object : ServerAccounts {
                override fun audiobookshelf(serverId: String): AudiobookshelfClient? {
                    val record = servers.value.server(serverId)?.takeIf { it.kind == ServerKinds.AUDIOBOOKSHELF } ?: return null
                    return AudiobookshelfClient(record.host.toHttpUrl(), absTokens, AudiobookshelfHttp(http, "test"))
                }

                override fun gpodder(serverId: String): GPodderClient? {
                    val record = servers.value.server(serverId)?.takeIf { it.kind == ServerKinds.GPODDER } ?: return null
                    return GPodderClient(record.host, "alex", "pw", http)
                }

                override fun address(serverId: String): String? =
                    (servers.value.connections[serverId] as? ConnectionState.Connected)?.url ?: servers.value.server(serverId)?.host

                override suspend fun noteSynced(serverId: String, atEpochMs: Long) {
                    synced += serverId
                }

                override suspend fun updateLibraries(serverId: String, libraries: List<ServerLibraryRef>) {
                    this@ServerSourcesTest.libraries[serverId] = libraries
                }
            },
            downloads = downloads,

            syncDelayMs = 50,
        )
    }

    @Test
    fun `an own_audio source is made once, however the address settles, and takes one snapshot`() {
        val server = ownAudio().kept()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url))))
        val model = model(state)
        onMain { model.load() }
        val ref = SourceRef.Server(UUID.fromString(oaId))
        val first = model.source(ref)
        assertNotNull(first)
        state.update { it.copy(connections = mapOf(oaId to ConnectionState.Checking)) }
        state.update { it.copy(connections = mapOf(oaId to ConnectionState.Connected(server.url)), modules = mapOf(oaId to setOf("podcasts.discovery"))) }
        runBlocking { eventually { model.state.value.shows.any { it.show.title == "Server Show" } } }
        onMain { model.reloadLists() }
        assertSame("the same source, given the new client", first, model.source(ref))
        assertEquals("one snapshot for the first sync", 1, server.count("GET", "/api/v2/sync/snapshot"))
    }

    @Test
    fun `an own_audio link carries its expiry to the player`() {
        val server = ownAudio().kept()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url)))))
        onMain { model.load() }
        val link = onMain { model.linkResolver.resolve(PlaybackLinkRequest("$oaId/p1|e1")) }
        assertEquals("https://storage.example/e1.mp3?sig=1", link.url)
        assertEquals(Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(), link.expiresAtMs)
    }

    @Test
    fun `only the show's owner may share it with the family`() {
        val server = ownAudio().kept()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url))))
        val model = model(state)
        onMain { model.load() }
        runBlocking { eventually { model.state.value.shows.isNotEmpty() } }
        val show = model.state.value.shows.first { it.source != SourceRef.Phone }
        assertEquals(FamilySharing(canChange = true, shared = false), onMain { model.familySharing(show) })
        var held: Boolean? = null
        model.setSharedWithFamily(show, true) { held = it }
        runBlocking { eventually { held == true } }
        assertEquals("""{"visibility":"family"}""", server.requests.last { it.method == "PUT" }.body?.utf8())

        state.update { it.copy(servers = listOf(ownAudioRecord(server.url, userId = "u2"))) }
        assertFalse("another member of the family can't", onMain { model.familySharing(show) }!!.canChange)
    }

    @Test
    fun `Audiobookshelf shows list by library, a library turned off leaves the lists and the switch`() {
        val server = shelf().kept()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(absRecord(server.url))))
        val model = model(state)
        onMain { model.load() }
        runBlocking { eventually { model.state.value.shows.size == 2 } }
        val ids = model.state.value.sources.map { it.ref.id }
        assertEquals("the server, then each of its libraries", listOf("phone", absId, "$absId#lib1", "$absId#lib2"), ids)
        runBlocking { eventually { libraries[absId] != null } }
        assertEquals(listOf("Podcasts", "Talks"), libraries[absId]!!.map { it.name })

        model.setActiveSource("$absId#lib2")
        state.update { it.copy(extras = mapOf(absId to ServerExtras(hiddenLibraries = setOf("lib2")))) }
        runBlocking { eventually { model.state.value.shows.size == 1 } }
        assertEquals("Shelf Show", model.state.value.shows.single().show.title)
        assertEquals("one library left: no entry of its own", listOf("phone", absId), model.state.value.sources.map { it.ref.id })
        assertEquals("the switch falls back to the server", absId, model.state.value.activeSource)
    }

    @Test
    fun `an Audiobookshelf episode plays from its file with the token, and a refusal renews it once`() {
        val server = shelf().kept()
        val engine = FakeEngine()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(absRecord(server.url)))), engine)
        onMain { model.load() }
        val id = "$absId/show1|ep1"
        val link = onMain { model.linkResolver.resolve(PlaybackLinkRequest(id)) }
        assertEquals("${server.url}/api/items/show1/file/101?token=tok", link.url)
        assertEquals("the link lives as long as its token", absExpiry, link.expiresAtMs)

        engine.listener!!.onLinkFailed(id, "https://elsewhere.example/a.mp3", 404)
        onMain { model.linkResolver.resolve(PlaybackLinkRequest(id)) }
        assertEquals("only a 401 renews the token", 0, server.count("POST", "/auth/refresh"))

        // The player asks once more right after telling: that link carries a new token.
        engine.listener!!.onLinkFailed(id, link.url, 401)
        val renewed = onMain { model.linkResolver.resolve(PlaybackLinkRequest(id)) }
        assertEquals(1, server.count("POST", "/auth/refresh"))
        assertTrue(renewed.url.endsWith("token=fresh"))
        onMain { model.linkResolver.resolve(PlaybackLinkRequest(id)) }
        assertEquals("renewed once, not at every link", 1, server.count("POST", "/auth/refresh"))
    }

    @Test
    fun `a position left paused is put once, not again each time the app goes to the background`() {
        val server = ownAudio().kept()
        val engine = FakeEngine()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url)))), engine)
        onMain { model.load() }
        runBlocking { eventually { model.state.value.shows.any { it.show.title == "Server Show" } } }
        val item = SourcedEpisode(SourceRef.Server(UUID.fromString(oaId)), audio.own.podcast.library.Episode(id = "e1", showId = "p1", title = "One"))
        onMain { model.play(item, startAt = 0) }
        engine.report(PodcastPlayer.Reason.PAUSE, 60_000, time)
        val put = "/api/v2/me/progress/episode/e1"
        runBlocking { eventually { server.count("PUT", put) == 1 } }
        val pending = PendingProgress(RoomValueStore(db, "pending", ListSerializer(PendingProgress.Entry.serializer())))
        // Cleared once the server answered: nothing is left for a refresh to send again.
        runBlocking { eventually { runBlocking { pending.all() }.isEmpty() } }

        repeat(3) {
            onMain {
                model.setForeground(true)
                model.setForeground(false)
                model.refresh(asked = true)
            }
        }
        Thread.sleep(300)
        assertEquals("the same position, not sent again at each trip to the background or refresh", 1, server.count("PUT", put))

        // Moved on since (held back by the once-a-minute throttle): sent at the next trip.
        time = time.plusSeconds(10)
        engine.report(PodcastPlayer.Reason.PERIODIC, 70_000, time)
        onMain {
            model.setForeground(true)
            model.setForeground(false)
        }
        runBlocking { eventually { server.count("PUT", put) == 2 } }
    }

    @Test
    fun `a removed server's downloads leave with it`() {
        val server = ownAudio().kept()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url))))
        val model = model(state)
        onMain { model.load() }
        state.update { it.copy(servers = emptyList()) }
        runBlocking { eventually { "removeAll $oaId" in downloads.calls } }
    }

    @Test
    fun `a gPodder account syncs on refresh and shortly after a follow on the device`() {

        val gpodder = GPodder().kept()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(gpodderRecord(gpodder.stub.url)))))
        onMain { model.load() }
        runBlocking { eventually { synced.size >= 1 } }
        val before = synced.size
        onMain { model.follow(FEED) }
        runBlocking { eventually { gpodder.uploads.any { FEED in it } } }
        runBlocking { eventually { synced.size > before } }
        assertTrue("the account now names the device's show", model.state.value.accountFeeds[gpId].orEmpty().contains(FEED))
        model.setActiveSource(gpId)
        assertEquals("its switch entry is the device's shows on the account", listOf("Feed Show"), model.state.value.visibleShows.map { it.show.title })
    }

    @Test
    fun `Sync Now reads why it failed`() {
        val refusing = Stub { status(409) }.kept()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(gpodderRecord(refusing.url)))))
        onMain { model.load() }
        try {
            onMain { model.syncNow(gpId) }
            fail("expected the admin message")
        } catch (e: UserFacingException) {
            assertEquals(R.string.error_podfetch_admin, e.text.id)
        }
    }

    @Test
    fun `the playback settings decide the speed, the skips, playing on and mobile data`() {
        val server = ownAudio().kept()
        val engine = FakeEngine()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url)))), engine)
        runBlocking { preferences.setPlayback(PlaybackSettings(skipBackSecs = 10, skipForwardSecs = 45, keepAheadMinutes = 15)) }
        onMain { model.load() }
        assertEquals(10, engine.state.value.skip.backSecs)
        assertEquals(45, engine.state.value.skip.forwardSecs)
        assertEquals(15 * 60_000L, engine.keepAheadMs)
        network.value = network.value.copy(dataSaver = true, signature = "saver")
        runBlocking { eventually { engine.keepAheadMs == 5 * 60_000L } }

        model.setPlaybackSettings(model.state.value.settings.copy(defaultRate = 1.5f, smartRewind = false))
        runBlocking { eventually { server.count("PATCH", "/api/v2/me/settings") == 1 } }
        assertEquals("""{"playback":{"speed":1.5}}""", server.requests.last { it.method == "PATCH" }.body?.utf8())
        assertFalse(engine.smartRewind.isEnabled)

        onMain { model.follow(FEED) }
        val item = model.state.value.inbox.first { it.source == SourceRef.Phone }
        onMain { model.play(item) }
        assertEquals("the default speed, the show having none", 1.5f, engine.loads.last().rate)

        model.setPlaybackSettings(model.state.value.settings.copy(mobileData = PlaybackSettings.MobileData.DOWNLOADED_ONLY))
        network.value = network.value.copy(metered = true, signature = "cell")
        val loads = engine.loads.size
        onMain { model.play(item) }
        assertEquals("nothing streams on mobile data", loads, engine.loads.size)
        assertEquals(UiTextOf(R.string.error_mobile_data), model.state.value.error)
    }

    @Test
    fun `without Play New Episodes After Up Next, nothing plays on`() {
        val engine = FakeEngine()
        val model = model(MutableStateFlow(ServersState(loaded = true)), engine)
        runBlocking { preferences.setPlayback(PlaybackSettings(continueWithNewEpisodes = false)) }
        onMain { model.load() }
        document = document.copy(episodes = listOf(episode("f1", 1)))
        onMain { model.follow(FEED) }
        time = time.plusSeconds(3600)
        document = document.copy(episodes = listOf(episode("f1", 1), episode("f2", 0)))
        onMain { model.refresh(asked = true) }
        assertEquals(2, model.state.value.inbox.size)
        onMain { model.play(model.state.value.inbox.first()) }
        engine.finish(time)
        Thread.sleep(300)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun `Summary and Ask show for own_audio episodes with a transcript on a server with summaries, nowhere else`() {
        val server = ownAudio().kept()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url)), modules = mapOf(oaId to setOf("summaries"))))
        val model = model(state)
        onMain { model.load() }
        val ref = SourceRef.Server(UUID.fromString(oaId))
        val episode = audio.own.podcast.library.Episode(id = "e1", showId = "p1", title = "One")
        val transcript = listOf(audio.own.podcast.library.Transcript("https://pub.example/e1.vtt", "text/vtt"))
        assertNotNull(model.episodeAI(SourcedEpisode(ref, episode.copy(transcripts = transcript))))
        assertEquals("no transcript, nothing to read", null, model.episodeAI(SourcedEpisode(ref, episode)))
        assertEquals("the device's feeds have no summaries", null, model.episodeAI(SourcedEpisode(SourceRef.Phone, episode.copy(transcripts = transcript))))
        state.update { it.copy(modules = mapOf(oaId to setOf("podcast_discovery"))) }
        runBlocking { eventually { model.episodeAI(SourcedEpisode(ref, episode.copy(transcripts = transcript))) == null } }
        assertTrue("reading whether it is offered asks the server nothing", server.requests.none { it.url.encodedPath.startsWith("/api/v2/summaries") })
    }

    // region Misuse (MisuseTests.swift in spirit; the feed cases are in MisuseTest)

    @Test
    fun `misuse - the server is removed while its episode plays - the player lets go, the device's shows still play`() {
        val server = ownAudio().kept()
        val engine = FakeEngine()
        val state = MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url))))
        val model = model(state, engine)
        onMain { model.load() }
        runBlocking { eventually { model.state.value.shows.any { it.show.title == "Server Show" } } }
        val item = SourcedEpisode(SourceRef.Server(UUID.fromString(oaId)), audio.own.podcast.library.Episode(id = "e1", showId = "p1", title = "One"))
        onMain { model.play(item, startAt = 0) }
        onMain { model.playLater(item) }
        assertEquals(item.id, engine.state.value.item?.id)

        state.update { it.copy(servers = emptyList()) }
        runBlocking { eventually { engine.state.value.item == null } }
        runBlocking { eventually { model.state.value.upNext.isEmpty() && model.state.value.shows.none { it.show.title == "Server Show" } } }
        // A late report of the gone episode goes nowhere and breaks nothing.
        model.player.listener?.onPosition(PodcastPlayer.Report(item.id, 5_000, 0, PodcastPlayer.Reason.PAUSE, time))
        Thread.sleep(100)
        assertEquals(0, server.count("PUT", "/api/v2/me/progress/episode/e1"))

        onMain { model.follow(FEED) }
        val device = model.state.value.inbox.first { it.source == SourceRef.Phone }
        onMain { model.play(device) }
        assertEquals(device.id, engine.state.value.item?.id)
    }

    @Test
    fun `misuse - a gPodder server that is gone - refresh still works, Sync Now says why`() {
        val dead = Stub { status(200) }
        val url = dead.url
        dead.close()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(gpodderRecord(url)))))
        onMain { model.load() }
        val started = System.nanoTime()
        onMain { model.follow(FEED) }
        onMain { model.refresh(asked = true) }
        assertTrue("not stuck on the dead server", java.time.Duration.ofNanos(System.nanoTime() - started) < java.time.Duration.ofSeconds(60))
        assertEquals(listOf("Feed Show"), model.state.value.shows.map { it.show.title })
        try {
            onMain { model.syncNow(gpId) }
            fail("a dead server can't sync")
        } catch (_: UserFacingException) {
        }
        assertTrue("nothing left marked as syncing", model.state.value.syncing.isEmpty())
    }

    @Test
    fun `misuse - offline for a day - the position waits on the device and reaches own_audio with its own time`() {
        val server = ownAudio().kept()
        val engine = FakeEngine()
        val model = model(MutableStateFlow(ServersState(loaded = true, servers = listOf(ownAudioRecord(server.url)))), engine)
        onMain { model.load() }
        runBlocking { eventually { model.state.value.shows.any { it.show.title == "Server Show" } } }
        val item = SourcedEpisode(SourceRef.Server(UUID.fromString(oaId)), audio.own.podcast.library.Episode(id = "e1", showId = "p1", title = "One"))
        onMain { model.play(item, startAt = 0) }
        network.update { it.copy(connected = false) }
        runBlocking { eventually { model.state.value.isOffline } }
        val pausedAt = time
        engine.report(PodcastPlayer.Reason.PAUSE, 120_000, pausedAt)
        time = time.plus(java.time.Duration.ofDays(1))
        onMain { model.refresh(asked = true) }
        Thread.sleep(200)
        val put = "/api/v2/me/progress/episode/e1"
        assertEquals("nothing sent while offline", 0, server.count("PUT", put))
        val pending = PendingProgress(RoomValueStore(db, "pending", ListSerializer(PendingProgress.Entry.serializer())))
        runBlocking { eventually { runBlocking { pending.all() }.isNotEmpty() } }

        network.update { it.copy(connected = true) }
        runBlocking { eventually(10_000) { server.count("PUT", put) == 1 } }
        val body = server.requests.last { it.url.encodedPath == put }.body?.utf8().orEmpty()
        assertTrue(body, "\"position_ms\":120000" in body)
        assertTrue("the time it was heard, not the time it was sent: $body", pausedAt.toString().removeSuffix("Z") in body)
        runBlocking { eventually { runBlocking { pending.all() }.isEmpty() } }
    }

    // endregion
}

private fun UiTextOf(id: Int) = audio.own.podcastplayer.app.UiText(id)
