package audio.own.player.spoken

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the spoken player learns after loading, on a real ExoPlayer over two 6-second files: the
 * parts' real lengths, chapters that arrive late, and a part's copy on the phone taking over.
 */
@RunWith(RobolectricTestRunner::class)
class SpokenPlayerUpdatesTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exo: ExoPlayer
    private lateinit var spoken: SpokenPlayer
    private var clock = 1_000_000L
    private val reports = mutableListOf<SpokenPosition>()

    private fun fixture(name: String, saveAs: String = name): String =
        Uri.fromFile(File(folder.root, saveAs).apply { writeBytes(File("src/test/resources/fixtures/$name").readBytes()) }).toString()

    private val mp3 by lazy { fixture("chapters-id3v24.mp3") }
    private val m4b by lazy { fixture("test-book.m4b") }

    @Before
    fun setUp() {
        exo = TestExoPlayerBuilder(context).build()
        spoken = SpokenPlayer(exo, scope, now = { clock })
        spoken.onPosition = { reports += it }
    }

    @After
    fun tearDown() {
        spoken.release()
        scope.cancel()
    }

    private fun book(parts: List<SpokenPart>, chapters: List<SpokenChapter> = emptyList()) =
        SpokenBook("srv|b1", "A Book", parts = parts, serverChapters = chapters)

    private fun ready() = run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)

    private fun settle() = run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPendingCommandsAreFullyHandled()

    /** The decoder's length of a file is a millisecond or so off its tag's. */
    private fun assertNear(expected: Long, actual: Long): Long {
        assertTrue("$actual, not about $expected", actual in expected - 20..expected + 20)
        return actual
    }

    private fun uriOf(index: Int) = exo.getMediaItemAt(index).localConfiguration?.uri?.toString()

    // region Real lengths

    @Test
    fun `an episode with no stated length starts where asked and takes the file's length`() {
        spoken.load(book(listOf(SpokenPart("e", mp3, 0))), 2_000, autoplay = false)
        assertEquals(2_000L, spoken.positionMs())
        ready()
        val state = spoken.state.value
        val real = assertNear(6_000, state.durationMs)
        assertEquals(real, spoken.currentBook?.parts?.single()?.durationMs)
        assertEquals(listOf(SpokenChapter("Part 1", 0, real)), state.chapters)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 1_900..2_100)
    }

    @Test
    fun `a stated length that is wrong gives way, and the chapters move with it`() {
        val chapters = listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, 15_000), SpokenChapter("Three", 15_000, 26_000))
        spoken.load(book(listOf(SpokenPart("f1", mp3, 20_000), SpokenPart("f2", m4b, 6_000)), chapters), 1_000, autoplay = false)
        assertEquals(26_000L, spoken.state.value.durationMs)
        ready()
        val state = spoken.state.value
        val first = spoken.currentBook!!.parts[0].durationMs
        assertNear(6_000, first)
        assertEquals(first + 6_000, state.durationMs)
        assertEquals(
            listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, first), SpokenChapter("Three", first, first + 6_000)),
            state.chapters,
        )
        spoken.seek(7_000)
        assertEquals(1, exo.currentMediaItemIndex)
        assertTrue("at ${exo.currentPosition}", exo.currentPosition in 900..1_100)
        assertEquals(first + 6_000, reports.last().durationMs)
    }

    @Test
    fun `the last chapter ends where the longer file does`() {
        val chapters = listOf(SpokenChapter("Intro", 0, 1_000), SpokenChapter("Talk", 1_000, 3_000))
        spoken.load(book(listOf(SpokenPart("e", mp3, 3_000)), chapters), 0, autoplay = false)
        ready()
        val real = assertNear(6_000, spoken.state.value.durationMs)
        assertEquals(listOf(SpokenChapter("Intro", 0, 1_000), SpokenChapter("Talk", 1_000, real)), spoken.state.value.chapters)
        spoken.seek(5_000)
        assertTrue("past the stated end: ${spoken.positionMs()}", spoken.positionMs() in 4_900..5_100)
    }

    @Test
    fun `a length close to the stated one is kept, and wrong ones too when asked`() {
        spoken.load(book(listOf(SpokenPart("f1", mp3, 6_500))), 0, autoplay = false)
        ready()
        assertEquals(6_500L, spoken.state.value.durationMs)

        spoken.adoptsPlayerDurations = false
        spoken.load(book(listOf(SpokenPart("f1", mp3, 20_000))), 0, autoplay = false)
        ready()
        assertEquals(20_000L, spoken.state.value.durationMs)
    }

    @Test
    fun `a book plays to its end on the real lengths`() {
        var finished: String? = null
        spoken.onFinished = { finished = it }
        spoken.load(book(listOf(SpokenPart("f1", mp3, 3_000), SpokenPart("f2", m4b, 20_000))), 1_000, autoplay = true)
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_ENDED)
        settle()
        assertEquals("srv|b1", finished)
        val total = assertNear(12_000, spoken.state.value.durationMs)
        assertEquals(total, reports.last().positionMs)
    }

    // endregion

    // region Chapters after loading

    @Test
    fun `chapters set after loading drive next, previous and the session`() {
        spoken.load(book(listOf(SpokenPart("f1", mp3, 6_000), SpokenPart("f2", m4b, 6_000))), 1_000, autoplay = false)
        ready()
        assertEquals(ChapterOrigin.PARTS, spoken.state.value.chapterOrigin)
        // An end left out (0) runs to the next start, the last one's to the end of the book.
        spoken.setChapters(listOf(SpokenChapter("B", 3_000, 0), SpokenChapter("A", 0, 3_000), SpokenChapter("C", 8_000, 0)))
        val state = spoken.state.value
        assertEquals(ChapterOrigin.SERVER, state.chapterOrigin)
        assertEquals(listOf(SpokenChapter("A", 0, 3_000), SpokenChapter("B", 3_000, 8_000), SpokenChapter("C", 8_000, 12_000)), state.chapters)
        assertEquals(state.chapters, spoken.currentBook?.serverChapters?.let { BookTimeline.fitted(it, 12_000) })
        assertTrue(spoken.sessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        spoken.sessionPlayer.seekToNext()
        assertEquals(3_000L, spoken.positionMs())
        spoken.nextChapter()
        assertEquals(8_000L, spoken.positionMs())
        spoken.seek(11_500)
        spoken.previousChapter()
        assertEquals(8_000L, spoken.positionMs())

        spoken.setChapters(emptyList())
        assertEquals(ChapterOrigin.PARTS, spoken.state.value.chapterOrigin)
        assertEquals(listOf("Part 1", "Part 2"), spoken.state.value.chapters.map { it.title })
    }

    @Test
    fun `end of chapter stops at a chapter set after loading`() {
        spoken.load(book(listOf(SpokenPart("f1", mp3, 6_000), SpokenPart("f2", m4b, 6_000))), 2_000, autoplay = true)
        spoken.sleepAt(SleepEnd.END_OF_CHAPTER)
        spoken.setChapters(listOf(SpokenChapter("One", 0, 3_000), SpokenChapter("Two", 3_000, 12_000)))
        run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilPlayWhenReadyIs(false)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 2_700..3_100)
    }

    @Test
    fun `late chapters are fitted to the real length`() {
        spoken.load(book(listOf(SpokenPart("e", mp3, 0))), 0, autoplay = false)
        ready()
        spoken.setChapters(listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, 9_000), SpokenChapter("Past the end", 7_000, 9_000)))
        val real = assertNear(6_000, spoken.state.value.durationMs)
        assertEquals(listOf(SpokenChapter("One", 0, 4_000), SpokenChapter("Two", 4_000, real)), spoken.state.value.chapters)
    }

    // endregion

    // region A copy on the phone

    @Test
    fun `a copy of the paused part takes over at once, at the same place`() {
        val copy = fixture("chapters-id3v24.mp3", "copy.mp3")
        spoken.load(book(listOf(SpokenPart("f1", "$mp3?stream", 6_000), SpokenPart("f2", m4b, 6_000))), 1_500, autoplay = false)
        ready()
        spoken.useLocalCopy(0, copy)
        assertEquals(copy, uriOf(0))
        assertEquals(copy, spoken.currentBook?.parts?.first()?.uri)
        ready()
        assertEquals(0, exo.currentMediaItemIndex)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 1_400..1_600)
        spoken.play()
        advance(exo).untilPosition(0, 2_500)
        assertTrue(exo.isPlaying)
    }

    @Test
    fun `the part playing moves to its copy at the next pause, not mid-sentence`() {
        val copy = fixture("chapters-id3v24.mp3", "copy.mp3")
        val stream = "$mp3?stream"
        spoken.load(book(listOf(SpokenPart("f1", stream, 6_000), SpokenPart("f2", m4b, 6_000))), 0, autoplay = true)
        advance(exo).untilPosition(0, 1_000)
        spoken.useLocalCopy("f1", copy)
        assertEquals(stream, uriOf(0))
        advance(exo).untilPosition(0, 2_000)
        assertEquals(stream, uriOf(0))
        spoken.pause()
        val paused = spoken.positionMs()
        assertEquals(copy, uriOf(0))
        assertEquals(paused, spoken.positionMs())
        ready()
        assertTrue("from $paused to ${spoken.positionMs()}", spoken.positionMs() in paused - 100..paused + 100)
    }

    @Test
    fun `a seek takes a waiting copy`() {
        val copy = fixture("chapters-id3v24.mp3", "copy.mp3")
        val stream = "$mp3?stream"
        spoken.load(book(listOf(SpokenPart("f1", stream, 6_000), SpokenPart("f2", m4b, 6_000))), 0, autoplay = true)
        advance(exo).untilPosition(0, 500)
        spoken.useLocalCopy(0, copy)
        assertEquals(stream, uriOf(0))
        spoken.seek(4_000)
        assertEquals(copy, uriOf(0))
        assertEquals(4_000L, spoken.positionMs())
        advance(exo).untilPosition(0, 4_500)
        assertTrue(exo.playWhenReady)
    }

    @Test
    fun `a copy of a part not playing is used at once and playback goes on`() {
        val copy = fixture("test-book.m4b", "copy.m4b")
        spoken.load(book(listOf(SpokenPart("f1", mp3, 6_000), SpokenPart("f2", "$m4b?stream", 6_000))), 0, autoplay = true)
        advance(exo).untilPosition(0, 500)
        spoken.useLocalCopy(1, copy)
        assertEquals(copy, uriOf(1))
        assertEquals(0, exo.currentMediaItemIndex)
        assertTrue(exo.playWhenReady)
        advance(exo).untilPosition(1, 500)
        assertTrue("at ${spoken.positionMs()}", spoken.positionMs() in 6_400..7_000)
        assertFalse(spoken.state.value.finished)
    }

    // endregion
}

/** Generous: CI's machines are much slower than a laptop, and a test player that stalls fails anyway. */
private const val RUN_TIMEOUT_MS = 60_000L
