package audio.own.player.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTimelineTest {
    private val parts = listOf(
        SpokenPart("a", "file:///a", 1000),
        SpokenPart("b", "file:///b", 2000),
        SpokenPart("c", "file:///c", 500),
    )
    private val timeline = BookTimeline(
        parts,
        chapters = listOf(
            SpokenChapter("Two", 1500, 3000),
            SpokenChapter("One", 0, 1500),
            SpokenChapter("Three", 3000, 3500),
        ),
    )

    @Test
    fun `parts lie end to end and a position finds its part and offset, clamped at both ends`() {
        assertEquals(listOf(0L, 1000L, 3000L), timeline.starts)
        assertEquals(3500L, timeline.durationMs)
        assertEquals(PartPosition(0, 0), timeline.locate(0))
        assertEquals(PartPosition(0, 999), timeline.locate(999))
        assertEquals(PartPosition(1, 0), timeline.locate(1000))
        assertEquals(PartPosition(2, 400), timeline.locate(3400))
        assertEquals(PartPosition(2, 500), timeline.locate(3500))
        assertEquals(PartPosition(2, 500), timeline.locate(9999))
        assertEquals(PartPosition(0, 0), timeline.locate(-5))
        assertEquals(1010L, timeline.position(partIndex = 1, offsetMs = 10))
        assertEquals(3500L, timeline.position(partIndex = 2, offsetMs = 9999))
        assertEquals(0L, timeline.position(partIndex = 7, offsetMs = 10))
        assertNull(BookTimeline(emptyList()).locate(0))
        assertEquals(1, timeline.indexOfPart("b"))
        assertNull(timeline.indexOfPart("zz"))
    }

    @Test
    fun `chapters are sorted, found by position, and stepped through like tracks`() {
        assertEquals(listOf("One", "Two", "Three"), timeline.chapters.map { it.title })
        assertEquals("One", timeline.chapterAt(0)?.title)
        assertEquals("Two", timeline.chapterAt(1500)?.title)
        assertEquals("Three", timeline.chapterAt(3499)?.title)
        assertEquals(1500L, timeline.nextChapterStart(100))
        assertNull(timeline.nextChapterStart(3200))
        assertEquals(1500L, timeline.previousChapterStart(2900, graceMs = 1000))
        assertEquals("just into a chapter goes to the one before", 0L, timeline.previousChapterStart(1600))
        assertEquals(0L, timeline.previousChapterStart(100))
        assertEquals(3000L, timeline.endOfChapter(1600))
        assertEquals("no chapters: the part ends it", 3000L, BookTimeline(parts).endOfChapter(1600))
        assertEquals(listOf(1000L, 3000L, 3500L), BookTimeline.chaptersFromParts(parts).map { it.endMs })
    }

    @Test
    fun `previous chapter has a three second grace`() {
        val long = BookTimeline(
            listOf(SpokenPart("a", "file:///a", 30_000)),
            listOf(SpokenChapter("1", 0, 10_000), SpokenChapter("2", 10_000, 20_000), SpokenChapter("3", 20_000, 30_000)),
        )
        assertEquals(0L, long.previousChapterStart(13_000))
        assertEquals(10_000L, long.previousChapterStart(13_001))
        assertEquals("the first chapter has none before it", 0L, long.previousChapterStart(1_000))
    }

    @Test
    fun `next in the last chapter goes to the end of the book`() {
        assertEquals(1500L, timeline.nextChapterTarget(0))
        assertEquals(3500L, timeline.nextChapterTarget(3200))
    }

    @Test
    fun `skips are clamped to the book`() {
        assertEquals(0L, timeline.skipped(100, bySeconds = -15.0))
        assertEquals(3500L, timeline.skipped(3400, bySeconds = 30.0))
        assertEquals(1500L, timeline.skipped(1000, bySeconds = 0.5))
    }

    @Test
    fun `chapters from parts use file titles, else Part N`() {
        val chapters = BookTimeline.chaptersFromParts(parts, titles = listOf("Opening", "", null))
        assertEquals(listOf("Opening", "Part 2", "Part 3"), chapters.map { it.title })
        assertEquals(listOf(0L, 1000L, 3000L), chapters.map { it.startMs })
        val czech = BookTimeline.chaptersFromParts(parts, fallbackTitle = { "Část ${it + 1}" })
        assertEquals("Část 1", czech.first().title)
    }

    @Test
    fun `an unresolved part is marked by its scheme`() {
        val part = SpokenPart.unresolved("p9", 1234)
        assertTrue(part.isUnresolved)
        assertEquals("spoken-unresolved:p9", part.uri)
        assertFalse(parts[0].isUnresolved)
        assertFalse(SpokenPart("h", "https://x/h.m3u8", 1, isHls = true, streamsByRange = true).isWindowable)
    }
}
