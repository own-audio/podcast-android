package audio.own.podcastplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import audio.own.player.core.ArtworkBitmapLoader
import audio.own.player.core.PlaybackSessionCallback
import audio.own.player.core.SkipCommands
import audio.own.player.spoken.SpeedText
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.MainActivity
import audio.own.podcastplayer.OwnAudioApp
import audio.own.podcastplayer.R
import audio.own.podcastplayer.podcasts.PodcastModel
import audio.own.podcastplayer.podcasts.PodcastState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The media service: the notification, the lock screen, Bluetooth, the system's media controls,
 * the assistant and Android Auto. It plays the app's one [PodcastPlayer]; the player lives with
 * the process, so a service the system ends and starts again picks up the same episode.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PodcastService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as OwnAudioApp).container
        val player = container.player
        val podcasts = container.podcasts
        val library = AutoLibrary(
            library = object : AutoLibrary.Library {
                override val state: PodcastState get() = podcasts.state.value
                override val player: PodcastPlayer.State get() = player.state.value

                override fun canPlay(item: SourcedEpisode) = podcasts.canPlay(item)

                override suspend fun episodes(show: SourcedShow) = podcasts.episodes(show)
            },
            words = AutoWords(),
            artwork = container.carArtwork::uri,
        )
        val callback = Callback(scope, podcasts, library)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionPlayer = PodcastSessionPlayer(player.spoken.sessionPlayer) { player.spoken.player.currentMediaItem?.mediaId }
        val built = MediaLibrarySession.Builder(this, sessionPlayer, callback)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(scope) { uri -> container.artwork.notificationBitmap(uri) }))
            .build()
        session = built
        // The speed button shows the speed it is at; the car's lists follow the library.
        scope.launch {
            player.state.map { it.rate to it.skip }.distinctUntilChanged().drop(1).collect { built.setMediaButtonPreferences(callback.buttons()) }
        }
        scope.launch {
            podcasts.state.first { it.loaded }
            podcasts.state.map { Triple(it.shows.size, it.inbox.map { item -> item.id }, it.downloads.entries.size) }.distinctUntilChanged().collect {
                for (id in listOf(AutoContent.ROOT, AutoContent.NEW_EPISODES, AutoContent.DOWNLOADS, AutoContent.SHOWS)) built.notifyChildrenChanged(id, Int.MAX_VALUE, null)
            }
        }
        scope.launch {
            player.state.map { it.item?.id to it.chapters.size }.distinctUntilChanged().drop(1).collect {
                built.notifyChildrenChanged(AutoContent.ROOT, Int.MAX_VALUE, null)
                built.notifyChildrenChanged(AutoContent.CHAPTERS, Int.MAX_VALUE, null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiped away from the recent apps while paused: nothing is left to keep the service for. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class AutoWords : AutoLibrary.Words {
        override fun title(entry: AutoContent.RootEntry): String = getString(
            when (entry) {
                AutoContent.RootEntry.NEW_EPISODES -> R.string.tab_new_episodes
                AutoContent.RootEntry.DOWNLOADS -> R.string.tab_downloads
                AutoContent.RootEntry.SHOWS -> R.string.tab_shows
            },
        )

        override val chapters: String get() = getString(R.string.chapters)
        override val followOnPhone: String get() = getString(R.string.auto_follow_on_phone)
        override val noNewEpisodes: String get() = getString(R.string.auto_no_new_episodes)
        override val nothingDownloaded: String get() = getString(R.string.auto_nothing_downloaded)
        override val nothingHere: String get() = getString(R.string.auto_nothing_here)
        override val noShows: String get() = getString(R.string.auto_no_shows)
        override val noChapters: String get() = getString(R.string.no_chapters)

        override fun chapter(number: Int): String = getString(R.string.chapter_number, number)

        override fun failure(error: Throwable): String = PodcastModel.message(error).resolve(this@PodcastService)
    }

    private inner class Callback(
        private val scope: CoroutineScope,
        private val podcasts: PodcastModel,
        private val library: AutoLibrary,
    ) : PlaybackSessionCallback(
        scope = scope,
        skipIntervals = { podcasts.player.state.value.skip },
        skipLabels = { intervals ->
            getString(R.string.skip_back_seconds, intervals.backSecs) to getString(R.string.skip_forward_seconds, intervals.forwardSecs)
        },
    ) {
        override suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? =
            podcasts.resumption(isForPlayback)

        /** The skip buttons, and in the overflow the speed, which a car shows next to them. */
        fun buttons(): List<CommandButton> {
            val state = podcasts.player.state.value
            val skips = SkipCommands.buttons(state.skip, getString(R.string.skip_back_seconds, state.skip.backSecs), getString(R.string.skip_forward_seconds, state.skip.forwardSecs))
            val speed = CommandButton.Builder(speedIcon(state.rate))
                .setDisplayName(getString(R.string.auto_speed, SpeedText.text(state.rate)))
                .setSessionCommand(SPEED_COMMAND)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
            return skips + speed
        }

        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val base = super.onConnectAsync(session, controller).get()
            if (!base.isAccepted) return Futures.immediateFuture(base)
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SPEED_COMMAND).build())
                .setAvailablePlayerCommands(PodcastPlayer.withoutNeighbours(base.availablePlayerCommands))
                .setMediaButtonPreferences(buttons())
            return Futures.immediateFuture(builder.build())
        }

        /** Skips through the app's player, which keeps them inside the episode's real length. */
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SkipCommands.ACTION_SKIP_BACK -> podcasts.player.skipBack()
                SkipCommands.ACTION_SKIP_FORWARD -> podcasts.player.skipForward()
                ACTION_SPEED -> podcasts.player.setRate(AutoContent.nextRate(podcasts.player.state.value.rate))
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            podcasts.state.first { it.loaded }
            LibraryResult.ofItemList(paged(library.children(parentId), page, pageSize), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = library.item(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                podcasts.state.first { it.loaded }
                session.notifySearchResultChanged(browser, query, library.search(query).size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            podcasts.state.first { it.loaded }
            LibraryResult.ofItemList(paged(library.search(query), page, pageSize), params)
        }

        /**
         * A controller picked something: a row in the car, a chapter, or a spoken request
         * ("play ‹show› on own.podcast", which arrives as a search query). The model plays it; the
         * session then gets back what the player loaded.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val picked = mediaItems.getOrNull(startIndex.takeIf { it in mediaItems.indices } ?: 0)
                    val id = picked?.mediaId.orEmpty()
                    val query = picked?.requestMetadata?.searchQuery
                    val playing = when {
                        id.startsWith(AutoContent.CHAPTER_PREFIX) -> {
                            val chapter = id.removePrefix(AutoContent.CHAPTER_PREFIX).toIntOrNull()?.let(library::chapter)
                            if (chapter != null) {
                                podcasts.player.seekTo(chapter.startMs)
                                podcasts.player.play()
                            }
                            chapter != null
                        }
                        id.isNotEmpty() && !id.startsWith("@") -> podcasts.playLink(id, library.known(id))
                        else -> podcasts.playFromSearch(query)
                    }
                    val loaded = if (playing) podcasts.player.resumptionItems() else null
                    if (loaded == null) {
                        result.setException(UnsupportedOperationException("Nothing to play"))
                    } else {
                        result.set(MediaSession.MediaItemsWithStartPosition(loaded.mediaItems, 0, C.TIME_UNSET))
                    }
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        private fun <T : Any> future(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val result = SettableFuture.create<LibraryResult<T>>()
            scope.launch {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }
    }

    private companion object {
        const val ACTION_SPEED = "audio.own.podcastplayer.SPEED"
        val SPEED_COMMAND = SessionCommand(ACTION_SPEED, Bundle.EMPTY)

        fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
            return ImmutableList.copyOf(items.drop(page * pageSize).take(pageSize))
        }

        /** The numbered speed icon nearest the speed. */
        fun speedIcon(rate: Float): Int = when {
            rate < 0.9f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            rate < 1.1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            rate < 1.4f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            rate < 1.65f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            rate < 1.9f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            else -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        }
    }
}
