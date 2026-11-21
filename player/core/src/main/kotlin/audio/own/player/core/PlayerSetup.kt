package audio.own.player.core

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource

/** What the app plays, for the system's audio routing and ducking: music, or speech. */
enum class ContentKind { MUSIC, SPEECH }

/** How every own.audio app builds and configures its ExoPlayer. */
@UnstableApi
object PlayerSetup {
    fun audioAttributes(kind: ContentKind): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (kind == ContentKind.MUSIC) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

    /**
     * The iOS apps' back-pressure values: buffer up to two minutes ahead, refill below one, and
     * after running dry wait for eight seconds before playing on, so a weak network stutters
     * once rather than every second. Time wins over the default byte budget, which would cap a
     * FLAC stream at well under two minutes.
     */
    fun loadControl(
        maxBufferMs: Int = 120_000,
        minBufferMs: Int = 60_000,
        bufferForPlaybackMs: Int = 2_500,
        bufferForPlaybackAfterRebufferMs: Int = 8_000,
    ): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /** An ExoPlayer for the service, configured by [configure]. */
    fun buildPlayer(
        context: Context,
        mediaSourceFactory: MediaSource.Factory,
        kind: ContentKind,
        loadControl: LoadControl = loadControl(),
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // Keeps Wi-Fi awake while streaming with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { configure(it, kind) }

    /**
     * Audio attributes **without** focus handling (see [AudioFocusOnFirstPlay], which turns it
     * on), and pause when headphones are unplugged.
     */
    fun configure(player: ExoPlayer, kind: ContentKind) {
        player.setAudioAttributes(audioAttributes(kind), false)
        player.setHandleAudioBecomingNoisy(true)
    }
}

/**
 * Lets ExoPlayer handle audio focus from the first Play on, never before.
 *
 * Opening the app, restoring the last queue or preparing an item must not stop another app's
 * audio (an iOS 1.0 bug did exactly that). From the first Play ExoPlayer owns focus: it pauses
 * for a call, ducks for a navigation prompt and resumes where it was afterwards.
 */
@UnstableApi
class AudioFocusOnFirstPlay(private val attributes: AudioAttributes) {
    /** Whether a play has happened, so focus is handled from now on. */
    var isEnabled: Boolean = false
        private set

    /** Call before every play on [player]; cheap after the first. */
    fun beforePlay(player: ExoPlayer) {
        if (!isEnabled) isEnabled = true
        if (!handles.contains(player)) {
            player.setAudioAttributes(attributes, true)
            handles += player
        }
    }

    /**
     * [player] stops handling focus (a crossfade's outgoing deck: two players of one app asking
     * for focus would take it from each other and pause the fade).
     */
    fun release(player: ExoPlayer) {
        if (handles.remove(player)) player.setAudioAttributes(attributes, false)
    }

    private val handles = mutableSetOf<ExoPlayer>()
}

/**
 * Releases the decoder and the `AudioTrack` [delayMs] after a pause, by stopping the player
 * (which keeps the playlist and the position), and prepares it again on the next play. A paused
 * player otherwise holds the audio hardware awake for as long as the app lives.
 *
 * Only an explicit pause counts: during a transient focus loss (a call) `playWhenReady` stays
 * true and nothing is released, so playback continues with sound when the call ends.
 */
@UnstableApi
class IdleDecoderRelease(
    private val player: ExoPlayer,
    private val delayMs: Long = DEFAULT_DELAY_MS,
) : Player.Listener {
    private val handler = Handler(player.applicationLooper)
    private val release = Runnable {
        if (isPausedAndLoaded()) player.stop()
    }

    init {
        player.addListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) reschedule()
    }

    private fun reschedule() {
        handler.removeCallbacks(release)
        if (isPausedAndLoaded()) handler.postDelayed(release, delayMs)
    }

    private fun isPausedAndLoaded() =
        !player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    fun dispose() {
        handler.removeCallbacks(release)
        player.removeListener(this)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30_000L

        /** Before a play: a player stopped by the release (or by an error) is prepared again. */
        fun prepareIfIdle(player: Player) {
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
        }
    }
}
