package audio.own.player.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of music-ios `SleepTimerTests`. */
class SleepTimerTest {
    private fun timer(scope: TestScope = TestScope()) = SleepTimer(scope)

    @Test
    fun startSetsFullDuration() {
        val timer = timer()
        timer.start(SleepTimer.Preset.FIVE_MINUTES)
        assertEquals(300.0, timer.remainingSecs)
        assertTrue(timer.isActive)
    }

    @Test
    fun tickCountsDown() {
        val timer = timer()
        var expired = false
        timer.onExpire = { expired = true }
        timer.start(10.0)

        timer.tick(4.0)

        assertEquals(6.0, timer.remainingSecs)
        assertFalse(expired)
    }

    @Test
    fun reachingZeroFiresOnExpire() {
        val timer = timer()
        var expireCount = 0
        timer.onExpire = { expireCount++ }
        timer.start(5.0)

        timer.tick(5.0)

        assertNull(timer.remainingSecs)
        assertFalse(timer.isActive)
        assertEquals(1, expireCount)

        // A tick after expiry must be a no-op: nothing left to count down.
        timer.tick(1.0)
        assertEquals(1, expireCount)
    }

    @Test
    fun overshootingTickStillFiresOnce() {
        val timer = timer()
        var expireCount = 0
        timer.onExpire = { expireCount++ }
        timer.start(5.0)

        timer.tick(100.0)

        assertNull(timer.remainingSecs)
        assertEquals(1, expireCount)
    }

    @Test
    fun extendAddsTime() {
        val timer = timer()
        timer.start(60.0)
        timer.tick(50.0)

        timer.extend(SleepTimer.EXTEND_SECS)

        assertEquals(610.0, timer.remainingSecs)
    }

    @Test
    fun extendAfterExpiryIsNoOp() {
        val timer = timer()
        timer.start(5.0)
        timer.tick(5.0)

        timer.extend(300.0)

        assertNull(timer.remainingSecs)
    }

    @Test
    fun cancelClearsState() {
        val timer = timer()
        var expired = false
        timer.onExpire = { expired = true }
        timer.start(5.0)

        timer.cancel()

        assertNull(timer.remainingSecs)
        assertFalse(timer.isActive)

        timer.tick(100.0)
        assertFalse(expired)
    }

    @Test
    fun startingAgainReplacesCountdown() {
        val timer = timer()
        timer.start(60.0)
        timer.tick(10.0)
        assertEquals(50.0, timer.remainingSecs)

        timer.start(SleepTimer.Preset.FIFTEEN_MINUTES)

        assertEquals(900.0, timer.remainingSecs)
    }

    // End of item

    @Test
    fun endOfItemHasNoCountdown() {
        val timer = timer()
        timer.startUntilEndOfItem()
        assertTrue(timer.stopsAtEndOfItem)
        assertNull(timer.remainingSecs)
        assertTrue(timer.isActive)
    }

    @Test
    fun consumesTheFinishOnce() {
        val timer = timer()
        timer.startUntilEndOfItem()
        assertTrue(timer.consumeEndOfItem())
        assertFalse(timer.stopsAtEndOfItem)
        assertFalse(timer.isActive)
        assertFalse("a second finish is not this timer's business", timer.consumeEndOfItem())
    }

    @Test
    fun doesNotConsumeWhenUnarmed() {
        val timer = timer()
        assertFalse(timer.consumeEndOfItem())
        timer.start(60.0)
        assertFalse(timer.consumeEndOfItem())
    }

    @Test
    fun modesAreExclusive() {
        val timer = timer()
        timer.start(300.0)
        timer.startUntilEndOfItem()
        assertNull(timer.remainingSecs)

        timer.start(SleepTimer.Preset.FIVE_MINUTES)
        assertFalse(timer.stopsAtEndOfItem)
        assertEquals(300.0, timer.remainingSecs)
    }

    @Test
    fun cancelDisarms() {
        val timer = timer()
        timer.startUntilEndOfItem()
        timer.cancel()
        assertFalse(timer.stopsAtEndOfItem)
        assertFalse(timer.isActive)
    }

    @Test
    fun extendIsANoOp() {
        val timer = timer()
        timer.startUntilEndOfItem()
        timer.extend(600.0)
        assertNull(timer.remainingSecs)
        assertTrue(timer.stopsAtEndOfItem)
    }

    /** Not in the Swift suite: the once-a-second loop itself, on virtual time. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theClockCountsDownOnceASecond() = runTest {
        val timer = SleepTimer(backgroundScope)
        var expired = 0
        timer.onExpire = { expired++ }
        timer.start(3.0)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2.0, timer.remainingSecs)

        advanceTimeBy(2_000)
        runCurrent()
        assertNull(timer.remainingSecs)
        assertEquals(1, expired)

        advanceTimeBy(10_000)
        assertEquals("the loop stops after expiry", 1, expired)
    }
}
