package audio.own.core.servers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnreachableRetryTest {
    private val wifi = NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi,wlan0")
    private val cellular = NetworkState(connected = true, metered = true, dataSaver = false, signature = "cellular,rmnet0")

    /** Stands in for the servers model: a recheck goes Checking, then Reachable or Unreachable. */
    private inner class World(val scope: TestScope, foreground: Boolean = true, network: NetworkState = wifi, var canAsk: Boolean = true) {
        val foreground = MutableStateFlow(foreground)
        val network = MutableStateFlow(network)
        val reachability = MutableStateFlow<Map<String, Reachability>>(emptyMap())
        val answering = HashSet<String>()
        val attempts = ArrayList<Pair<String, Long>>()
        val retry = UnreachableRetry(scope.backgroundScope, this.foreground, this.network, reachability, { canAsk }) { id ->
            attempts += id to scope.testScheduler.currentTime
            reachability.value += id to Reachability.CHECKING
            val ok = id in answering
            reachability.value += id to if (ok) Reachability.REACHABLE else Reachability.UNREACHABLE
            ok
        }

        fun unreachable(id: String) {
            reachability.value += id to Reachability.UNREACHABLE
        }

        fun times(id: String = "nas") = attempts.filter { it.first == id }.map { it.second }
    }

    private fun world(scope: TestScope, foreground: Boolean = true, network: NetworkState = wifi): World =
        World(scope, foreground, network).also {
            it.retry.start()
            scope.runCurrent()
        }

    @Test fun theBackoffIs15s30s60s120sThenEveryFiveMinutes() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(30 * 60_000L)
        val expected = listOf(15_000L, 45_000L, 105_000L, 225_000L, 525_000L, 825_000L, 1_125_000L, 1_425_000L, 1_725_000L)
        assertEquals(expected, w.times())
    }

    @Test fun nothingIsAskedInTheBackground() = runTest {
        val w = world(this, foreground = false)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertTrue(w.attempts.isEmpty())
    }

    @Test fun goingToTheBackgroundStopsTheRetries() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(16_000)
        assertEquals(listOf(15_000L), w.times())
        w.foreground.value = false
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertEquals(listOf(15_000L), w.times())
    }

    @Test fun aReachableServerIsNeverAsked() = runTest {
        val w = world(this)
        w.reachability.value = mapOf("cloud" to Reachability.REACHABLE, "nas" to Reachability.CHECKING)
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        w.foreground.value = false
        runCurrent()
        w.foreground.value = true
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertTrue(w.attempts.isEmpty())
    }

    @Test fun nothingIsAskedWithoutANetwork() = runTest {
        val w = world(this, network = NetworkState.Offline)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertTrue(w.attempts.isEmpty())
    }

    @Test fun nothingIsAskedInOfflineMode() = runTest {
        val w = world(this)
        w.canAsk = false
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertTrue(w.attempts.isEmpty())
    }

    @Test fun comingBackToTheForegroundAsksAtOnceAndStartsTheBackoffOver() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(106_000) // attempts at 15, 45 and 105 s: the next wait would be 120 s
        w.foreground.value = false
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        val back = currentTime
        w.foreground.value = true
        runCurrent()
        advanceTimeBy(16_000)
        assertEquals(listOf(15_000L, 45_000L, 105_000L, back, back + 15_000L), w.times())
    }

    @Test fun aNetworkChangeStartsTheBackoffOver() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(106_000)
        val changed = currentTime
        w.network.value = cellular
        runCurrent()
        advanceTimeBy(16_000)
        assertEquals(listOf(15_000L, 45_000L, 105_000L, changed + 15_000L), w.times())
    }

    @Test fun aServerDownAtLaunchComesBackWithoutANetworkChange() = runTest {
        // As at launch: the app is not on screen yet while the first race fails.
        val w = world(this, foreground = false)
        w.unreachable("nas")
        runCurrent()
        w.foreground.value = true
        runCurrent()
        assertEquals(listOf(0L), w.times())
        advanceTimeBy(50_000)
        w.answering += "nas"
        advanceTimeBy(60_000)
        assertEquals(Reachability.REACHABLE, w.reachability.value["nas"])
        assertEquals(listOf(0L, 15_000L, 45_000L, 105_000L), w.times())
        advanceTimeBy(60 * 60_000L)
        assertEquals(4, w.attempts.size)
    }

    @Test fun aServerThatFailsAgainAfterAnsweringStartsFrom15s() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(46_000)
        w.answering += "nas"
        advanceTimeBy(60_000)
        assertEquals(Reachability.REACHABLE, w.reachability.value["nas"])
        val down = currentTime
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(16_000)
        assertEquals(listOf(15_000L, 45_000L, 105_000L, down + 15_000L), w.times())
    }

    @Test fun aNudgeAsksAtOnceAndKeepsTheBackoff() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(50_000) // 15 and 45 s done; the next one would come at 105 s
        w.retry.nudge("nas")
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(listOf(15_000L, 45_000L, 50_000L, 50_000L + 60_000L), w.times().take(4))
    }

    @Test fun aNudgeDoesNothingForAReachableServerOrInTheBackground() = runTest {
        val w = world(this)
        w.reachability.value = mapOf("cloud" to Reachability.REACHABLE)
        runCurrent()
        w.retry.nudge("cloud")
        runCurrent()
        w.foreground.value = false
        w.unreachable("nas")
        runCurrent()
        w.retry.nudge("nas")
        runCurrent()
        advanceTimeBy(60_000)
        assertTrue(w.attempts.isEmpty())
    }

    @Test fun aRemovedServerIsNoLongerAsked() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(16_000)
        w.reachability.value = emptyMap()
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertEquals(listOf(15_000L), w.times())
    }

    @Test fun eachServerHasItsOwnBackoff() = runTest {
        val w = world(this)
        w.unreachable("nas")
        runCurrent()
        advanceTimeBy(20_000)
        w.unreachable("vpn")
        runCurrent()
        advanceTimeBy(40_000)
        assertEquals(listOf(15_000L, 45_000L), w.times("nas"))
        assertEquals(listOf(35_000L), w.times("vpn"))
    }
}
