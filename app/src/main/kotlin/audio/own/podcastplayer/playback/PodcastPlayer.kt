package audio.own.podcastplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import audio.own.player.core.PlaybackUris
import audio.own.player.core.SkipIntervals
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.ChapterOrigin
import audio.own.player.spoken.PlaybackSpeed
import audio.own.player.spoken.ReportReason
import audio.own.player.spoken.SleepEnd
import audio.own.player.spoken.SmartRewind
import audio.own.player.spoken.SpokenBook
import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.SpokenPart
import audio.own.player.spoken.SpokenPlayer
import audio.own.player.spoken.SpokenPosition
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourcedEpisode
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The podcast player: `:player:spoken`'s [SpokenPlayer] with one part per episode. The spoken
 * player brings the speed with the pitch kept, smart rewind, the sleep timer's modes, Keep Ahead,
 * the wait for a signal, the file's own chapters and the one retry of a refused link; this class
 * adds what is an episode's rather than a book's: the show, the position reports the app's
 * reasons know, the episode held paused after a relaunch until Play, and a download taking over
 * from the stream.
 *
 * The part starts with the feed's duration, which feeds often leave out or get wrong (an
 * inserted ad makes the file longer); the spoken player takes the file's real length once it
 * has opened it. Use it from the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PodcastPlayer(
    private val context: Context,
    val spoken: SpokenPlayer,
    private val scope: CoroutineScope,
    /** The media service kept running while an episode is loaded; null in tests. */
    private val service: ComponentName? = ComponentName(context, PodcastService::class.java),
) : PlaybackEngine {
    enum class Reason { PERIODIC, PAUSE, SEEK, STOP, SWITCH, FINISHED }

    data class Report(val id: String, val positionMs: Long, val durationMs: Long, val reason: Reason, val at: Instant)

    interface Listener {
        fun onPosition(report: Report)

        /** The episode played to its end; [playOn] false when the sleep timer stopped there. */
        fun onEnded(id: String, playOn: Boolean)

        /**
         * The link failed (an Audiobookshelf `401`: a token revoked or expired early). The player
         * asks for a new link once, right after this returns, so a listener that renews must
         * note it now; a second failure stays an error.
         */
        fun onLinkFailed(id: String, url: String?, status: Int?) {}
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
        /** How much of what comes next is on the device already: how long it plays on with no signal. */
        val savedAheadMs: Long? = null,
    ) {
        val hasChapters: Boolean get() = chapters.isNotEmpty()

        val currentChapter: SpokenChapter? get() = chapters.lastOrNull { it.startMs <= positionMs }

        val sleeping: Boolean get() = sleepRemainingSecs != null || sleepAtEndOfEpisode || sleepAtEndOfChapter

        val waitingForSignal: Boolean get() = problem == Problem.NO_SIGNAL
    }

    /** An episode loaded but not opened yet: the last one, back paused after a relaunch. */
    private data class Held(val item: SourcedEpisode, val show: Show?, val startMs: Long, val rate: Float, val chapters: List<SpokenChapter>)

    override var listener: Listener? = null

    /** The episode's file on the device, or null to stream it (through the listening cache). */
    var localFile: (String) -> Uri? = { null }

    override var smartRewind: SmartRewind
        get() = spoken.smartRewind
        set(value) {
            spoken.smartRewind = value
        }

    private val _state = MutableStateFlow(State())
    override val state: StateFlow<State> = _state.asStateFlow()

    private var held: Held? = null

    /** The last episode is back paused after a relaunch, not opened yet: the first Play loads it. */
    val isHeld: Boolean get() = held != null
    private var item: SourcedEpisode? = null
    private var show: Show? = null
    private var skip = SkipIntervals(DEFAULT_SKIP_BACK, DEFAULT_SKIP_FORWARD)
    private var rate = PlaybackSpeed.DEFAULT

    /** Set while another episode replaces this one: the spoken player's STOP is a switch. */
    private var switching = false

    /** Set while the same episode is loaded again (its download arrived): its STOP is a seek. */
    private var reloading = false

    private var controller: ListenableFuture<MediaController>? = null

    init {
        spoken.onPosition = ::reported
        spoken.onFinished = ::finished
        spoken.onLinkFailed = { _, error ->
            val id = spoken.bookId
            if (id != null) {
                val refused = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                listener?.onLinkFailed(id, refused?.dataSpec?.uri?.toString(), refused?.responseCode)
            }
        }
        scope.launch { spoken.state.collect { publish(it) } }
    }

    // region Loading

    /**
     * Loads [item] at [startMs]. With [prepare] false nothing opens on the network (the last
     * episode, back paused after a relaunch); the first Play loads it. [chapters] are the feed's
     * or the server's; with none, the player reads the file's own.
     */
    override fun load(item: SourcedEpisode, show: Show?, startMs: Long, autoplay: Boolean, rate: Float, prepare: Boolean, chapters: List<SpokenChapter>) {
        val normalized = PlaybackSpeed.normalized(rate)
        this.rate = normalized
        if (!prepare) {
            if (spoken.bookId != null) switchingDo { spoken.stop() }
            held = Held(item, show, startMs.coerceAtLeast(0), normalized, chapters)
            this.item = item
            this.show = show
            publish(spoken.state.value)
            return
        }
        held = null
        val before = spoken.bookId
        this.item = item
        this.show = show
        val start = startMs.coerceAtLeast(0)
        val book = book(item, show, chapters, start)
        when (before) {
            null -> spoken.load(book, start, autoplay, normalized)
            item.id -> reloadingDo { spoken.load(book, start, autoplay, normalized) }
            else -> switchingDo { spoken.load(book, start, autoplay, normalized) }
        }
        // ExoPlayer keeps "play when ready" across a stop and a new item: one loaded not to play
        // would start by itself after an episode that was playing.
        if (!autoplay && spoken.player.playWhenReady) spoken.player.playWhenReady = false
        if (autoplay) ensureService()
        publish(spoken.state.value)
    }

    private fun book(item: SourcedEpisode, show: Show?, chapters: List<SpokenChapter>, startMs: Long): SpokenBook {
        val episode = item.episode
        val local = localFile(item.id)
        // A position past the feed's length (the file is longer than it says) would be held to
        // it; unknown, the part is open-ended until the player tells the real length.
        val stated = episode.durationMs?.takeIf { it > startMs } ?: 0
        val part = SpokenPart(
            id = PART_ID,
            uri = local?.toString() ?: PlaybackUris.forItem(item.id).toString(),
            durationMs = stated,
            streamsByRange = true,
            contentType = episode.audioType,
        )
        return SpokenBook(
            id = item.id,
            title = episode.title,
            author = show?.title,
            artworkUri = episode.imageUrl ?: show?.imageUrl,
            parts = listOf(part),
            serverChapters = chapters,
        )
    }

    private inline fun switchingDo(work: () -> Unit) {
        switching = true
        try {
            work()
        } finally {
            switching = false
        }
    }

    private inline fun reloadingDo(work: () -> Unit) {
        reloading = true
        try {
            work()
        } finally {
            reloading = false
        }
    }

    /** What the system's media controls resume after the app was killed: the item loaded now. */
    override fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? {
        val player = spoken.player
        val current = player.currentMediaItem ?: return null
        return MediaSession.MediaItemsWithStartPosition(listOf(current), 0, player.currentPosition.coerceAtLeast(0))
    }

    /**
     * A download of [id] finished. The loaded episode moves to the file at once when paused, else
     * at the next pause or seek: never mid-sentence. Waiting for a signal, the file ends the wait.
     */
    override fun useLocalCopy(id: String) {
        if (item?.id != id || held != null || spoken.bookId != id) return
        val local = localFile(id) ?: return
        spoken.useLocalCopy(0, local.toString())
    }

    /**
     * The feed's chapters for [id], arrived after it started (a chapters document fetched
     * meanwhile): next and previous, End of Chapter and the chapter list go by them from now on.
     */
    override fun setChapters(id: String, chapters: List<SpokenChapter>) {
        if (item?.id != id) return
        val waiting = held
        if (waiting != null) {
            held = waiting.copy(chapters = chapters)
            publish(spoken.state.value)
            return
        }
        if (spoken.bookId == id) spoken.setChapters(chapters)
    }

    // endregion

    // region Controls

    override fun play() {
        held?.let { waiting ->
            held = null
            load(waiting.item, waiting.show, waiting.startMs, autoplay = true, rate = waiting.rate, prepare = true, chapters = waiting.chapters)
            return
        }
        if (spoken.bookId == null) return
        ensureService()
        spoken.play()
    }

    override fun pause() {
        if (held != null) return
        spoken.pause()
    }

    override fun toggle() = if (held == null && spoken.player.playWhenReady) pause() else play()

    override fun seekTo(positionMs: Long) {
        val waiting = held
        if (waiting != null) {
            held = waiting.copy(startMs = clamp(positionMs))
            publish(spoken.state.value)
            return
        }
        spoken.seek(clamp(positionMs))
    }

    override fun skipBack() = seekTo(_state.value.positionMs - skip.backSecs * 1000L)

    override fun skipForward() = seekTo(_state.value.positionMs + skip.forwardSecs * 1000L)

    override fun setRate(rate: Float) {
        val normalized = PlaybackSpeed.normalized(rate)
        this.rate = normalized
        held?.let { held = it.copy(rate = normalized) }
        spoken.setSpeed(normalized)
        publish(spoken.state.value)
    }

    override fun networkChanged(connected: Boolean) = spoken.networkChanged(connected)

    override fun setSkipIntervals(skip: SkipIntervals) {
        this.skip = skip
        spoken.skipBackSecs = skip.backSecs
        spoken.skipForwardSecs = skip.forwardSecs
        publish(spoken.state.value)
    }

    override fun setKeepAhead(ms: Long) {
        spoken.loadControl?.lookAheadMs = ms
    }

    /** Lets go of the episode: the session, the notification and the lock screen with it. */
    override fun stop() {
        held = null
        item = null
        show = null
        spoken.stop()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        publish(spoken.state.value)
    }

    override fun sleepAfter(preset: SleepTimer.Preset) = spoken.sleep(preset)

    override fun sleepAtEndOfEpisode() = spoken.sleepAt(SleepEnd.END_OF_BOOK)

    override fun stopAtEndOfChapter(on: Boolean) {
        if (on) {
            spoken.sleepAt(SleepEnd.END_OF_CHAPTER)
        } else if (spoken.state.value.sleepEnd == SleepEnd.END_OF_CHAPTER) {
            spoken.cancelSleep()
        }
    }

    override fun extendSleep() = spoken.extendSleep()

    override fun cancelSleep() = spoken.cancelSleep()

    // endregion

    // region Reports

    private fun reported(position: SpokenPosition) {
        val reason = when (position.reason) {
            ReportReason.PERIODIC -> Reason.PERIODIC
            ReportReason.PAUSE, ReportReason.TEARDOWN -> Reason.PAUSE
            ReportReason.SEEK, ReportReason.CHAPTER -> Reason.SEEK
            ReportReason.STOP -> when {
                reloading -> Reason.SEEK
                switching -> Reason.SWITCH
                else -> Reason.STOP
            }
            ReportReason.FINISHED -> Reason.FINISHED
        }
        val duration = realDurationMs(position.bookId)
        val at = if (reason == Reason.FINISHED && duration > 0) duration else position.positionMs.let { if (duration > 0) it.coerceAtMost(duration) else it }
        listener?.onPosition(Report(position.bookId, at, duration, reason, Instant.ofEpochMilli(position.atMs)))
    }

    private fun finished(id: String) {
        // The sleep timer's end comes before Up Next: stopping here is what it was set for. The
        // spoken player uses up an armed end as the episode ends, after this state was published.
        val stoppedBySleep = spoken.state.value.sleepEnd != null
        listener?.onEnded(id, playOn = !stoppedBySleep)
    }

    // endregion

    private fun publish(s: SpokenPlayer.State) {
        val waiting = held
        if (waiting != null) {
            _state.value = State(
                item = waiting.item,
                show = waiting.show,
                positionMs = waiting.startMs,
                durationMs = waiting.item.episode.durationMs ?: 0,
                rate = waiting.rate,
                chapters = waiting.chapters,
                skip = skip,
            )
            return
        }
        val book = s.book
        val current = item
        if (book == null || current == null || book.id != current.id) {
            _state.value = State(skip = skip, rate = rate)
            return
        }
        val duration = realDurationMs(book.id)
        val position = if (duration > 0) s.positionMs.coerceAtMost(duration) else s.positionMs
        val chapters = if (s.chapterOrigin == ChapterOrigin.PARTS) {
            emptyList()
        } else {
            s.chapters.filter { duration <= 0 || it.startMs < duration }.map { if (duration > 0 && it.endMs > duration) it.copy(endMs = duration) else it }
        }
        _state.value = State(
            item = current,
            show = show,
            isPlaying = s.playWhenReady,
            isLoading = s.isLoading,
            positionMs = position,
            durationMs = duration,
            rate = s.speed,
            chapters = chapters,
            sleepRemainingSecs = s.sleepRemainingSecs,
            sleepAtEndOfEpisode = s.sleepEnd == SleepEnd.END_OF_BOOK,
            sleepAtEndOfChapter = s.sleepEnd == SleepEnd.END_OF_CHAPTER,
            problem = when {
                s.waitingForSignal -> Problem.NO_SIGNAL
                s.error != null -> Problem.CANT_PLAY
                else -> null
            },
            skip = skip,
            savedAheadMs = s.savedAheadMs.takeIf { !s.waitingForSignal && book.parts.firstOrNull()?.uri?.startsWith("file:") != true },
        )
    }

    /** The file's length once the player knows it, else what the feed or the server said. */
    private fun realDurationMs(id: String): Long {
        val known = spoken.state.value.durationMs
        if (known > 0 && spoken.bookId == id) return known
        return item?.takeIf { it.id == id }?.episode?.durationMs ?: 0
    }

    private fun clamp(positionMs: Long): Long {
        val duration = _state.value.durationMs
        return if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
    }

    /**
     * The media service runs while a controller is bound to it; this one keeps it there for as
     * long as an episode is loaded, so Media3 can put the notification up when playback starts.
     */
    private fun ensureService() {
        if (controller != null) return
        val token = SessionToken(context, service ?: return)
        controller = MediaController.Builder(context, token).buildAsync()
    }

    companion object {
        const val DEFAULT_SKIP_BACK = 15
        const val DEFAULT_SKIP_FORWARD = 30
        const val PART_ID = "episode"

        private val NEIGHBOUR_COMMANDS = setOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        /** Previous and next stay hidden, so the skip buttons take their places in the notification. */
        fun withoutNeighbours(commands: Player.Commands): Player.Commands =
            commands.buildUpon().removeAll(*NEIGHBOUR_COMMANDS.toIntArray()).build()
    }
}

/** What the app asks of its player; [PodcastPlayer] on the device, a fake in the model's tests. */
interface PlaybackEngine {
    val state: StateFlow<PodcastPlayer.State>
    var listener: PodcastPlayer.Listener?
    var smartRewind: SmartRewind

    fun load(item: SourcedEpisode, show: Show?, startMs: Long, autoplay: Boolean, rate: Float, prepare: Boolean = true, chapters: List<SpokenChapter> = emptyList())
    fun play()
    fun pause()
    fun toggle()
    fun seekTo(positionMs: Long)
    fun skipBack()
    fun skipForward()
    fun setRate(rate: Float)

    /** The network came or went: without one the player waits, and goes on when it is back. */
    fun networkChanged(connected: Boolean)
    fun setSkipIntervals(skip: SkipIntervals)

    /** How far ahead of the playhead a stream is loaded (Settings' Keep Ahead). */
    fun setKeepAhead(ms: Long)
    fun stop()
    fun sleepAfter(preset: SleepTimer.Preset)
    fun sleepAtEndOfEpisode()
    fun stopAtEndOfChapter(on: Boolean)
    fun extendSleep()
    fun cancelSleep()

    /** A download of [id] finished: the episode loaded now plays from the file from the next pause on. */
    fun useLocalCopy(id: String) {}

    /** Chapters for [id] that arrived after it was loaded. */
    fun setChapters(id: String, chapters: List<SpokenChapter>) {}

    /** The loaded item and its position, for Media3's playback resumption. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? = null
}
