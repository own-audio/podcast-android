package audio.own.player.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A client-side sleep timer: a countdown, or "stop at the end of this item".
 *
 * Counting down is split from waiting in real time: [tick] is called directly by tests, and a
 * coroutine in [scope] calls it once a second in the app. Use it from one thread (the player's
 * main thread); [scope] should run there too.
 */
class SleepTimer(private val scope: CoroutineScope) {
    enum class Preset(val minutes: Int) {
        FIVE_MINUTES(5),
        FIFTEEN_MINUTES(15),
        THIRTY_MINUTES(30),
        FORTY_FIVE_MINUTES(45),
        SIXTY_MINUTES(60);

        val secs: Double get() = minutes * 60.0
    }

    data class State(
        /** Seconds left on a countdown; null when no countdown runs. */
        val remainingSecs: Double? = null,
        /**
         * Armed to stop when whatever is playing reaches its own end, rather than after a
         * duration. Deliberately not "duration minus position": that number is wrong the moment
         * the listener scrubs, pauses or changes speed (at 1.5×, half an hour of episode is
         * twenty minutes of wall clock), and it would race the queue's auto-advance at exactly
         * the moment it matters. So there is no countdown here; the player says when the item
         * actually ended ([consumeEndOfItem]).
         */
        val stopsAtEndOfItem: Boolean = false,
    ) {
        val isActive: Boolean get() = remainingSecs != null || stopsAtEndOfItem
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val remainingSecs: Double? get() = _state.value.remainingSecs
    val stopsAtEndOfItem: Boolean get() = _state.value.stopsAtEndOfItem
    val isActive: Boolean get() = _state.value.isActive

    /**
     * Called once when the countdown reaches zero. The player pauses from here, rather than this
     * type reaching into playback state itself.
     */
    var onExpire: (() -> Unit)? = null

    private var tickJob: Job? = null

    fun start(preset: Preset) = start(preset.secs)

    fun start(secs: Double) {
        _state.value = State(remainingSecs = maxOf(secs, 0.0))
        startTicking()
    }

    /** The two modes are exclusive: picking one replaces the other. */
    fun startUntilEndOfItem() {
        stopTicking()
        _state.value = State(stopsAtEndOfItem = true)
    }

    /**
     * Whether this finish is the one the timer was waiting for, disarming it if so. The player
     * asks when the item has played out, **before** the queue advances; `true` means stop there
     * rather than start whatever is queued next.
     */
    fun consumeEndOfItem(): Boolean {
        if (!_state.value.stopsAtEndOfItem) return false
        _state.value = _state.value.copy(stopsAtEndOfItem = false)
        return true
    }

    /** "+10 minutes" while counting down; nothing once it has expired or in end-of-item mode. */
    fun extend(bySecs: Double) {
        val remaining = _state.value.remainingSecs ?: return
        _state.value = _state.value.copy(remainingSecs = remaining + bySecs)
    }

    fun cancel() {
        stopTicking()
        _state.value = State()
    }

    internal fun tick(bySecs: Double = 1.0) {
        val remaining = _state.value.remainingSecs ?: return
        val next = remaining - bySecs
        if (next <= 0) {
            stopTicking()
            _state.value = _state.value.copy(remainingSecs = null)
            onExpire?.invoke()
        } else {
            _state.value = _state.value.copy(remainingSecs = next)
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    companion object {
        /** What "+10 minutes" adds. */
        const val EXTEND_SECS = 10 * 60.0
    }
}
