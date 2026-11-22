package audio.own.player.spoken

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import audio.own.player.spoken.chapters.BlockCachingRangeReader
import audio.own.player.spoken.chapters.FileRangeReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * The spoken player on a real ExoPlayer (fake renderers) over two 6-second files: one timeline
 * across them, speed without pitch, skips over the boundary, chapters for next/previous, smart
 * rewind, the sleep end modes and the position reports.
 */
@RunWith(RobolectricTestRunner::class)
class SpokenPlayerTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var spoken: SpokenPlayer
    private lateinit var parts: List<SpokenPart>
    private var clock = 1_000_000L
    private val reports = mutableListOf<SpokenPosition>()

    private fun fixture(name: String): File = File(folder.root, name).apply { writeBytes(File("src/test/resources/fixtures/$name").readBytes()) }

    @Before
    fun setUp() {
        parts = listOf(
            SpokenPart("f1", Uri.fromFile(fixture("chapters-id3v24.mp3")).toString(), 6_000),
            SpokenPart("f2", Uri.fromFile(fixture("test-book.m4b")).toString(), 6_000),
        )
        exo = TestExoPlayerBuilder(context).build()
        spoken = SpokenPlayer(exo, scope, idleReleaseMs = null, openPart = { BlockCachingRangeReader(FileRangeReader(File(Uri.parse(it.uri).path!!))) }, now = { clock })
        spoken.onPosition = { reports += it }
    }

    @After
    fun tearDown() {
        spoken.release()
        scope.cancel()
    }

    private fun book(chapters: List<SpokenChapter> = emptyList()) = SpokenBook("srv|b1", "A Book", "An Author", parts = parts, serverChapters = chapters)

    private fun ready() = run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)

    @Test
    fun `a position in book time lands in the right file`() {
        spoken.load(book(), 7_000, autoplay = false)
        ready()
        assertEquals(1, exo.currentMediaItemIndex)
        assertTrue("at ${exo.currentPosition}", exo.currentPosition in 900..1_100)
        assertTrue(spoken.positionMs() in 6_900..7_100)
        assertEquals(12_000, spoken.state.value.durationMs)
    }

    @Test
    fun `playback crosses into the next file on one timeline`() {
        spoken.load(book(), 5_000, autoplay = true)
        advance(exo).untilPosition(1, 500)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 6_400..7_000)
    }

    @Test
    fun `speed changes and the pitch stays`() {
        spoken.load(book(), 0, autoplay = false, speed = 1.75f)
        assertEquals(1.75f, exo.playbackParameters.speed)
        assertEquals(1f, exo.playbackParameters.pitch)
        spoken.setSpeed(2.98f)
        assertEquals(3.0f, exo.playbackParameters.speed)
        assertEquals(1f, exo.playbackParameters.pitch)
    }

    @Test
    fun `a book loaded paused after one that played stays paused`() {
        spoken.load(book(), 0, autoplay = true)
        ready()
        assertTrue(exo.playWhenReady)
        spoken.load(book(), 1_000, autoplay = false)
        ready()
        assertFalse("loaded to be shown, not played", exo.playWhenReady)
    }

    @Test
    fun `skips cross file boundaries and stop at the ends`() {
        spoken.skipBackSecs = 5
        spoken.load(book(), 7_000, autoplay = false)
        ready()
        spoken.skipBack()
        assertEquals(0, exo.currentMediaItemIndex)
        assertTrue(spoken.positionMs() in 1_900..2_100)
        spoken.skipForward()
        assertEquals(1, exo.currentMediaItemIndex)
        assertTrue(spoken.positionMs() in 11_900..12_000)
        spoken.skipBackSecs = 60
        spoken.skipBack()
        assertEquals(0L, spoken.positionMs())
        // Each skip crossed a file, and with no chapter marks every file is a chapter.
        assertEquals(listOf(ReportReason.CHAPTER, ReportReason.CHAPTER, ReportReason.CHAPTER), reports.map { it.reason })
    }

    @Test
    fun `next and previous are chapters, also from the session`() {
        val chapters = listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, 9_000), SpokenChapter("Three", 9_000, 12_000))
        spoken.load(book(chapters), 1_000, autoplay = false)
        ready()
        assertEquals(ChapterOrigin.SERVER, spoken.state.value.chapterOrigin)
        assertTrue(spoken.sessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        spoken.sessionPlayer.seekToNext()
        assertEquals(4_000L, spoken.positionMs())
        spoken.sessionPlayer.seekToNext()
        assertEquals(9_000L, spoken.positionMs())
        assertEquals(1, exo.currentMediaItemIndex)
        // Within the grace period, previous goes to the chapter before.
        spoken.sessionPlayer.seekToPrevious()
        assertEquals(4_000L, spoken.positionMs())
        spoken.seek(8_000)
        spoken.previousChapter()
        assertEquals(4_000L, spoken.positionMs())
        // In the last chapter, next goes to the end.
        spoken.seek(10_000)
        spoken.nextChapter()
        assertTrue(spoken.positionMs() >= 11_900)
        assertEquals(ReportReason.CHAPTER, reports.first().reason)
    }

    @Test
    fun `the session's seek back and forward are the skips`() {
        spoken.skipBackSecs = 5
        spoken.skipForwardSecs = 10
        spoken.load(book(), 3_000, autoplay = false)
        ready()
        assertEquals(10_000L, spoken.sessionPlayer.seekForwardIncrement)
        spoken.sessionPlayer.seekForward()
        assertTrue(spoken.positionMs() in 12_900..13_100 || spoken.positionMs() in 11_900..12_000)
        spoken.sessionPlayer.seekBack()
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 6_900..7_100)
    }

    @Test
    fun `without server chapters the files' chapters are read`() {
        spoken.load(book(), 0, autoplay = false)
        assertEquals(ChapterOrigin.PARTS, spoken.state.value.chapterOrigin)
        assertEquals(listOf("Part 1", "Part 2"), spoken.state.value.chapters.map { it.title })
        val deadline = System.currentTimeMillis() + 5_000
        while (spoken.state.value.chapterOrigin != ChapterOrigin.FILES && System.currentTimeMillis() < deadline) {
            // Virtual time must not outrun the readers' real file reads past the 6 s limit.
            ShadowLooper.idleMainLooper(5, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        val state = spoken.state.value
        assertEquals(ChapterOrigin.FILES, state.chapterOrigin)
        assertTrue("chapters ${state.chapters}", state.chapters.size > 2)
        assertTrue(state.chapters.any { it.startMs >= 6_000 })
    }

    @Test
    fun `a resume after a pause rewinds by the pause's length`() {
        spoken.load(book(), 5_000, autoplay = true)
        advance(exo).untilPosition(0, 5_500)
        spoken.pause()
        val paused = spoken.positionMs()
        clock += 30_000
        spoken.play()
        assertTrue("from $paused to ${spoken.positionMs()}", spoken.positionMs() in paused - 3_100..paused - 2_900)
        spoken.pause()
        clock += 5_000
        val again = spoken.positionMs()
        spoken.play()
        assertEquals(again, spoken.positionMs())
    }

    @Test
    fun `a pause reports the position with the time listened`() {
        spoken.load(book(), 0, autoplay = true)
        advance(exo).untilPosition(0, 1_000)
        clock += 1_000
        spoken.pause()
        val report = reports.last()
        assertEquals(ReportReason.PAUSE, report.reason)
        assertEquals("f1", report.partId)
        assertEquals(1_000L, report.listenedMs)
        assertTrue(report.positionMs in 900..1_300)
        spoken.stop()
        assertEquals(ReportReason.STOP, reports.last().reason)
        assertEquals(0L, reports.last().listenedMs)
    }

    @Test
    fun `end of chapter pauses where the chapter ends`() {
        val chapters = listOf(SpokenChapter("One", 0, 3_000), SpokenChapter("Two", 3_000, 12_000))
        spoken.load(book(chapters), 2_000, autoplay = true)
        spoken.sleepAt(SleepEnd.END_OF_CHAPTER)
        assertEquals(SleepEnd.END_OF_CHAPTER, spoken.state.value.sleepEnd)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPlayWhenReadyIs(false)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 2_700..3_100)
        assertEquals(null, spoken.state.value.sleepEnd)
    }

    @Test
    fun `end of book plays through the files and reports the book finished`() {
        var finished: String? = null
        spoken.onFinished = { finished = it }
        spoken.load(book(), 5_000, autoplay = true)
        spoken.sleepAt(SleepEnd.END_OF_BOOK)
        advance(exo).untilPosition(1, 500)
        assertTrue("End of Book doesn't stop at the end of a file", exo.playWhenReady)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_ENDED)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()
        assertEquals("srv|b1", finished)
        assertTrue(reports.last().finished)
        assertEquals(ReportReason.FINISHED, reports.last().reason)
        assertEquals(12_000L, spoken.positionMs())
        assertFalse(exo.playWhenReady)
        assertEquals(null, spoken.state.value.sleepEnd)
    }

    @Test
    fun `a countdown and an end mode are exclusive`() {
        spoken.load(book(), 0, autoplay = false)
        spoken.sleepAt(SleepEnd.END_OF_CHAPTER)
        spoken.sleepFor(300.0)
        assertEquals(null, spoken.state.value.sleepEnd)
        assertNotNull(spoken.state.value.sleepRemainingSecs)
        spoken.sleepAt(SleepEnd.END_OF_BOOK)
        assertEquals(null, spoken.state.value.sleepRemainingSecs)
        spoken.cancelSleep()
        assertEquals(null, spoken.state.value.sleepEnd)
    }

    @Test
    fun `the session player passes every player event on`() {
        var playing = false
        var commands: Player.Commands? = null
        spoken.sessionPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                commands = availableCommands
            }
        })
        val chapters = listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, 12_000))
        spoken.load(book(chapters), 0, autoplay = true)
        advance(exo).untilPosition(0, 500)
        assertTrue(playing)
        assertTrue("chapters make next available", commands?.contains(Player.COMMAND_SEEK_TO_NEXT) == true)
    }

    @Test
    fun `the look-ahead load control plays a book across its files`() {
        val control = audio.own.player.spoken.media.LookAheadLoadControl(lookAheadMs = 5 * 60_000L)
        val player = TestExoPlayerBuilder(context).setLoadControl(control).build()
        val withWindow = SpokenPlayer(player, scope, idleReleaseMs = null, now = { clock })
        withWindow.load(book(), 5_000, autoplay = true)
        advance(player).untilPosition(1, 500)
        assertTrue(withWindow.positionMs() in 6_400..7_000)
        withWindow.release()
    }
}

/** Generous: CI's machines are much slower than a laptop, and a test player that stalls fails anyway. */
private const val RUN_TIMEOUT_MS = 60_000L
