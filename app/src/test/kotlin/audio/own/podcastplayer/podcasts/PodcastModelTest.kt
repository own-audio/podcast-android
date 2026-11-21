package audio.own.podcastplayer.podcasts

import android.app.Application
import android.util.Xml
import androidx.media3.common.util.UnstableApi
import audio.own.core.servers.NetworkState
import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedEnclosure
import audio.own.kit.feed.FeedEpisode
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedParser
import audio.own.kit.feed.FeedShow
import audio.own.kit.feed.XmlParserFactory
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.SmartRewind
import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.EpisodeKey
import audio.own.podcast.library.FeedLibrary
import audio.own.podcast.library.FeedLoader
import audio.own.podcast.library.LastPlayed
import audio.own.podcast.library.PendingProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.UpNext
import audio.own.podcast.library.ValueStore
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomFeedLibraryStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.MemoryPreferences
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import audio.own.podcastplayer.playback.EpisodePlayer
import audio.own.podcastplayer.playback.PlaybackEngine
import audio.own.podcastplayer.servers.ServersState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val FEED = "https://feeds.example.com/show.xml"

/** Stands in for [EpisodePlayer]: remembers what it was asked and plays nothing. */
class FakeEngine : PlaybackEngine {
    data class Load(val id: String, val startMs: Long, val autoplay: Boolean, val rate: Float, val prepare: Boolean)

    private val _state = MutableStateFlow(EpisodePlayer.State())
    override val state: StateFlow<EpisodePlayer.State> = _state
    override var listener: EpisodePlayer.Listener? = null
    override var smartRewind = SmartRewind()
    val loads = mutableListOf<Load>()

    override fun load(item: SourcedEpisode, show: Show?, startMs: Long, autoplay: Boolean, rate: Float, prepare: Boolean) {
        loads += Load(item.id, startMs, autoplay, rate, prepare)
        _state.update { it.copy(item = item, show = show, positionMs = startMs, durationMs = item.episode.durationMs ?: 0, rate = rate, isPlaying = autoplay) }
    }

    override fun setChapters(id: String, chapters: List<SpokenChapter>) = _state.update { it.copy(chapters = chapters) }
    override fun play() = _state.update { it.copy(isPlaying = true) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun toggle() = _state.update { it.copy(isPlaying = !it.isPlaying) }
    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }
    override fun skipBack() = Unit
    override fun skipForward() = Unit
    override fun setRate(rate: Float) = _state.update { it.copy(rate = rate) }
    override fun retryIfWaiting() = Unit
    override fun stop() = _state.update { EpisodePlayer.State() }
    override fun sleepAfter(preset: SleepTimer.Preset) = Unit
    override fun sleepAtEndOfEpisode() = Unit
    override fun stopAtEndOfChapter(on: Boolean) = Unit
    override fun extendSleep() = Unit
    override fun cancelSleep() = Unit

    fun report(reason: EpisodePlayer.Reason, positionMs: Long, at: Instant) {
        val item = _state.value.item ?: return
        listener?.onPosition(EpisodePlayer.Report(item.id, positionMs, _state.value.durationMs, reason, at))
    }

    fun finish(at: Instant) {
        val item = _state.value.item ?: return
        report(EpisodePlayer.Reason.FINISHED, _state.value.durationMs, at)
        listener?.onEnded(item.id, playOn = true)
    }
}

private fun day(day: Int) = LocalDate.of(2025, 10, day).atTime(8, 0).toInstant(ZoneOffset.UTC)

private fun feed(vararg items: Pair<String, Int>) = FeedDocument(
    show = FeedShow(title = "Show", author = "Host"),
    episodes = items.map { (id, day) ->
        FeedEpisode(id = id, title = "Episode $id", publishedAt = day(day), durationMs = 3_600_000, enclosure = FeedEnclosure("https://cdn.example.com/$id.mp3", "audio/mpeg", 100))
    },
)

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PodcastModelTest {
    private val main = mainThread()
    private val db = PodcastDatabase.inMemory(RuntimeEnvironment.getApplication())
    private var time = Instant.parse("2026-11-21T10:00:00Z")
    private var document = feed("e1" to 1, "e2" to 2)
    private val loader = FeedLoader { _, _ -> FeedFetcher.Result.Fetched(document, FeedFetcher.Validators(), FEED) }
    private val lastPlayed = object : ValueStore<LastPlayed> {
        var value: LastPlayed? = null
        override suspend fun load() = value
        override suspend fun save(value: LastPlayed?) {
            this.value = value
        }
    }

    @After
    fun close() {
        db.close()
        main.close()
    }

    private fun model(engine: FakeEngine = FakeEngine()): Pair<PodcastModel, FakeEngine> {
        val upNext = UpNext(RoomValueStore(db, "up-next", ListSerializer(UpNext.Entry.serializer())))
        val phone = FeedLibrary(RoomFeedLibraryStore(db), loader, now = { time })
        val model = PodcastModel(
            db = db,
            phone = phone,
            upNext = upNext,
            pending = PendingProgress(RoomValueStore(db, "pending", ListSerializer(PendingProgress.Entry.serializer()))),
            lastPlayed = lastPlayed,
            player = engine,
            servers = MutableStateFlow(ServersState(loaded = true)),
            client = { null },
            preferences = MemoryPreferences(),
            network = MutableStateFlow(NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi")),
            offlineMode = { false },
            http = OkHttpClient(),
            feeds = FeedFetcher(OkHttpClient(), FeedParser(XmlParserFactory { Xml.newPullParser() })),
            isFullyCached = { false },
            strings = object : PodcastModel.Strings {
                override val thisDevice = "This Device"
                override fun chapter(number: Int) = "Chapter $number"
            },
            log = AppLog(null, echo = false),
            scope = CoroutineScope(SupervisorJob() + main),
            now = { time },
            country = "us",
        )
        return model to engine
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(main) { block() } }

    @Test
    fun `following a feed fills the shows and New Episodes - played and dismissed leave it`() {
        val (model, _) = model()
        onMain { model.load() }
        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.shows.isEmpty())
        val show = onMain { model.follow(FEED) }
        assertEquals(SourceRef.Phone, show.source)
        assertEquals(listOf("Show"), model.state.value.shows.map { it.show.title })
        assertEquals("a new follow brings its newest episode", listOf("e2"), model.state.value.inbox.map { it.episode.id })
        time = time.plusSeconds(3600)
        document = feed("e1" to 1, "e2" to 2, "e3" to 3, "e4" to 4)
        onMain { model.refresh(asked = true) }
        assertEquals(listOf("e4", "e3", "e2"), model.state.value.inbox.map { it.episode.id })
        val e4 = model.state.value.inbox.first()
        model.markPlayed(e4, true)
        runBlocking { eventually { model.state.value.inbox.none { it.id == e4.id } } }
        model.dismiss(model.state.value.inbox.first())
        assertEquals(listOf("e2"), model.state.value.inbox.map { it.episode.id })
        onMain { model.reloadLists() }
        assertEquals("both are written, not just hidden", listOf("e2"), model.state.value.inbox.map { it.episode.id })
    }

    @Test
    fun `play starts where the listener was, after the intro, at the show's speed`() {
        val (model, engine) = model()
        onMain { model.load() }
        val show = onMain { model.follow(FEED) }
        model.updateSettings(show.show.settings.copy(skipIntroSecs = 30.0, speed = 1.5f), show)
        runBlocking { eventually { model.state.value.shows.first().show.settings.speed == 1.5f } }
        val item = model.state.value.inbox.first()
        onMain { model.play(item) }
        assertEquals(FakeEngine.Load(item.id, 30_000, autoplay = true, rate = 1.5f, prepare = true), engine.loads.last())
        engine.report(EpisodePlayer.Reason.PAUSE, 600_000, time)
        runBlocking { eventually { onMain { model.phone.progress(item.episode.key) }?.positionMs == 600_000L } }
        onMain { model.play(item) }
        assertEquals("the intro is skipped only from the start", 600_000L, engine.loads.last().startMs)
    }

    @Test
    fun `positions reach the store once a minute while playing, and at once on a pause`() {
        val (model, engine) = model()
        onMain { model.load() }
        onMain { model.follow(FEED) }
        val item = model.state.value.inbox.first()
        onMain { model.play(item) }
        val key = item.episode.key
        engine.report(EpisodePlayer.Reason.PERIODIC, 10_000, time)
        runBlocking { eventually { onMain { model.phone.progress(key) }?.positionMs == 10_000L } }
        engine.report(EpisodePlayer.Reason.PERIODIC, 20_000, time.plusSeconds(10))
        engine.report(EpisodePlayer.Reason.PERIODIC, 30_000, time.plusSeconds(20))
        Thread.sleep(200)
        assertEquals("held back by the minute's throttle", 10_000L, onMain { model.phone.progress(key) }?.positionMs)
        assertEquals("the screens have it at once", 30_000L, model.state.value.progress[item.id]?.positionMs)
        assertEquals("and so does last-played", 30_000L, lastPlayed.value?.positionMs)
        engine.report(EpisodePlayer.Reason.PAUSE, 31_000, time.plusSeconds(21))
        runBlocking { eventually { onMain { model.phone.progress(key) }?.positionMs == 31_000L } }
    }

    @Test
    fun `at the end Up Next plays on, then the newest new episode`() {
        val (model, engine) = model()
        onMain { model.load() }
        document = feed("e1" to 1)
        onMain { model.follow(FEED) }
        time = time.plusSeconds(60)
        document = feed("e1" to 1, "e2" to 2, "e3" to 3)
        onMain { model.refresh(asked = true) }
        val (e3, e2) = model.state.value.inbox
        model.playLater(model.state.value.inbox.first { it.episode.id == "e1" })
        runBlocking { eventually { model.state.value.upNext.size == 1 } }
        onMain { model.play(e3) }
        engine.finish(time)
        runBlocking { eventually { engine.loads.size == 2 } }
        assertTrue(engine.loads.last().id.endsWith("|e1"))
        assertTrue("it left the queue", model.state.value.upNext.isEmpty())
        assertFalse("a finished episode leaves New Episodes", model.state.value.inbox.any { it.id == e3.id })
        engine.finish(time)
        runBlocking { eventually { engine.loads.size == 3 } }
        assertEquals("then the newest new episode not yet played", e2.id, engine.loads.last().id)
    }

    @Test
    fun `the last episode comes back paused after a restart, its missed position stored`() {
        val (first, engine) = model()
        onMain { first.load() }
        onMain { first.follow(FEED) }
        val item = first.state.value.inbox.first()
        onMain { first.play(item) }
        engine.report(EpisodePlayer.Reason.PERIODIC, 5_000, time)
        runBlocking { eventually { onMain { first.phone.progress(item.episode.key) }?.positionMs == 5_000L } }
        // Ten seconds on, killed before the minute's write.
        engine.report(EpisodePlayer.Reason.PERIODIC, 15_000, time.plusSeconds(10))
        runBlocking { eventually { lastPlayed.value?.positionMs == 15_000L } }

        val (second, restarted) = model(FakeEngine())
        onMain { second.load() }
        assertEquals(FakeEngine.Load(item.id, 15_000, autoplay = false, rate = 1.0f, prepare = false), restarted.loads.single())
        runBlocking { eventually { onMain { second.phone.progress(item.episode.key) }?.positionMs == 15_000L } }
    }

    @Test
    fun `unfollowing stops its episode and takes it out of Up Next`() {
        val (model, engine) = model()
        onMain { model.load() }
        val show = onMain { model.follow(FEED) }
        val item = model.state.value.inbox.first()
        model.playLater(item)
        runBlocking { eventually { model.state.value.upNext.isNotEmpty() } }
        onMain { model.play(item) }
        model.unfollow(show)
        runBlocking { eventually { model.state.value.shows.isEmpty() } }
        assertNull(engine.state.value.item)
        assertTrue(model.state.value.upNext.isEmpty())
        assertNull(onMain { model.phone.progress(EpisodeKey(show.show.id, "e2")) })
        runBlocking { eventually { lastPlayed.value == null } }
        assertFalse(model.state.value.inbox.any { it.episode.showId == show.show.id })
    }
}
