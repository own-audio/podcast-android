package audio.own.player.spoken.media

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * "Keep Ahead": the player buffers [lookAheadMs] of the book ahead of the playhead, so a tunnel
 * or a gap in the signal shorter than that goes unheard. Everything the player reads also lands
 * in the listening cache, so the window is on disk as well as in memory.
 *
 * A thin cap over [DefaultLoadControl] built for the largest choice, because Media3 fixes a load
 * control's durations when it is built while the listener can change the setting mid-book.
 */
@UnstableApi
class LookAheadLoadControl(
    lookAheadMs: Long = 10 * 60_000L,
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_LOOK_AHEAD_MS.toInt(), 2_500, 8_000)
        // Speech is small; the byte budget only matters for a FLAC book.
        .setTargetBufferBytes(64 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build(),
) : LoadControl {
    @Volatile
    var lookAheadMs: Long = lookAheadMs.coerceIn(MIN_BUFFER_MS.toLong(), MAX_LOOK_AHEAD_MS)

    @Volatile
    private var capped = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val bufferedMs = parameters.bufferedDurationUs / 1000
        // Hysteresis: once full, wait until a minute has played before loading again, so the
        // radio isn't woken for every few seconds of audio.
        capped = if (capped) bufferedMs > lookAheadMs - REFILL_MS else bufferedMs >= lookAheadMs
        if (capped) return false
        return delegate.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out androidx.media3.exoplayer.Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        const val MIN_BUFFER_MS = 60_000
        const val MAX_LOOK_AHEAD_MS = 15 * 60_000L
        private const val REFILL_MS = 60_000L
    }
}
