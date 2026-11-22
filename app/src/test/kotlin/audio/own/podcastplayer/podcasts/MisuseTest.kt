package audio.own.podcastplayer.podcasts

import android.app.Application
import android.net.Uri
import android.util.Xml
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.Downloads
import audio.own.core.downloads.DownloadsDatabase
import audio.own.core.servers.NetworkState
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedParser
import audio.own.kit.feed.XmlParserFactory
import audio.own.player.core.ProxyMemory
import audio.own.player.spoken.SpokenPlayer
import audio.own.podcast.library.FeedLibrary
import audio.own.podcast.library.FeedLoader
import audio.own.podcast.library.LastPlayed
import audio.own.podcast.library.PendingProgress
import audio.own.podcast.library.Playback
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceException
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.UpNext
import audio.own.podcast.library.ValueStore
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomFeedLibraryStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcastplayer.FakeDownloader
import audio.own.podcastplayer.FixtureFeedServer
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.MemoryPreferences
import audio.own.podcastplayer.downloads.EpisodeDownloads
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.servers.ServersState
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * podcast-ios's `UITests/MisuseTests.swift` in spirit, as model and Robolectric tests against the
 * fixture feed server: things a listener or the network isn't meant to do, done anyway. Nothing
 * here checks a feature; each checks the app is still usable after. The server cases (one
 * removed while its episode plays, a dead gPodder server) are in [ServerSourcesTest].
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MisuseTest {
    private val context = RuntimeEnvironment.getApplication()
    private val main = mainThread()
    private val db = PodcastDatabase.inMemory(context)
    private val fixtures = FixtureFeedServer()
    private var time = Instant.parse("2026-11-21T10:00:00Z")
    private val network = MutableStateFlow(NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi"))
    private val http = OkHttpClient()
    private val feeds = FeedFetcher(http, FeedParser(XmlParserFactory { Xml.newPullParser() }))
    private val closing = mutableListOf<() -> Unit>()

    @After
    fun close() {
        closing.forEach { it() }
        fixtures.close()
        db.close()
        main.close()
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(main) { block() } }

    private fun model(engine: FakeEngine = FakeEngine()): Pair<PodcastModel, FakeEngine> {
        val phone = FeedLibrary(RoomFeedLibraryStore(db), FeedLoader { url, validators -> feeds.fetch(url, validators) }, now = { time })
        val model = PodcastModel(
            db = db,
            phone = phone,
            upNext = UpNext(RoomValueStore(db, "up-next", ListSerializer(UpNext.Entry.serializer()))),
            pending = PendingProgress(RoomValueStore(db, "pending", ListSerializer(PendingProgress.Entry.serializer()))),
            lastPlayed = object : ValueStore<LastPlayed> {
                var value: LastPlayed? = null
                override suspend fun load() = value
                override suspend fun save(value: LastPlayed?) {
                    this.value = value
                }
            },
            player = engine,
            servers = MutableStateFlow(ServersState(loaded = true)),
            client = { null },
            preferences = MemoryPreferences(),
            network = network,
            offlineMode = { false },
            http = http,
            feeds = feeds,
            isFullyCached = { false },
            strings = object : PodcastModel.Strings {
                override val thisDevice = "This Device"
                override fun chapter(number: Int) = "Chapter $number"
            },
            log = AppLog(null, echo = false),
            scope = CoroutineScope(SupervisorJob() + main),
            now = { time },
            country = "us",
            downloads = FakeDownloader(),
        )
        return model to engine
    }

    @Test
    fun `the fixture feed plays end to end, and its chapters document arrives after Play`() {
        val (model, engine) = model()
        onMain { model.load() }
        onMain { model.follow(fixtures.feedUrl) }
        assertEquals(listOf("Fixture Episode 3"), model.state.value.inbox.map { it.episode.title })
        val item = model.state.value.inbox.first()
        onMain { model.play(item) }
        assertTrue("Play doesn't wait for the chapters", engine.loads.last().chapters.isEmpty())
        runBlocking { eventually(10_000) { engine.lateChapters.isNotEmpty() } }
        assertEquals(item.id, engine.lateChapters.last().first)
        assertEquals(listOf("Hello", "Counting"), engine.lateChapters.last().second.map { it.title })

        // Kept: the next Play has them at once, and doesn't fetch them again.
        onMain { model.play(item) }
        assertEquals(listOf("Hello", "Counting"), engine.loads.last().chapters.map { it.title })
        Thread.sleep(200)
        assertEquals(1, fixtures.paths().count { it == "/chapters.json" })
    }

    @Test
    fun `a feed address that answers a web page is refused, and the right one still works`() {
        val (model, _) = model()
        onMain { model.load() }
        try {
            onMain { model.follow(fixtures.htmlUrl) }
            fail("a web page is no feed")
        } catch (_: SourceException.NotAFeed) {
        }
        assertTrue("nothing followed", model.state.value.shows.isEmpty())
        onMain { model.follow(fixtures.feedUrl) }
        assertEquals(listOf("own.audio Test Fixtures"), model.state.value.shows.map { it.show.title })
    }

    @Test
    fun `a feed of 5,000 episodes is followed, listed and refreshed in reasonable time`() {
        val (model, _) = model()
        onMain { model.load() }
        val started = System.nanoTime()
        val show = onMain { model.follow(fixtures.bigFeedUrl(5_000)) }
        val episodes = onMain { model.episodes(show) }
        assertEquals(5_000, episodes.size)
        assertEquals("Archive episode 5000", episodes.maxBy { it.episode.publishedAt ?: Instant.MIN }.episode.title)
        assertEquals("only the newest reaches New Episodes", listOf("Archive episode 5000"), model.state.value.inbox.map { it.episode.title })
        onMain { model.refresh(asked = true) }
        val took = Duration.ofNanos(System.nanoTime() - started)
        // Generous for CI; on a laptop this is a second or two.
        assertTrue("took $took", took < Duration.ofSeconds(60))
        assertEquals(1, model.state.value.shows.size)
    }

    @Test
    fun `offline for a day, then back - nothing is asked meanwhile, the position stays, new episodes come`() {
        val (model, engine) = model()
        onMain { model.load() }
        onMain { model.follow(fixtures.feedUrl) }
        val item = model.state.value.inbox.first()
        onMain { model.play(item) }

        network.update { it.copy(connected = false) }
        runBlocking { eventually { model.state.value.isOffline } }
        runBlocking { eventually { engine.network.lastOrNull() == false } }
        val asked = fixtures.requests.size
        engine.report(PodcastPlayer.Reason.PAUSE, 7_000, time)
        time = time.plus(Duration.ofDays(1))
        onMain { model.refresh(asked = true) }
        onMain { model.refresh(asked = false) }
        assertEquals("no request while offline", asked, fixtures.requests.size)
        runBlocking { eventually { onMain { model.phone.progress(item.episode.key) }?.positionMs == 7_000L } }

        fixtures.extraEpisodes = listOf("4")
        network.update { it.copy(connected = true) }
        runBlocking { eventually { !model.state.value.isOffline } }
        assertEquals("the player goes on by itself", true, engine.network.last())
        onMain { model.refresh(asked = false) }
        runBlocking { eventually { model.state.value.inbox.any { it.episode.title == "Fixture Episode 4" } } }
        assertEquals(7_000L, model.state.value.progress[item.id]?.positionMs)
    }

    // region Downloads

    private class Scheduler : DownloadScheduler {
        override suspend fun active(): Set<String> = emptySet()
        override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) = Unit
        override fun cancel(key: String) = Unit
        override fun cancelAll() = Unit
    }

    private fun downloads(root: File, db: DownloadsDatabase, scope: CoroutineScope) = EpisodeDownloads(
        context = context,
        http = http,
        userAgent = "test",
        proxyMemory = ProxyMemory(),
        playback = { Playback("${fixtures.base}/ep2.m4a", isServerFile = false) },
        wifiOnly = { false },
        playing = { null },
        reportHoldings = { _, _ -> },
        log = AppLog(null, echo = false),
        scope = scope,
        root = root,
        db = db,
        schedulerFor = { Scheduler() },
    )

    @Test
    fun `killed mid-download, the next launch picks up where it got`() = runBlocking {
        val root = File(context.filesDir, "misuse-${System.nanoTime()}").also { dir -> closing += { dir.deleteRecursively() } }
        val store = DownloadsDatabase.inMemory(context)
        val item = SourcedEpisode(SourceRef.Phone, audio.own.podcast.library.Episode(id = "fixture-2", showId = "s", title = "Fixture Episode 2", audioUrl = "${fixtures.base}/ep2.m4a", audioType = "audio/mp4"))
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = downloads(root, store, first)
        before.download(item, Show(id = "s", title = "own.audio Test Fixtures"))
        eventually { item.id in before.state.value.entries }

        fixtures.slowDownloads = true
        val running = first.launch { before.core.run(item.id, attempt = 0) }
        eventually(20_000) { root.walk().any { it.name.endsWith(".part") && it.length() > 0 } }
        // The process goes: the work is cancelled mid-transfer, nothing is kept as done.
        running.cancelAndJoin()
        first.cancel()
        assertFalse(before.state.value.isDownloaded(item.id))
        val partial = root.walk().first { it.name.endsWith(".part") }.length()
        assertTrue("$partial of ${fixtures.audio(2).size}", partial in 1 until fixtures.audio(2).size)

        fixtures.slowDownloads = false
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope -> closing += { scope.cancel() } }
        val after = downloads(root, store, second)
        after.start()
        assertEquals(Downloads.Outcome.DONE, after.core.run(item.id, attempt = 0))
        eventually { after.state.value.isDownloaded(item.id) }
        val file = after.localFile(item.id)?.path?.let(::File)
        assertNotNull(file)
        assertArrayEquals("the whole file, once", fixtures.audio(2), file!!.readBytes())
        val resumed = fixtures.requests.last { it.url.encodedPath == "/ep2.m4a" }
        assertEquals("bytes=$partial-", resumed.headers["Range"])
    }

    @Test
    fun `a connection that drops mid-download is tried again, never kept half`() = runBlocking {
        val root = File(context.filesDir, "misuse-${System.nanoTime()}").also { dir -> closing += { dir.deleteRecursively() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s -> closing += { s.cancel() } }
        val downloads = downloads(root, DownloadsDatabase.inMemory(context), scope)
        val item = SourcedEpisode(SourceRef.Phone, audio.own.podcast.library.Episode(id = "fixture-2", showId = "s", title = "Fixture Episode 2", audioUrl = "${fixtures.base}/ep2.m4a"))
        downloads.download(item, null)
        eventually { item.id in downloads.state.value.entries }
        fixtures.cutDownloads = true
        assertEquals(Downloads.Outcome.RETRY, downloads.core.run(item.id, attempt = 0))
        assertNull(downloads.localFile(item.id))
        fixtures.cutDownloads = false
        assertEquals(Downloads.Outcome.DONE, downloads.core.run(item.id, attempt = 1))
        assertArrayEquals(fixtures.audio(2), File(downloads.localFile(item.id)!!.path!!).readBytes())
    }

    // endregion

    @Test
    fun `play, pause and the skips hammered faster than anything loads - it still plays after`() {
        val file = File(context.filesDir, "hammer-${System.nanoTime()}.mp3").apply { writeBytes(File("src/test/resources/fixtures/chapters-id3v24.mp3").readBytes()) }
        closing += { file.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val exo: ExoPlayer = TestExoPlayerBuilder(context).build()
        val player = PodcastPlayer(context, SpokenPlayer(exo, scope, idleReleaseMs = null), scope, service = null)
        closing += { player.spoken.release(); scope.cancel() }
        player.localFile = { Uri.fromFile(file) }
        val item = SourcedEpisode(SourceRef.Phone, audio.own.podcast.library.Episode(id = "e1", showId = "s1", title = "Episode", durationMs = 6_000))
        player.load(item, null, 0, autoplay = true, rate = 1f)
        repeat(48) { round ->
            when (round % 4) {
                0 -> player.skipForward()
                1 -> player.skipBack()
                2 -> player.toggle()
                else -> player.skipForward()
            }
        }
        // Stop while loading, then the same episode again: nothing of the first load comes back.
        player.stop()
        ShadowLooper.idleMainLooper()
        assertNull(player.state.value.item)
        player.load(item, null, 1_000, autoplay = false, rate = 1f)
        assertFalse("loaded paused stays paused after a stop of a playing one", exo.playWhenReady)
        repeat(9) { player.toggle() }
        ShadowLooper.idleMainLooper()
        assertTrue("an odd number of toggles leaves it playing", player.state.value.isPlaying)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        val before = exo.currentPosition
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPosition(0, before + 1_000)
        ShadowLooper.idleMainLooper()
        assertTrue("the position moves: ${player.state.value.positionMs}", player.state.value.positionMs > before)
        assertEquals(item.id, player.state.value.item?.id)
    }

    private companion object {
        /** Generous: CI's machines are much slower than a laptop. */
        const val RUN_TIMEOUT_MS = 60_000L
    }
}
