package audio.own.podcastplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import audio.own.player.core.ArtworkBitmapLoader
import audio.own.player.core.PlaybackSessionCallback
import audio.own.podcastplayer.MainActivity
import audio.own.podcastplayer.OwnAudioApp
import audio.own.podcastplayer.R
import audio.own.podcastplayer.podcasts.PodcastModel
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The media service: the notification, the lock screen, Bluetooth and the system's media
 * controls. It plays the app's one [EpisodePlayer]; the player lives with the process, so a
 * service the system ends and starts again picks up the same episode.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PodcastService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as OwnAudioApp).container
        val player = container.player
        val callback = Callback(scope, container.podcasts)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player.sessionPlayer, callback)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(scope) { uri -> container.artwork.notificationBitmap(uri) }))
            .build()
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

    private inner class Callback(scope: CoroutineScope, private val podcasts: PodcastModel) : PlaybackSessionCallback(
        scope = scope,
        skipIntervals = { podcasts.player.state.value.skip },
        skipLabels = { intervals ->
            getString(R.string.skip_back_seconds, intervals.backSecs) to getString(R.string.skip_forward_seconds, intervals.forwardSecs)
        },
    ) {
        override suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? =
            podcasts.resumption(isForPlayback)

        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val base = super.onConnectAsync(session, controller).get()
            if (!base.isAccepted) return Futures.immediateFuture(base)
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands)
                .setAvailablePlayerCommands(EpisodePlayer.withoutNeighbours(base.availablePlayerCommands))
            base.mediaButtonPreferences?.let { builder.setMediaButtonPreferences(it) }
            return Futures.immediateFuture(builder.build())
        }

        // Android Auto's browsing comes in P11; until then the root is empty.
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(ROOT, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }

    private companion object {
        val ROOT: MediaItem = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
            .build()
    }
}
