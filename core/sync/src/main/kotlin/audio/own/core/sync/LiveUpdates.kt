package audio.own.core.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live updates (`GET /events`) while the app is on screen, and only then: a launch by Android
 * Auto, the widget, a download or the periodic refresh never opens the stream. The app says when
 * it is in the foreground ([setForeground]) and when the other conditions may have changed
 * ([refresh]); [canRun] says whether they hold (online, not under Data Saver, signed in).
 *
 * Each change ([open]'s elements) brings one [onChange] after [debounceMs] of quiet. A dropped
 * connection is opened again after 2 s, doubling to 120 s; [isFatal] (sign-in refused) stops it
 * until the next [refresh].
 */
class LiveUpdates(
    private val scope: CoroutineScope,
    private val canRun: () -> Boolean,
    /** The stream for the server shown now; null when it has none. */
    private val open: () -> Flow<Unit>?,
    private val onChange: suspend () -> Unit,
    private val isFatal: (Throwable) -> Boolean = { false },
    private val debounceMs: Long = 2_000,
    private val firstRetryMs: Long = 2_000,
    private val maxRetryMs: Long = 120_000,
    private val log: (String) -> Unit = {},
) {
    private var foreground = false
    private var job: Job? = null
    private var pendingChange: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized
    fun setForeground(on: Boolean) {
        foreground = on
        refresh()
    }

    /** The server, the network or the sign-in changed: start, stop or start again. */
    @Synchronized
    fun refresh(restart: Boolean = false) {
        val should = foreground && canRun()
        if (!should) {
            stop()
            return
        }
        if (restart) stop()
        if (isRunning) return
        val stream = open() ?: return
        job = scope.launch { run(stream) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        pendingChange?.cancel()
        _connected.value = false
    }

    private suspend fun run(stream: Flow<Unit>) {
        var wait = firstRetryMs
        while (true) {
            try {
                _connected.value = true
                stream.collect {
                    // An event means the connection works: the next drop starts over at 2 s.
                    wait = firstRetryMs
                    change()
                }
                log("live updates: the server closed the stream")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFatal(e)) {
                    log("live updates stopped: ${e.javaClass.simpleName}")
                    _connected.value = false
                    return
                }
                log("live updates dropped: ${e.javaClass.simpleName}; again in ${wait / 1000} s")
            }
            _connected.value = false
            delay(wait)
            wait = (wait * 2).coerceAtMost(maxRetryMs)
        }
    }

    @Synchronized
    private fun change() {
        pendingChange?.cancel()
        pendingChange = scope.launch {
            delay(debounceMs)
            onChange()
        }
    }
}
