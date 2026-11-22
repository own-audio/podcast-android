package audio.own.podcastplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import audio.own.player.core.PlayerSetup

/**
 * Settings' Keep Ahead: while an episode streams, the player loads up to [aheadMs] past the
 * playhead (through the listening cache, so those minutes are on the device) and then waits until
 * the margin has shrunk by [REFILL_MS] before loading again, so the radio is not kept awake for a
 * trickle. Under the window it is [PlayerSetup]'s load control, with room for the longest choice.
 */
@UnstableApi
class KeepAheadLoadControl(
    private val delegate: LoadControl = PlayerSetup.loadControl(maxBufferMs = MAX_WINDOW_MS + REFILL_MS.toInt()),
) : LoadControl {
    @Volatile
    var aheadMs: Long = DEFAULT_MS

    private var waiting = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val bufferedMs = parameters.bufferedDurationUs / 1000
        val ahead = aheadMs
        if (bufferedMs >= ahead) waiting = true
        if (waiting && bufferedMs > ahead - REFILL_MS) return false
        waiting = false
        return delegate.shouldContinueLoading(parameters)
    }

    // Each call forwarded by hand: Kotlin's `by` would leave these to the interface's defaults,
    // which call the older forms DefaultLoadControl no longer implements.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        const val DEFAULT_MS = 10 * 60_000L
        const val MAX_WINDOW_MS = 15 * 60_000
        const val REFILL_MS = 60_000L

        /** The window for a choice in minutes. */
        fun windowMs(minutes: Int): Long = minutes * 60_000L
    }
}
