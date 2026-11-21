package audio.own.player.spoken

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRewindTest {
    @Test
    fun `smart rewind grows with the pause and can be switched off`() {
        val rule = SmartRewind()
        assertEquals(0L, rule.rewindMs(pausedMs = 5_000))
        assertEquals(3_000L, rule.rewindMs(pausedMs = 30_000))
        assertEquals(10_000L, rule.rewindMs(pausedMs = 300_000))
        assertEquals(20_000L, rule.rewindMs(pausedMs = 1_800_000))
        assertEquals(30_000L, rule.rewindMs(pausedMs = 86_400_000))
        assertEquals(0L, SmartRewind(isEnabled = false).rewindMs(pausedMs = 86_400_000))
    }

    @Test
    fun `each step starts exactly at its bound`() {
        val rule = SmartRewind()
        assertEquals(0L, rule.rewindMs(9_999))
        assertEquals(3_000L, rule.rewindMs(10_000))
        assertEquals(3_000L, rule.rewindMs(59_999))
        assertEquals(10_000L, rule.rewindMs(60_000))
        assertEquals(10_000L, rule.rewindMs(599_999))
        assertEquals(20_000L, rule.rewindMs(600_000))
        assertEquals(20_000L, rule.rewindMs(3_599_999))
        assertEquals(30_000L, rule.rewindMs(3_600_000))
    }
}
