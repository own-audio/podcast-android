package audio.own.podcastplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import audio.own.player.core.AudioFocusOnFirstPlay
import audio.own.player.core.ContentKind
import audio.own.player.core.IdleDecoderRelease
import audio.own.player.core.PlaybackDataSources
import audio.own.player.core.PlaybackUris
import audio.own.player.core.PlayerSetup
import audio.own.player.core.SkipIntervals
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.BookTimeline
import audio.own.player.spoken.PlaybackSpeed
import audio.own.player.spoken.SleepDecision
import audio.own.player.spoken.SleepEnd
import audio.own.player.spoken.SmartRewind
import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.SpokenPart
import audio.own.player.spoken.SpokenSleepPolicy
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourcedEpisode
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The podcast player for now: one episode at a time on one ExoPlayer, the speed with the pitch
 * kept, the skip intervals as the session's numbered buttons, smart rewind after a pause, the
 * chapters the app found for the episode, and the sleep timer's three modes. It reports where
 * the listener is every 10 s of listening and on every pause, seek, stop, switch and finish.
 *
 * Built on `:player:core`'s pieces (`PlayerSetup`, focus on the first play, the idle release, the
 * resolving data sources) and `:player:spoken`'s rules; the spoken engine's Media3 player takes
 * its place once it exists. Use it from the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class EpisodePlayer(
    private val context: Context,
    dataSources: PlaybackDataSources,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> Instant = Instant::now,
) : PlaybackEngine {
    enum class Reason { PERIODIC, PAUSE, SEEK, STOP, SWITCH, FINISHED }

    data class Report(val id: String, val positionMs: Long, val durationMs: Long, val reason: Reason, val at: Instant)

    interface Listener {
        fun onPosition(report: Report)

        /** The episode played to its end; [playOn] false when the sleep timer stopped there. */
        fun onEnded(id: String, playOn: Boolean)

        /**
         * The server refused the link with `401` (a token revoked or expired early). The listener
         * may get a fresh one and [PlaybackEngine.retry]; otherwise the episode stays stopped.
         */
        fun onRefused(id: String, url: String) {}
    }

    enum class Problem { NO_SIGNAL, CANT_PLAY }

    data class State(
        val item: SourcedEpisode? = null,
        val show: Show? = null,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val rate: Float = PlaybackSpeed.DEFAULT,
        val chapters: List<SpokenChapter> = emptyList(),
        val sleepRemainingSecs: Double? = null,
        val sleepAtEndOfEpisode: Boolean = false,
        val sleepAtEndOfChapter: Boolean = false,
        val problem: Problem? = null,
        val skip: SkipIntervals = SkipIntervals(DEFAULT_SKIP_BACK, DEFAULT_SKIP_FORWARD),
    ) {
        val hasChapters: Boolean get() = chapters.isNotEmpty()

        val currentChapter: SpokenChapter? get() = chapters.lastOrNull { it.startMs <= positionMs }

        val sleeping: Boolean get() = sleepRemainingSecs != null || sleepAtEndOfEpisode || sleepAtEndOfChapter

        val waitingForSignal: Boolean get() = problem == Problem.NO_SIGNAL
    }

    override var listener: Listener? = null

    private val keepAhead = KeepAheadLoadControl()
    private val exo: ExoPlayer = PlayerSetup.buildPlayer(context, dataSources.mediaSourceFactory(), ContentKind.SPEECH, keepAhead)
    private val focus = AudioFocusOnFirstPlay(PlayerSetup.audioAttributes(ContentKind.SPEECH))
    private val idleRelease = IdleDecoderRelease(exo)
    private val sleepTimer = SleepTimer(scope)
    private val chapterSleep = SpokenSleepPolicy()
    override var smartRewind = SmartRewind()

    private val _state = MutableStateFlow(State())
    override val state: StateFlow<State> = _state.asStateFlow()

    private var pausedAt: Long? = null
    private var ticker: Job? = null
    private var lastTickPosition = 0L
    private var controller: ListenableFuture<MediaController>? = null

    /**
     * What the media session plays through: Play from the notification, Bluetooth or the lock
     * screen comes here, so it gets focus, smart rewind and the idle release's prepare like a
     * tap in the app. Previous and next are hidden, so the skip buttons take their places.
     */
    val sessionPlayer: Player = object : ForwardingPlayer(exo) {
        override fun play() = this@EpisodePlayer.play()

        override fun pause() = this@EpisodePlayer.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) this@EpisodePlayer.play() else this@EpisodePlayer.pause()

        override fun getAvailableCommands(): Player.Commands = withoutNeighbours(super.getAvailableCommands())

        override fun isCommandAvailable(command: Int): Boolean = command !in NEIGHBOUR_COMMANDS && super.isCommandAvailable(command)
    }

    init {
        sleepTimer.onExpire = { pause() }
        scope.launch {
            sleepTimer.state.collect { sleep ->
                _state.update { it.copy(sleepRemainingSecs = sleep.remainingSecs, sleepAtEndOfEpisode = sleep.stopsAtEndOfItem) }
            }
        }
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && _state.value.item != null && exo.playbackState != Player.STATE_ENDED) {
                    pausedAt = clock()
                    report(Reason.PAUSE)
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    lastTickPosition = newPosition.positionMs
                    report(Reason.SEEK)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
                if (playbackState == Player.STATE_READY) _state.update { it.copy(problem = null) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val refused = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull { it.responseCode == 401 }
                val item = _state.value.item
                if (refused != null && item != null) {
                    _state.update { it.copy(isPlaying = false, isLoading = true) }
                    listener?.onRefused(item.id, refused.dataSpec.uri.toString())
                    return
                }
                val noSignal = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                _state.update { it.copy(problem = if (noSignal) Problem.NO_SIGNAL else Problem.CANT_PLAY, isPlaying = false, isLoading = false) }
            }
        })
    }

    /**
     * Loads [item] at [startMs]. With [prepare] false nothing is opened on the network (the last
     * episode, back paused after a relaunch); the first Play prepares it. The start goes with
     * the item (`setMediaItems` with a position): a seek after `prepare` would be dropped.
     */
    override fun load(item: SourcedEpisode, show: Show?, startMs: Long, autoplay: Boolean, rate: Float, prepare: Boolean) {
        val current = _state.value.item
        if (current != null && current.id != item.id) report(Reason.SWITCH)
        val episode = item.episode
        val artwork = episode.imageUrl ?: show?.imageUrl
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(show?.title)
            .setAlbumTitle(show?.title)
            .setArtworkUri(artwork?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(PlaybackUris.forItem(item.id))
            .setMediaMetadata(metadata)
            .build()
        pausedAt = null
        chapterSleep.disarm()
        exo.setMediaItems(listOf(mediaItem), 0, startMs.coerceAtLeast(0))
        exo.setPlaybackSpeed(PlaybackSpeed.normalized(rate))
        lastTickPosition = startMs
        _state.update {
            it.copy(
                item = item,
                show = show,
                positionMs = startMs,
                durationMs = episode.durationMs ?: 0,
                rate = PlaybackSpeed.normalized(rate),
                chapters = emptyList(),
                sleepAtEndOfChapter = false,
                problem = null,
            )
        }
        if (prepare) exo.prepare()
        if (autoplay) play()
        refresh()
    }

    override fun setChapters(id: String, chapters: List<SpokenChapter>) {
        if (_state.value.item?.id == id) _state.update { it.copy(chapters = chapters) }
    }

    override fun play() {
        if (exo.mediaItemCount == 0) return
        ensureService()
        focus.beforePlay(exo)
        pausedAt?.let { since ->
            val back = smartRewind.rewindMs(clock() - since)
            if (back > 0) exo.seekTo((exo.currentPosition - back).coerceAtLeast(0))
        }
        pausedAt = null
        if (_state.value.problem != null) _state.update { it.copy(problem = null) }
        IdleDecoderRelease.prepareIfIdle(exo)
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun toggle() = if (exo.playWhenReady) pause() else play()

    override fun seekTo(positionMs: Long) {
        val duration = durationMs()
        exo.seekTo(if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
        refresh()
    }

    override fun skipBack() = seekTo(exo.currentPosition - _state.value.skip.backSecs * 1000L)

    override fun skipForward() = seekTo(exo.currentPosition + _state.value.skip.forwardSecs * 1000L)

    override fun setRate(rate: Float) {
        val normalized = PlaybackSpeed.normalized(rate)
        exo.setPlaybackSpeed(normalized)
        _state.update { it.copy(rate = normalized) }
    }

    override fun retry() {
        if (_state.value.item == null) return
        _state.update { it.copy(problem = null) }
        exo.prepare()
        exo.play()
    }

    override fun setSkipIntervals(skip: SkipIntervals) = _state.update { it.copy(skip = skip) }

    override fun setKeepAhead(ms: Long) {
        keepAhead.aheadMs = ms
    }

    /** After a lost connection: once the network is back, the same episode carries on. */
    override fun retryIfWaiting() {
        if (_state.value.problem != Problem.NO_SIGNAL) return
        _state.update { it.copy(problem = null) }
        exo.prepare()
        exo.play()
    }

    /** Lets go of the episode: the session, the notification and the lock screen with it. */
    override fun stop() {
        if (_state.value.item != null) report(Reason.STOP)
        exo.stop()
        exo.clearMediaItems()
        sleepTimer.cancel()
        chapterSleep.disarm()
        pausedAt = null
        _state.update { State(skip = it.skip, rate = it.rate) }
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // region Sleep timer

    override fun sleepAfter(preset: SleepTimer.Preset) {
        stopAtEndOfChapter(false)
        sleepTimer.start(preset)
    }

    override fun sleepAtEndOfEpisode() {
        stopAtEndOfChapter(false)
        sleepTimer.startUntilEndOfItem()
    }

    override fun stopAtEndOfChapter(on: Boolean) {
        if (on) {
            sleepTimer.cancel()
            chapterSleep.arm(SleepEnd.END_OF_CHAPTER)
        } else {
            chapterSleep.disarm()
        }
        _state.update { it.copy(sleepAtEndOfChapter = on) }
    }

    override fun extendSleep() = sleepTimer.extend(SleepTimer.EXTEND_SECS)

    override fun cancelSleep() {
        sleepTimer.cancel()
        stopAtEndOfChapter(false)
    }

    // endregion

    private fun ended() {
        val item = _state.value.item ?: return
        report(Reason.FINISHED, positionMs = durationMs().takeIf { it > 0 } ?: exo.currentPosition)
        // The sleep timer's end comes before Up Next: stopping here is what it was set for.
        val byTimer = sleepTimer.consumeEndOfItem()
        val byChapter = chapterSleep.isArmed && chapterSleep.onPartEnded(timeline(), 0) == SleepDecision.PAUSE
        if (byChapter) _state.update { it.copy(sleepAtEndOfChapter = false) }
        listener?.onEnded(item.id, playOn = !byTimer && !byChapter)
    }

    private fun refresh() {
        val playing = exo.isPlaying
        val loading = exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING
        _state.update { it.copy(isPlaying = playing, isLoading = loading, positionMs = exo.currentPosition, durationMs = durationMs()) }
        if (playing) startTicking() else stopTicking()
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                val position = exo.currentPosition
                _state.update { it.copy(positionMs = position, durationMs = durationMs()) }
                if (chapterSleep.isArmed && chapterSleep.onTick(timeline(), lastTickPosition, position) == SleepDecision.PAUSE) {
                    _state.update { it.copy(sleepAtEndOfChapter = false) }
                    pause()
                }
                lastTickPosition = position
                if (++seconds % REPORT_EVERY_SECS == 0) report(Reason.PERIODIC)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun report(reason: Reason, positionMs: Long = exo.currentPosition) {
        val item = _state.value.item ?: return
        listener?.onPosition(Report(item.id, positionMs, durationMs(), reason, now()))
    }

    private fun durationMs(): Long {
        val known = exo.duration
        return if (known != C.TIME_UNSET && known > 0) known else _state.value.item?.episode?.durationMs ?: 0
    }

    private fun timeline(): BookTimeline {
        val state = _state.value
        val part = SpokenPart(id = "episode", uri = state.item?.id.orEmpty(), durationMs = durationMs())
        return BookTimeline(listOf(part), state.chapters)
    }

    /**
     * The media service runs while a controller is bound to it; this one keeps it there for as
     * long as an episode is loaded, so Media3 can put the notification up when playback starts.
     */
    private fun ensureService() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PodcastService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    companion object {
        const val DEFAULT_SKIP_BACK = 15
        const val DEFAULT_SKIP_FORWARD = 30
        private const val REPORT_EVERY_SECS = 10

        private val NEIGHBOUR_COMMANDS = setOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        fun withoutNeighbours(commands: Player.Commands): Player.Commands =
            commands.buildUpon().removeAll(*NEIGHBOUR_COMMANDS.toIntArray()).build()
    }
}

/** What the app asks of its player; [EpisodePlayer] on the device, a fake in the model's tests. */
interface PlaybackEngine {
    val state: StateFlow<EpisodePlayer.State>
    var listener: EpisodePlayer.Listener?
    var smartRewind: SmartRewind

    fun load(item: SourcedEpisode, show: Show?, startMs: Long, autoplay: Boolean, rate: Float, prepare: Boolean = true)
    fun setChapters(id: String, chapters: List<SpokenChapter>)
    fun play()
    fun pause()
    fun toggle()
    fun seekTo(positionMs: Long)
    fun skipBack()
    fun skipForward()
    fun setRate(rate: Float)
    fun retryIfWaiting()

    /** Opens the episode again where it is, after the listener renewed what was refused. */
    fun retry()
    fun setSkipIntervals(skip: SkipIntervals)

    /** How far ahead of the playhead a stream is loaded (Settings' Keep Ahead). */
    fun setKeepAhead(ms: Long)
    fun stop()
    fun sleepAfter(preset: SleepTimer.Preset)
    fun sleepAtEndOfEpisode()
    fun stopAtEndOfChapter(on: Boolean)
    fun extendSleep()
    fun cancelSleep()
}
