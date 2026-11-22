package audio.own.player.spoken

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import audio.own.player.core.AudioFocusOnFirstPlay
import audio.own.player.core.ContentKind
import audio.own.player.core.IdleDecoderRelease
import audio.own.player.core.PlaybackLinks
import audio.own.player.core.PlaybackUris
import audio.own.player.core.PlayerSetup
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.chapters.ByteRangeReader
import audio.own.player.spoken.chapters.EmbeddedChapters
import audio.own.player.spoken.media.LookAheadLoadControl
import audio.own.player.spoken.media.Prefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One book at a time on one ExoPlayer whose playlist is the book's parts, end to end. Everything
 * outside speaks book time (milliseconds from the start of the book, across its files); only
 * this class converts to a part and an offset, through [BookTimeline].
 *
 * Speed keeps the pitch, skips cross file boundaries, a resume after a pause rewinds a little
 * ([SmartRewind]), the sleep timer can stop at the end of a chapter or of the book, and every
 * position worth keeping is handed to [onPosition] (every [tickIntervalMs] of listening, and on
 * pause, seek, chapter, stop, the end and teardown).
 *
 * With no network the player is held buffering rather than failed (see
 * [audio.own.player.spoken.media.SignalWaitPolicy]); the app calls [networkChanged] and the book
 * goes on by itself when the signal returns, unless the listener paused meanwhile.
 *
 * Use from the main thread.
 */
@UnstableApi
class SpokenPlayer(
    val player: ExoPlayer,
    private val scope: CoroutineScope,
    /** The links the parts' placeholders resolve through; forgotten for a part that failed. */
    private val links: PlaybackLinks? = null,
    private val prefetcher: Prefetcher? = null,
    /** Opens a part for the chapter readers (a file, or HTTP ranges); null reads no file chapters. */
    private val openPart: (suspend (SpokenPart) -> ByteRangeReader?)? = null,
    /** The look-ahead window, to read and change "Keep Ahead". */
    val loadControl: LookAheadLoadControl? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val book: SpokenBook? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val chapters: List<SpokenChapter> = emptyList(),
        val chapterOrigin: ChapterOrigin = ChapterOrigin.PARTS,
        /** The listener wants it playing (it may still be loading). */
        val playWhenReady: Boolean = false,
        /** Starting, or waiting for bytes while meant to play. */
        val isLoading: Boolean = false,
        val waitingForSignal: Boolean = false,
        val speed: Float = PlaybackSpeed.DEFAULT,
        val sleepEnd: SleepEnd? = null,
        val sleepRemainingSecs: Double? = null,
        val savedAheadMs: Long? = null,
        val error: PlaybackException? = null,
        val finished: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var skipBackSecs: Int = 15
    var skipForwardSecs: Int = 30
    var smartRewind: SmartRewind = SmartRewind()
    var tickIntervalMs: Long = 10_000

    /**
     * Whether a stated part length more than [DURATION_TOLERANCE_MS] off the file's real one gives
     * way to it once the player knows it. A missing length (0) always does.
     */
    var adoptsPlayerDurations: Boolean = true

    /** Whether whole parts may be fetched ahead (an unmetered network, no Data Saver). */
    var canPrefetchWhole: () -> Boolean = { false }

    var onPosition: ((SpokenPosition) -> Unit)? = null
    var onFinished: ((String) -> Unit)? = null

    /** A part's link failed and is asked for once more (Audiobookshelf takes a fresh token then). */
    var onLinkFailed: ((part: SpokenPart, error: PlaybackException) -> Unit)? = null

    /** "Part N" and "Chapter N" in the listener's language; gets the zero-based index. */
    var partTitle: (Int) -> String = { "Part ${it + 1}" }
    var chapterTitle: (Int) -> String = { "Chapter ${it + 1}" }

    val sleepTimer = SleepTimer(scope).also { it.onExpire = { pause(); publish() } }
    private val sleepPolicy = SpokenSleepPolicy()

    private val focus = AudioFocusOnFirstPlay(PlayerSetup.audioAttributes(ContentKind.SPEECH))
    private val idleRelease = IdleDecoderRelease(player)

    private var book: SpokenBook? = null
    private var timeline: BookTimeline? = null
    private var chapterOrigin = ChapterOrigin.PARTS
    private var chapterJob: Job? = null
    private var tickJob: Job? = null

    private var pausedAt: Long? = null
    private var playingSince: Long? = null
    private var listenedMs = 0L
    private var lastReportAt = 0L
    private var lastTickPositionMs = 0L
    private var listenedInPartMs = 0L
    private var retriedLink = false
    private var error: PlaybackException? = null
    private var finished = false
    private var lastSavedAheadAt = 0L
    private var savedAheadMs: Long? = null

    /** Parts whose local copy waits for the next pause or seek: the part playing, mid-sentence. */
    private val localWaiting = HashMap<Int, String>()

    // Waiting for a signal.
    private var connected = true
    private var waitingSince: Long? = null
    private var waitingFailed = false
    private var resumesAfterWait = false

    /** What the media session drives: chapters for next/previous, the skips for seek back/forward. */
    val sessionPlayer: Player = SessionPlayer()

    init {
        player.addListener(Events())
        PlayerSetup.configure(player, ContentKind.SPEECH)
    }

    val bookId: String? get() = book?.id
    val currentBook: SpokenBook? get() = book

    // region Loading and playing

    /** Loads [book] at [positionMs] (book time) at [speed], and plays if [autoplay]. */
    fun load(book: SpokenBook, positionMs: Long, autoplay: Boolean, speed: Float = PlaybackSpeed.DEFAULT) {
        if (this.book != null) report(ReportReason.STOP)
        prefetcher?.cancel()
        chapterJob?.cancel()
        this.book = book
        error = null
        finished = false
        pausedAt = null
        retriedLink = false
        listenedMs = 0
        listenedInPartMs = 0
        playingSince = null
        savedAheadMs = null
        localWaiting.clear()
        resetWait()
        sleepPolicy.disarm()
        val serverChapters = book.serverChapters
        chapterOrigin = if (serverChapters.isNotEmpty()) ChapterOrigin.SERVER else ChapterOrigin.PARTS
        val chapters = serverChapters.ifEmpty { BookTimeline.chaptersFromParts(book.parts, book.partTitles, partTitle) }
        val line = BookTimeline(book.parts, chapters).also { timeline = it }
        val start = line.locate(positionMs) ?: PartPosition(0, 0)
        lastTickPositionMs = line.position(start.index, start.offsetMs)
        player.setMediaItems(book.parts.mapIndexed { i, part -> mediaItem(book, part, i) }, start.index, start.offsetMs)
        player.playbackParameters = PlaybackParameters(PlaybackSpeed.normalized(speed), 1f)
        player.prepare()
        lastReportAt = now()
        if (autoplay) play()
        if (serverChapters.isEmpty() && openPart != null) readFileChapters(book)
        publish()
    }

    private fun readFileChapters(book: SpokenBook) {
        val open = openPart ?: return
        chapterJob = scope.launch {
            val found = EmbeddedChapters.readWithinLimit(book.parts, open, chapterTitle)
            if (found.isEmpty() || this@SpokenPlayer.book?.id != book.id) return@launch
            // Lengths the player told meanwhile move the marks of the parts after them.
            val current = timeline?.parts ?: book.parts
            var line = BookTimeline(book.parts, found)
            for (i in current.indices) {
                if (i < line.parts.size && current[i].durationMs != line.parts[i].durationMs) line = line.withPartDuration(i, current[i].durationMs)
            }
            timeline = BookTimeline(current, line.chapters)
            chapterOrigin = ChapterOrigin.FILES
            commandsChanged()
            updateChapterMetadata()
            publish()
        }
    }

    /**
     * Replaces the chapters of the book loaded now (a feed's chapters that arrived after it
     * started): next and previous, End of Chapter and the session go by them from now on. Empty
     * goes back to one chapter per part. File chapters still being read are no longer awaited.
     */
    fun setChapters(chapters: List<SpokenChapter>) {
        val book = book ?: return
        val line = timeline ?: return
        chapterJob?.cancel()
        if (chapters.isEmpty()) {
            chapterOrigin = ChapterOrigin.PARTS
            timeline = BookTimeline(line.parts, BookTimeline.chaptersFromParts(line.parts, book.partTitles, partTitle))
        } else {
            chapterOrigin = ChapterOrigin.SERVER
            timeline = line.withChapters(chapters)
        }
        this.book = book.copy(serverChapters = chapters)
        commandsChanged()
        updateChapterMetadata()
        publish()
    }

    /**
     * Part [partIndex] is on the phone now, at [uri] (a download finished). A part not playing
     * plays from it at once; the part playing moves to it at the next pause or seek, never
     * mid-sentence, at the same place. Waiting for a signal for that part, the copy ends the wait.
     */
    fun useLocalCopy(partIndex: Int, uri: String) {
        val book = book ?: return
        val line = timeline ?: return
        val part = line.parts.getOrNull(partIndex) ?: return
        if (part.uri == uri) return
        val local = part.copy(uri = uri, isHls = false)
        timeline = line.withPart(partIndex, local)
        this.book = book.copy(parts = timeline?.parts ?: book.parts)
        prefetcher?.cancel()
        if (partIndex != player.currentMediaItemIndex) {
            swapSource(partIndex, uri)
        } else if (waitingSince != null) {
            localWaiting[partIndex] = uri
            val resume = resumesAfterWait
            val position = positionMs()
            resetWait()
            error = null
            retriedLink = false
            swapWaiting()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            seek(position, report = false)
            if (resume) {
                focus.beforePlay(player)
                player.play()
            }
        } else if (player.playWhenReady) {
            localWaiting[partIndex] = uri
        } else {
            localWaiting[partIndex] = uri
            swapWaiting()
        }
        publish()
    }

    /** [useLocalCopy] for the part with [partId]. */
    fun useLocalCopy(partId: String, uri: String) {
        val index = timeline?.indexOfPart(partId) ?: return
        useLocalCopy(index, uri)
    }

    /** The copies waiting for a break: the part playing goes back to where it was. */
    private fun swapWaiting() {
        if (localWaiting.isEmpty() || finished) return
        val waiting = localWaiting.toMap()
        localWaiting.clear()
        val current = player.currentMediaItemIndex
        val at = player.currentPosition.coerceAtLeast(0)
        for ((index, uri) in waiting) swapSource(index, uri)
        if (current in waiting) player.seekTo(current, at)
    }

    private fun swapSource(index: Int, uri: String) {
        if (index !in 0 until player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        if (item.localConfiguration?.uri?.toString() == uri) return
        player.replaceMediaItem(index, item.buildUpon().setUri(uri).setMimeType(null).build())
    }

    fun play() {
        val line = timeline ?: return
        if (waitingSince != null) {
            resumesAfterWait = true
            // A failed item is loaded again; an intact one plays what it has and stalls again if
            // nothing comes.
            if (waitingFailed) {
                reloadAfterWait(rewindMs = 0)
                return
            }
        }
        if (finished || player.playbackState == Player.STATE_ENDED) {
            finished = false
            seek(0, report = false)
        }
        pausedAt?.let { at ->
            val rewind = smartRewind.rewindMs(now() - at)
            if (rewind > 0) seek(line.clamp(positionMs() - rewind), report = false)
        }
        pausedAt = null
        if (error != null) {
            error = null
            retriedLink = false
        }
        focus.beforePlay(player)
        IdleDecoderRelease.prepareIfIdle(player)
        player.play()
        lastReportAt = now()
        startTicking()
        publish()
    }

    fun pause() {
        resumesAfterWait = false
        if (!player.playWhenReady) {
            publish()
            return
        }
        player.pause()
        pausedAt = now()
        report(ReportReason.PAUSE)
        swapWaiting()
        publish()
    }

    fun toggle() = if (player.playWhenReady) pause() else play()

    /** Ends the book: the position is reported once more (unless [reporting] is false). */
    fun stop(reporting: Boolean = true) {
        if (book == null) return
        if (reporting) report(ReportReason.STOP)
        player.stop()
        player.clearMediaItems()
        prefetcher?.cancel()
        chapterJob?.cancel()
        book = null
        timeline = null
        pausedAt = null
        playingSince = null
        error = null
        finished = false
        resetWait()
        sleepTimer.cancel()
        sleepPolicy.disarm()
        savedAheadMs = null
        localWaiting.clear()
        publish()
    }

    /** Going to the background or being killed: the position is saved; audio keeps playing. */
    fun saveForTeardown() {
        if (book != null) report(ReportReason.TEARDOWN)
    }

    fun release() {
        stop()
        idleRelease.dispose()
        tickJob?.cancel()
        player.release()
    }

    // endregion

    // region Moving

    /** The position in book time. */
    fun positionMs(): Long {
        val line = timeline ?: return 0
        if (player.mediaItemCount == 0) return 0
        if (finished) return line.durationMs
        return line.position(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
    }

    fun seek(positionMs: Long) = seek(positionMs, report = true)

    private fun seek(target: Long, report: Boolean) {
        val line = timeline ?: return
        val located = line.locate(target) ?: return
        val clamped = line.position(located.index, located.offsetMs)
        val chapterBefore = line.chapterIndex(positionMs())
        if (located.index != player.currentMediaItemIndex) listenedInPartMs = 0
        finished = false
        // A seek breaks the sentence anyway: a copy that arrived meanwhile is taken now.
        if (localWaiting.isNotEmpty()) {
            localWaiting.forEach { (index, uri) -> swapSource(index, uri) }
            localWaiting.clear()
        }
        player.seekTo(located.index, located.offsetMs)
        lastTickPositionMs = clamped
        if (report) report(if (line.chapterIndex(clamped) != chapterBefore) ReportReason.CHAPTER else ReportReason.SEEK)
        publish()
    }

    fun skipBack() = skip(-skipBackSecs.toDouble())

    fun skipForward() = skip(skipForwardSecs.toDouble())

    fun skip(bySeconds: Double) {
        val line = timeline ?: return
        seek(line.skipped(positionMs(), bySeconds))
    }

    fun nextChapter() {
        val line = timeline ?: return
        seek(line.nextChapterTarget(positionMs()))
    }

    fun previousChapter() {
        val line = timeline ?: return
        seek(line.previousChapterStart(positionMs()))
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(PlaybackSpeed.normalized(speed), 1f)
        publish()
    }

    // endregion

    // region Sleep timer

    fun sleep(preset: SleepTimer.Preset) = sleepFor(preset.secs)

    fun sleepFor(secs: Double) {
        sleepPolicy.disarm()
        sleepTimer.start(secs)
        publish()
    }

    /** End of Chapter or End of Book; replaces a countdown. */
    fun sleepAt(end: SleepEnd) {
        sleepTimer.cancel()
        sleepPolicy.arm(end)
        publish()
    }

    fun extendSleep() {
        sleepTimer.extend(SleepTimer.EXTEND_SECS)
        publish()
    }

    fun cancelSleep() {
        sleepTimer.cancel()
        sleepPolicy.disarm()
        publish()
    }

    // endregion

    // region Network

    /** The app's network monitor: no network holds the player; its return lets it go on. */
    fun networkChanged(isConnected: Boolean) {
        val was = connected
        connected = isConnected
        if (isConnected && !was && waitingSince != null) {
            val waited = now() - (waitingSince ?: now())
            reloadAfterWait(rewindMs = smartRewind.rewindMs(waited))
        } else {
            checkWaiting()
        }
        publish()
    }

    val isConnected: Boolean get() = connected

    private fun checkWaiting() {
        if (waitingSince != null || book == null) return
        if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && !connected) {
            startWaiting(failed = false)
        }
    }

    private fun startWaiting(failed: Boolean) {
        if (waitingSince == null) {
            waitingSince = now()
            resumesAfterWait = player.playWhenReady
            // Saved as at a pause; waiting doesn't count as listening.
            report(ReportReason.PAUSE)
        }
        if (failed) waitingFailed = true
    }

    private fun reloadAfterWait(rewindMs: Long) {
        val line = timeline ?: return
        val failed = waitingFailed
        val resume = resumesAfterWait
        val position = line.clamp(positionMs() - rewindMs)
        resetWait()
        if (failed) {
            error = null
            player.prepare()
        }
        // A seek also starts the loader at once rather than at its next retry, up to 30 s away.
        seek(position, report = false)
        if (resume) {
            focus.beforePlay(player)
            player.play()
        }
        publish()
    }

    private fun resetWait() {
        waitingSince = null
        waitingFailed = false
    }

    // endregion

    // region Reports

    private fun report(reason: ReportReason, finished: Boolean = false) {
        val book = book ?: return
        val line = timeline ?: return
        val position = positionMs()
        val index = if (player.mediaItemCount > 0) player.currentMediaItemIndex else line.locate(position)?.index ?: 0
        val part = line.parts.getOrNull(index) ?: return
        val at = now()
        playingSince?.let { since ->
            listenedMs += (at - since).coerceAtLeast(0)
            playingSince = if (player.isPlaying) at else null
        }
        val out = SpokenPosition(
            bookId = book.id,
            positionMs = position,
            durationMs = line.durationMs,
            partId = part.id,
            partOffsetMs = position - line.starts[index],
            listenedMs = listenedMs,
            finished = finished,
            atMs = at,
            reason = reason,
        )
        listenedMs = 0
        lastReportAt = at
        onPosition?.invoke(out)
    }

    // endregion

    // region Ticks

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
                if (!player.playWhenReady && waitingSince == null) break
            }
        }
    }

    private fun tick() {
        val line = timeline ?: return
        val position = positionMs()
        val from = lastTickPositionMs
        lastTickPositionMs = position
        if (player.isPlaying) {
            listenedInPartMs += TICK_MS
            if (sleepPolicy.onTick(line, from, position) == SleepDecision.PAUSE) {
                pause()
                return
            }
            if (line.chapterIndex(from) != line.chapterIndex(position)) {
                report(ReportReason.CHAPTER)
                updateChapterMetadata()
            } else if (now() - lastReportAt >= tickIntervalMs) {
                report(ReportReason.PERIODIC)
            }
            prefetch(line)
        }
        checkWaiting()
        if (now() - lastSavedAheadAt >= SAVED_AHEAD_EVERY_MS) {
            lastSavedAheadAt = now()
            savedAheadMs = measureSavedAhead(line)
        }
        publish()
    }

    private fun prefetch(line: BookTimeline) {
        val prefetcher = prefetcher ?: return
        if (!canPrefetchWhole()) return
        val index = player.currentMediaItemIndex
        val part = line.parts.getOrNull(index) ?: return
        if (part.isHls) return
        val next = line.parts.getOrNull(index + 1)
        when {
            listenedInPartMs >= CURRENT_PART_AFTER_MS && !prefetcher.isFullyCached(part) ->
                prefetcher.fetch(part, fromMs = player.currentPosition.coerceAtLeast(0))
            next != null && !next.isHls && !prefetcher.isFullyCached(next) -> prefetcher.fetch(next)
        }
    }

    private fun measureSavedAhead(line: BookTimeline): Long? {
        val index = player.currentMediaItemIndex
        val part = line.parts.getOrNull(index) ?: return null
        if (part.isHls) return null
        val offset = player.currentPosition.coerceAtLeast(0)
        val inMemory = (player.totalBufferedDuration).coerceAtLeast(0)
        val prefetcher = prefetcher ?: return inMemory
        var onDisk = prefetcher.cachedAheadMs(part, offset) ?: return inMemory
        var reachedEnd = offset + onDisk >= part.durationMs - 1_000
        var i = index + 1
        while (reachedEnd && i < line.parts.size) {
            val next = line.parts[i]
            val got = prefetcher.cachedAheadMs(next, 0) ?: break
            onDisk += got
            reachedEnd = got >= next.durationMs - 1_000
            i++
        }
        return maxOf(inMemory, onDisk)
    }

    // endregion

    private fun publish() {
        val line = timeline
        val waiting = waitingSince != null
        val loading = book != null && player.playWhenReady && !waiting &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE && error == null)
        _state.value = State(
            book = book,
            positionMs = positionMs(),
            durationMs = line?.durationMs ?: 0,
            chapters = line?.chapters.orEmpty(),
            chapterOrigin = chapterOrigin,
            playWhenReady = player.playWhenReady || (waiting && resumesAfterWait),
            isLoading = loading,
            waitingForSignal = waiting,
            speed = player.playbackParameters.speed,
            sleepEnd = sleepPolicy.armed,
            sleepRemainingSecs = sleepTimer.remainingSecs,
            savedAheadMs = savedAheadMs,
            error = error,
            finished = finished,
        )
    }

    /** Refreshes [state] now, e.g. at 4 Hz while the player screen is visible. */
    fun refresh() = publish()

    private fun mediaItem(book: SpokenBook, part: SpokenPart, index: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setTrackNumber(index + 1)
            .setTotalTrackCount(book.parts.size)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { book.artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setMediaId("${book.id}#${part.id}")
            .setUri(part.uri)
            .setMediaMetadata(metadata)
            .apply { if (part.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }

    /** The notification names the chapter playing, when the chapters are real ones. */
    private fun updateChapterMetadata() {
        val book = book ?: return
        val line = timeline ?: return
        if (chapterOrigin == ChapterOrigin.PARTS || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val item = player.getMediaItemAt(index)
        val chapter = line.chapterAt(positionMs())?.title ?: return
        if (item.mediaMetadata.subtitle?.toString() == chapter) return
        val updated = item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setSubtitle(chapter).setArtist(chapter).setAlbumArtist(book.author).build())
            .build()
        player.replaceMediaItem(index, updated)
    }

    private val sessionListeners = HashMap<Player.Listener, Player.Listener>()

    private fun commandsChanged() {
        val commands = sessionPlayer.availableCommands
        sessionListeners.values.toList().forEach { it.onAvailableCommandsChanged(commands) }
    }

    private inner class Events : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val at = now()
            if (isPlaying) {
                playingSince = at
                if (waitingSince != null && !waitingFailed) resetWait()
            } else {
                playingSince?.let { listenedMs += (at - it).coerceAtLeast(0) }
                playingSince = null
            }
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) startTicking() else if (localWaiting.isNotEmpty()) scope.launch { if (!player.playWhenReady) swapWaiting() }
            publish()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = adoptDurations(timeline)

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> bookEnded()
                Player.STATE_BUFFERING -> checkWaiting()
                Player.STATE_READY -> if (waitingSince != null && !waitingFailed && player.isPlaying) resetWait()
            }
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val line = timeline ?: return
            listenedInPartMs = 0
            retriedLink = false
            // A copy that waited for the part to end plays when its part comes round again.
            val current = player.currentMediaItemIndex
            localWaiting.keys.filter { it != current }.forEach { index -> localWaiting.remove(index)?.let { swapSource(index, it) } }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val ended = player.currentMediaItemIndex - 1
                if (ended >= 0 && sleepPolicy.onPartEnded(line, ended) == SleepDecision.PAUSE) pause()
            }
            updateChapterMetadata()
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            val line = timeline ?: return
            val part = line.parts.getOrNull(player.currentMediaItemIndex)
            when {
                // The copy on the phone that waited for a break replaces the link that failed.
                player.currentMediaItemIndex in localWaiting -> {
                    swapWaiting()
                    player.prepare()
                }
                !connected -> startWaiting(failed = true)
                !retriedLink && part != null -> {
                    retriedLink = true
                    PlaybackUris.parse(Uri.parse(part.uri))?.let { links?.forget(it) }
                    onLinkFailed?.invoke(part, error)
                    player.seekTo(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
                    player.prepare()
                }
                else -> {
                    this@SpokenPlayer.error = error
                    if (player.playWhenReady) {
                        player.pause()
                        pausedAt = now()
                    }
                    report(ReportReason.PAUSE)
                }
            }
            publish()
        }
    }

    /**
     * The player opened a part and knows its real length: where the stated one is missing or
     * wrong (a feed's guess, an inserted ad) the book's timeline takes it, with its chapters.
     */
    private fun adoptDurations(media: androidx.media3.common.Timeline) {
        var line = timeline ?: return
        val book = book ?: return
        if (media.windowCount != line.parts.size) return
        val window = androidx.media3.common.Timeline.Window()
        var changed = false
        for (i in 0 until media.windowCount) {
            media.getWindow(i, window)
            if (window.isPlaceholder || window.isDynamic) continue
            val real = window.durationMs
            if (real == C.TIME_UNSET || real <= 0) continue
            val stated = line.parts[i].durationMs
            if (stated > 0 && (!adoptsPlayerDurations || kotlin.math.abs(real - stated) <= DURATION_TOLERANCE_MS)) continue
            line = line.withPartDuration(i, real)
            changed = true
        }
        if (!changed) return
        if (chapterOrigin == ChapterOrigin.PARTS) line = BookTimeline(line.parts, BookTimeline.chaptersFromParts(line.parts, book.partTitles, partTitle))
        timeline = line
        this.book = book.copy(parts = line.parts)
        lastTickPositionMs = positionMs()
        commandsChanged()
        publish()
    }

    private fun bookEnded() {
        val book = book ?: return
        val line = timeline ?: return
        if (finished) return
        sleepPolicy.onPartEnded(line, line.parts.lastIndex)
        finished = true
        player.pause()
        report(ReportReason.FINISHED, finished = true)
        onFinished?.invoke(book.id)
    }

    private inner class SessionPlayer : androidx.media3.common.ForwardingPlayer(player) {
        override fun play() = this@SpokenPlayer.play()

        override fun pause() = this@SpokenPlayer.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) this@SpokenPlayer.play() else this@SpokenPlayer.pause()

        // Removing the notification or "stop" from a controller keeps the book; the app's Stop forgets it.
        override fun stop() = this@SpokenPlayer.pause()

        override fun seekToNext() = nextChapter()

        override fun seekToNextMediaItem() = nextChapter()

        override fun seekToPrevious() = previousChapter()

        override fun seekToPreviousMediaItem() = previousChapter()

        override fun seekForward() = skipForward()

        override fun seekBack() = skipBack()

        override fun getSeekBackIncrement(): Long = skipBackSecs * 1000L

        override fun getSeekForwardIncrement(): Long = skipForwardSecs * 1000L

        override fun seekTo(positionMs: Long) {
            val line = timeline ?: return
            seek(line.position(player.currentMediaItemIndex, positionMs))
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            val line = timeline ?: return
            seek(line.position(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0 else positionMs))
        }

        override fun getAvailableCommands(): Player.Commands {
            val hasChapters = (timeline?.chapters?.size ?: 0) > 1
            val builder = super.getAvailableCommands().buildUpon()
            if (book != null) builder.addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_PLAY_PAUSE)
            for (command in CHAPTER_COMMANDS) if (hasChapters) builder.add(command) else builder.remove(command)
            return builder.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun addListener(listener: Player.Listener) {
            val wrapped = CommandsListener(listener)
            sessionListeners[listener] = wrapped
            super.addListener(wrapped)
        }

        override fun removeListener(listener: Player.Listener) {
            sessionListeners.remove(listener)?.let { super.removeListener(it) }
        }

        /**
         * Passes this player's commands, not the playlist's, and this player as the source. Every
         * callback is forwarded by hand: Kotlin's `by` delegation skips Java default methods.
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        private inner class CommandsListener(private val inner: Player.Listener) : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(this@SessionPlayer.availableCommands)
            override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(this@SessionPlayer, events)
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = inner.onTracksChanged(tracks)
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = inner.onMediaMetadataChanged(mediaMetadata)
            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
            override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
            override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = inner.onPlayerStateChanged(playWhenReady, playbackState)
            override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = inner.onPlayWhenReadyChanged(playWhenReady, reason)
            override fun onPlaybackSuppressionReasonChanged(reason: Int) = inner.onPlaybackSuppressionReasonChanged(reason)
            override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
            override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
            override fun onShuffleModeEnabledChanged(enabled: Boolean) = inner.onShuffleModeEnabledChanged(enabled)
            override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
            override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
            override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
                inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = inner.onPlaybackParametersChanged(parameters)
            override fun onSeekBackIncrementChanged(ms: Long) = inner.onSeekBackIncrementChanged(ms)
            override fun onSeekForwardIncrementChanged(ms: Long) = inner.onSeekForwardIncrementChanged(ms)
            override fun onMaxSeekToPreviousPositionChanged(ms: Long) = inner.onMaxSeekToPreviousPositionChanged(ms)
            override fun onAudioSessionIdChanged(id: Int) = inner.onAudioSessionIdChanged(id)
            override fun onAudioAttributesChanged(attributes: androidx.media3.common.AudioAttributes) = inner.onAudioAttributesChanged(attributes)
            override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
            override fun onSkipSilenceEnabledChanged(enabled: Boolean) = inner.onSkipSilenceEnabledChanged(enabled)
            override fun onDeviceInfoChanged(info: androidx.media3.common.DeviceInfo) = inner.onDeviceInfoChanged(info)
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) = inner.onVideoSizeChanged(size)
            override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
            override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
            override fun onCues(cues: List<androidx.media3.common.text.Cue>) = inner.onCues(cues)
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = inner.onCues(cueGroup)
            override fun onMetadata(metadata: androidx.media3.common.Metadata) = inner.onMetadata(metadata)
        }
    }

    companion object {
        /**
         * The ExoPlayer for books: [PlayerSetup]'s, with Media3's stuck-buffering check off, because
         * a player held for a signal in a long tunnel is waiting on purpose, not stuck.
         */
        fun buildExoPlayer(
            context: android.content.Context,
            mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory,
            loadControl: androidx.media3.exoplayer.LoadControl,
        ): ExoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
            .build()
            .also { PlayerSetup.configure(it, ContentKind.SPEECH) }

        private const val TICK_MS = 250L

        /** A stated length this close to the player's is kept, so book time doesn't shift for nothing. */
        const val DURATION_TOLERANCE_MS = 1_000L

        private val CHAPTER_COMMANDS = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        private const val SAVED_AHEAD_EVERY_MS = 2_000L

        /** On an unmetered network the current part is fetched whole after this much listening. */
        const val CURRENT_PART_AFTER_MS = 45_000L

        /** Whether [error] means no network rather than a refusal. */
        fun isNoNetwork(error: PlaybackException): Boolean =
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                generateSequence(error.cause) { it.cause }.any { it is java.net.UnknownHostException || it is java.net.ConnectException }

        /** The HTTP status of a failed read, or null. */
        fun httpStatus(error: PlaybackException): Int? =
            generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    }
}
