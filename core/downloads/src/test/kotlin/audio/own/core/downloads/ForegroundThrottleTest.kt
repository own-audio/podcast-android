package audio.own.core.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundThrottleTest {
    private class Post(val at: Long, val title: String?, val fraction: Float?)

    @Test
    fun `rapid progress becomes a bounded number of notifications`() = runTest {
        val posts = mutableListOf<Post>()
        val throttle = ForegroundThrottle(now = { testScheduler.currentTime }) { title, fraction -> posts += Post(testScheduler.currentTime, title, fraction) }

        throttle.update(null, null)
        assertEquals("the first one is posted at once", 1, posts.size)
        assertEquals(0L, posts.single().at)

        throttle.update("Song", null)
        assertEquals("a title is posted at once", 2, posts.size)

        // Twice a second for 30 s, as the fetcher reports, finishing at 100 %.
        val steps = 60
        for (i in 1..steps) {
            delay(500)
            throttle.update("Song", i / steps.toFloat())
        }
        val progress = posts.drop(2)
        val bound = (steps * 500L / ForegroundThrottle.EVERY_MS).toInt() + 2
        assertTrue("${progress.size} progress notifications", progress.size <= bound)
        assertTrue(progress.size < steps)
        progress.zipWithNext().dropLast(1).forEach { (a, b) -> assertTrue(b.at - a.at >= ForegroundThrottle.EVERY_MS) }
        assertEquals("the finished state is posted", 1f, posts.last().fraction)
    }

    @Test
    fun `nothing is posted when the whole percent stays the same`() = runTest {
        val posts = mutableListOf<Float?>()
        val throttle = ForegroundThrottle(now = { testScheduler.currentTime }) { _, fraction -> posts += fraction }
        throttle.update("Book", 0.101f)
        repeat(20) {
            delay(2_000)
            throttle.update("Book", 0.101f + it * 0.0001f)
        }
        assertEquals(1, posts.size)
    }

    @Test
    fun `a slow download posts each new percent`() = runTest {
        val posts = mutableListOf<Float?>()
        val throttle = ForegroundThrottle(now = { testScheduler.currentTime }) { _, fraction -> posts += fraction }
        for (i in 0..10) {
            throttle.update("Episode", i / 10f)
            delay(5_000)
        }
        assertEquals(11, posts.size)
    }
}
