package audio.own.podcastplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.SpokenPlayer
import audio.own.player.spoken.chapters.BlockCachingRangeReader
import audio.own.player.spoken.chapters.FileRangeReader
import audio.own.podcast.library.Episode
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The podcast player on the spoken player and a real ExoPlayer (fake renderers) over a 6-second
 * MP3 with chapter frames: the file's length over the feed's, the file's chapters when the feed
 * has none, the episode held paused until Play, and the reasons its position reports give.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PodcastPlayerTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var player: PodcastPlayer
    private val reports = mutableListOf<PodcastPlayer.Report>()
    private val ended = mutableListOf<Pair<String, Boolean>>()
    private lateinit var file: File

    private val show = Show(id = "s1", title = "The Morning Brief")

    private fun episode(id: String, durationMs: Long?) = SourcedEpisode(SourceRef.Phone, Episode(id = id, showId = "s1", title = "Episode $id", durationMs = durationMs))

    @Before
    fun setUp() {
        file = File(folder.root, "e.mp3").apply { writeBytes(File("src/test/resources/fixtures/chapters-id3v24.mp3").readBytes()) }
        exo = TestExoPlayerBuilder(context).build()
        val spoken = SpokenPlayer(exo, scope, openPart = { part -> Uri.parse(part.uri).path?.let { BlockCachingRangeReader(FileRangeReader(File(it))) } })
        player = PodcastPlayer(context, spoken, scope, service = null)
        // Every episode is "downloaded" here: the player opens the file.
        player.localFile = { Uri.fromFile(file) }
        player.listener = object : PodcastPlayer.Listener {
            override fun onPosition(report: PodcastPlayer.Report) {
                reports += report
            }

            override fun onEnded(id: String, playOn: Boolean) {
                ended += id to playOn
            }
        }
    }

    @After
    fun tearDown() {
        player.spoken.release()
        scope.cancel()
    }

    private fun ready() {
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)
        idle()
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test
    fun `the file's length wins over a feed that says less, or nothing`() {
        val short = episode("e1", 3_000)
        player.load(short, show, 0, autoplay = false, rate = 1f)
        ready()
        assertTrue("${player.state.value.durationMs}", player.state.value.durationMs in 5_900..6_100)
        player.seekTo(5_000)
        idle()
        assertTrue("past the feed's end: ${player.state.value.positionMs}", player.state.value.positionMs in 4_900..5_100)

        player.load(episode("e2", null), show, 1_000, autoplay = false, rate = 1f)
        ready()
        assertTrue(player.state.value.durationMs in 5_900..6_100)
    }

    @Test
    fun `the file's chapters when the feed has none, the feed's when it has them`() {
        player.load(episode("e1", 6_000), show, 0, autoplay = false, rate = 1f)
        ready()
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        repeat(20) { if (player.state.value.chapters.isEmpty()) Thread.sleep(20).also { idle() } }
        assertTrue("read from the file", player.state.value.chapters.isNotEmpty())

        val feed = listOf(SpokenChapter("Intro", 0, 2_000), SpokenChapter("Main", 2_000, 6_000))
        player.load(episode("e2", 6_000), show, 0, autoplay = false, rate = 1f, chapters = feed)
        ready()
        assertEquals(listOf("Intro", "Main"), player.state.value.chapters.map { it.title })
        player.seekTo(3_000)
        idle()
        assertEquals("Main", player.state.value.currentChapter?.title)
    }

    @Test
    fun `back after a relaunch it waits paused, and opens nothing until Play`() {
        val item = episode("e1", 6_000)
        player.load(item, show, 2_000, autoplay = false, rate = 1.5f, prepare = false)
        idle()
        assertEquals(0, exo.mediaItemCount)
        assertEquals(item, player.state.value.item)
        assertEquals(2_000L, player.state.value.positionMs)
        player.seekTo(2_500)
        idle()
        assertEquals("a scrub while held moves where it will start", 2_500L, player.state.value.positionMs)
        player.play()
        assertEquals(1, exo.mediaItemCount)
        ready()
        assertTrue("${player.state.value.positionMs}", player.state.value.positionMs in 2_400..2_700)
        assertEquals(1.5f, player.state.value.rate)
    }

    @Test
    fun `a switch, a stop and the end are reported as such`() {
        player.load(episode("e1", 6_000), show, 1_000, autoplay = false, rate = 1f)
        ready()
        player.load(episode("e2", 6_000), show, 0, autoplay = false, rate = 1f)
        assertEquals(PodcastPlayer.Reason.SWITCH, reports.last().reason)
        assertTrue(reports.last().id.endsWith("|e1"))
        ready()
        player.stop()
        assertEquals(PodcastPlayer.Reason.STOP, reports.last().reason)
        idle()
        assertNull(player.state.value.item)

        player.load(episode("e3", 3_000), show, 4_000, autoplay = true, rate = 1f)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_ENDED)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        val last = reports.last()
        assertEquals(PodcastPlayer.Reason.FINISHED, last.reason)
        assertTrue("at the file's end, not the feed's: ${last.positionMs}", last.positionMs in 5_900..6_100)
        assertEquals("plays on into Up Next", listOf(last.id to true), ended)
    }

    @Test
    fun `End of Episode stops at the end instead of playing on`() {
        player.load(episode("e1", 6_000), show, 4_000, autoplay = true, rate = 1f)
        player.sleepAtEndOfEpisode()
        idle()
        assertTrue(player.state.value.sleepAtEndOfEpisode)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_ENDED)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertFalse(ended.single().second)
    }

    @Test
    fun `skips stay inside the episode`() {
        player.setSkipIntervals(audio.own.player.core.SkipIntervals(15, 30))
        player.load(episode("e1", null), show, 1_000, autoplay = false, rate = 1f)
        ready()
        player.skipForward()
        idle()
        assertTrue("not past the end: ${player.state.value.positionMs}", player.state.value.positionMs in 5_900..6_100)
        player.skipBack()
        idle()
        assertEquals(0L, player.state.value.positionMs)
        advance(exo).untilPendingCommandsAreFullyHandled()
    }
}

/** Generous: CI's machines are much slower than a laptop, and a test player that stalls fails anyway. */
private const val RUN_TIMEOUT_MS = 60_000L
