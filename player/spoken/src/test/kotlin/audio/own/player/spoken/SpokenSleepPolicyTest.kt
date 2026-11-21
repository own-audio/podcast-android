package audio.own.player.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenSleepPolicyTest {
    private val parts = listOf(SpokenPart("p1", "file:///1", 6000), SpokenPart("p2", "file:///2", 2000))
    private val withChapters = BookTimeline(
        parts,
        listOf(SpokenChapter("One", 0, 2000), SpokenChapter("Two", 2000, 4000), SpokenChapter("Three", 4000, 6000), SpokenChapter("Tail", 6000, 8000)),
    )
    private val noChapters = BookTimeline(parts)

    @Test
    fun `end of chapter pauses 250 ms before the chapter ends, once`() {
        val policy = SpokenSleepPolicy().apply { arm(SleepEnd.END_OF_CHAPTER) }
        assertEquals(SleepDecision.CONTINUE, policy.onTick(withChapters, fromMs = 1500, toMs = 1749))
        assertEquals(SleepDecision.PAUSE, policy.onTick(withChapters, fromMs = 1749, toMs = 1750))
        assertNull(policy.armed)
        assertEquals("disarmed after it fired", SleepDecision.CONTINUE, policy.onTick(withChapters, 1750, 3990))
    }

    @Test
    fun `a tick that already crossed into the next chapter still pauses`() {
        val policy = SpokenSleepPolicy().apply { arm(SleepEnd.END_OF_CHAPTER) }
        assertEquals(SleepDecision.PAUSE, policy.onTick(withChapters, fromMs = 3700, toMs = 4300))
    }

    @Test
    fun `without chapters the end of the part counts`() {
        val policy = SpokenSleepPolicy().apply { arm(SleepEnd.END_OF_CHAPTER) }
        assertEquals(SleepDecision.CONTINUE, policy.onTick(noChapters, 4000, 5000))
        assertEquals(SleepDecision.PAUSE, policy.onTick(noChapters, 5000, 5800))
    }

    @Test
    fun `after a seek the chapter sought into is the one that ends it`() {
        val policy = SpokenSleepPolicy().apply { arm(SleepEnd.END_OF_CHAPTER) }
        // The player passes the seek target as the previous position.
        assertEquals(SleepDecision.CONTINUE, policy.onTick(withChapters, fromMs = 6100, toMs = 6600))
        assertEquals(SleepDecision.PAUSE, policy.onTick(withChapters, fromMs = 7700, toMs = 7800))
    }

    @Test
    fun `end of book ignores chapter and part ends and pauses at the end of the last part`() {
        val policy = SpokenSleepPolicy().apply { arm(SleepEnd.END_OF_BOOK) }
        assertEquals(SleepDecision.CONTINUE, policy.onTick(withChapters, 1900, 2100))
        assertEquals(SleepDecision.CONTINUE, policy.onPartEnded(withChapters, partIndex = 0))
        assertEquals(SleepEnd.END_OF_BOOK, policy.armed)
        assertEquals(SleepDecision.PAUSE, policy.onPartEnded(withChapters, partIndex = 1))
        assertFalse(policy.isArmed)
    }

    @Test
    fun `the modes are exclusive and an unarmed policy never pauses`() {
        val policy = SpokenSleepPolicy()
        assertEquals(SleepDecision.CONTINUE, policy.onTick(withChapters, 1900, 2100))
        assertEquals(SleepDecision.CONTINUE, policy.onPartEnded(withChapters, 1))
        policy.arm(SleepEnd.END_OF_CHAPTER)
        policy.arm(SleepEnd.END_OF_BOOK)
        assertEquals(SleepEnd.END_OF_BOOK, policy.armed)
        assertEquals(SleepDecision.CONTINUE, policy.onTick(withChapters, 1900, 2100))
        policy.disarm()
        assertFalse(policy.isArmed)
    }
}
