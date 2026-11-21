package audio.own.core.sync

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveUpdatesTest {
    private class Fatal : Exception()

    private fun TestScope.runner(open: () -> Flow<Unit>?, canRun: () -> Boolean = { true }, onChange: () -> Unit) = LiveUpdates(
        scope = backgroundScope,
        canRun = canRun,
        open = open,
        onChange = { onChange() },
        isFatal = { it is Fatal },
    )

    @Test
    fun `nothing opens until the app is on screen`() = runTest {
        var opened = 0
        val live = runner(open = { opened++; flow { } }) {}
        live.refresh()
        runCurrent()
        assertEquals(0, opened)
        live.setForeground(true)
        runCurrent()
        assertEquals(1, opened)
        live.setForeground(false)
        assertFalse(live.isRunning)
    }

    @Test
    fun `events close together bring one change after two quiet seconds`() = runTest {
        val events = Channel<Unit>(Channel.UNLIMITED)
        var changes = 0
        val live = runner(open = { events.consumeAsFlow() }) { changes++ }
        live.setForeground(true)
        runCurrent()
        repeat(3) { events.send(Unit) }
        runCurrent()
        advanceTimeBy(1_999)
        assertEquals(0, changes)
        advanceTimeBy(2)
        assertEquals(1, changes)
        live.stop()
    }

    @Test
    fun `a dropped stream opens again after 2, 4, 8 seconds`() = runTest {
        val openedAt = ArrayList<Long>()
        val live = runner(open = { flow { openedAt += testScheduler.currentTime; throw IOException("dropped") } }) {}
        live.setForeground(true)
        advanceTimeBy(14_001)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), openedAt)
        live.stop()
    }

    @Test
    fun `a refused sign-in stops it until the next refresh`() = runTest {
        var opened = 0
        val live = runner(open = { flow { opened++; throw Fatal() } }) {}
        live.setForeground(true)
        advanceTimeBy(60_000)
        assertEquals(1, opened)
        assertFalse(live.isRunning)
        live.refresh()
        runCurrent()
        assertEquals(2, opened)
    }

    @Test
    fun `offline or under Data Saver it doesn't run`() = runTest {
        var can = false
        var opened = 0
        val live = runner(open = { opened++; flow { kotlinx.coroutines.awaitCancellation() } }, canRun = { can }) {}
        live.setForeground(true)
        runCurrent()
        assertEquals(0, opened)
        can = true
        live.refresh()
        runCurrent()
        assertTrue(live.isRunning)
        can = false
        live.refresh()
        assertFalse(live.isRunning)
    }
}
