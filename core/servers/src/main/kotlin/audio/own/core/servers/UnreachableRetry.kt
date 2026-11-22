package audio.own.core.servers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A server's connection as [UnreachableRetry] needs to know it. */
enum class Reachability { CHECKING, REACHABLE, UNREACHABLE }

/**
 * Asks servers that didn't answer again, so one that was down at launch (DNS not ready yet, a NAS
 * restarting) comes back without a network change or a tap on "Check Now".
 *
 * Only while the app is on screen and the network is up: a locked phone playing music, the app in
 * the background or no network costs nothing, and a server that answers is never asked. Each
 * attempt is [recheck] — the address race, which signs nothing in and syncs nothing.
 */
class UnreachableRetry(
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
    private val network: StateFlow<NetworkState>,
    private val reachability: Flow<Map<String, Reachability>>,
    /** False in Offline Mode: the listener asked for no server requests at all. */
    private val canAsk: () -> Boolean = { true },
    /** Races the server's addresses again; true when it answers now. */
    private val recheck: suspend (String) -> Boolean,
) {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    /** How far along the backoff each server is; kept across a nudge, cleared on a reset. */
    private val steps = HashMap<String, Int>()
    private val inFlight = HashSet<String>()
    private var latest: Map<String, Reachability> = emptyMap()
    private var wasForeground = false
    private var active = false
    private var signature: String? = null

    fun start(): Job = scope.launch {
        combine(foreground, network, reachability) { fg, net, map -> Triple(fg, net, map) }.collect { (fg, net, map) ->
            changed(fg, net, map)
        }
    }

    /**
     * The listener is about to use [serverId] (its page, switching to it): asks at once rather
     * than at the next step of the backoff.
     */
    fun nudge(serverId: String) {
        synchronized(lock) {
            if (!active || latest[serverId] != Reachability.UNREACHABLE || serverId in inFlight) return
            jobs.remove(serverId)?.cancel()
            jobs[serverId] = retry(serverId, immediately = true)
        }
    }

    private fun changed(fg: Boolean, net: NetworkState, map: Map<String, Reachability>) {
        synchronized(lock) { changedLocked(fg, net, map) }
    }

    private fun changedLocked(fg: Boolean, net: NetworkState, map: Map<String, Reachability>) {
        latest = map
        val cameToForeground = fg && !wasForeground
        wasForeground = fg
        active = fg && net.connected
        if (!active) {
            stopAll()
            return
        }
        // A new network gets its own race from the network-change path; the backoff starts over
        // after it, as it does each time the app comes back on screen.
        val networkChanged = signature != null && signature != net.signature
        signature = net.signature
        if (cameToForeground || networkChanged) {
            stopAll()
            steps.clear()
        }
        for (id in jobs.keys.toList()) {
            val now = map[id]
            if (now == null || now == Reachability.REACHABLE) {
                steps.remove(id)
                // An attempt in flight finishes on its own and ends the loop.
                if (id !in inFlight) jobs.remove(id)?.cancel()
            }
        }
        for ((id, now) in map) {
            if (now == Reachability.REACHABLE) steps.remove(id)
            if (now == Reachability.UNREACHABLE && id !in jobs) jobs[id] = retry(id, immediately = cameToForeground)
        }
    }

    private fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun retry(id: String, immediately: Boolean): Job = scope.launch {
        if (immediately && attempt(id)) return@launch
        while (true) {
            val step = synchronized(lock) { steps[id] ?: 0 }
            delay(delayAfter(step))
            synchronized(lock) { steps[id] = step + 1 }
            if (attempt(id)) return@launch
        }
    }

    /** True when the loop is done: the server answers, or another loop took over. */
    private suspend fun attempt(id: String): Boolean {
        if (!canAsk()) return false
        val self = currentCoroutineContext()[Job]
        synchronized(lock) {
            when (latest[id]) {
                Reachability.UNREACHABLE -> Unit
                // Someone else is racing it right now (a network change, Check Now).
                Reachability.CHECKING -> return false
                else -> {
                    if (jobs[id] === self) jobs.remove(id)
                    return true
                }
            }
            if (id in inFlight) return false
            inFlight += id
        }
        // Not cut short by going to the background: the race lasts seconds and, once it has an
        // answer, saves the addresses it learned.
        val answered = try {
            withContext(NonCancellable) { runCatching { recheck(id) }.getOrDefault(false) }
        } finally {
            synchronized(lock) { inFlight -= id }
        }
        if (!answered) return false
        synchronized(lock) {
            steps.remove(id)
            if (jobs[id] === self) jobs.remove(id)
        }
        return true
    }

    companion object {
        /** 15 s, 30 s, 60 s, 120 s, then every 5 minutes. */
        val DELAYS_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L)
        const val MAX_DELAY_MS = 300_000L

        fun delayAfter(step: Int): Long = DELAYS_MS.getOrElse(step) { MAX_DELAY_MS }
    }
}
