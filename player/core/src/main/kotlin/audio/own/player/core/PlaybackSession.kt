package audio.own.player.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Skip back and forward by the listener's intervals (the spoken apps; Music has none). */
data class SkipIntervals(val backSecs: Int, val forwardSecs: Int)

/**
 * The custom session commands behind the numbered skip buttons on the lock screen, the
 * notification, Bluetooth and Android Auto. Media3 has no "skip by N seconds" player command
 * whose N a controller shows, so the buttons carry it.
 */
@UnstableApi
object SkipCommands {
    const val ACTION_SKIP_BACK = "audio.own.player.SKIP_BACK"
    const val ACTION_SKIP_FORWARD = "audio.own.player.SKIP_FORWARD"

    val skipBack = SessionCommand(ACTION_SKIP_BACK, Bundle.EMPTY)
    val skipForward = SessionCommand(ACTION_SKIP_FORWARD, Bundle.EMPTY)

    /** The two buttons; [backLabel] and [forwardLabel] are the app's localized "Skip back 15 seconds". */
    fun buttons(intervals: SkipIntervals, backLabel: CharSequence, forwardLabel: CharSequence): List<CommandButton> = listOf(
        CommandButton.Builder(backIcon(intervals.backSecs))
            .setDisplayName(backLabel)
            .setSessionCommand(skipBack)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(forwardIcon(intervals.forwardSecs))
            .setDisplayName(forwardLabel)
            .setSessionCommand(skipForward)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    /** The numbered icon where Media3 has one; a plain arrow for other intervals. */
    fun backIcon(secs: Int): Int = when (secs) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        30 -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    fun forwardIcon(secs: Int): Int = when (secs) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        30 -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    /** Runs a skip command on [player]; false for any other command. */
    fun handle(player: Player, command: SessionCommand, intervals: SkipIntervals): Boolean {
        val deltaMs = when (command.customAction) {
            ACTION_SKIP_BACK -> -intervals.backSecs * 1000L
            ACTION_SKIP_FORWARD -> intervals.forwardSecs * 1000L
            else -> return false
        }
        val duration = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (duration > 0) target = target.coerceAtMost(duration)
        player.seekTo(target)
        return true
    }
}

/**
 * The parts of a `MediaLibrarySession.Callback` every own.audio app shares: the skip buttons,
 * playback resumption from the last item, and placeholder addresses for items controllers add.
 * The app subclasses it for browsing (Android Auto) and anything of its own.
 */
@UnstableApi
open class PlaybackSessionCallback(
    /** Where resumption runs; the service's scope, on the main thread. */
    private val scope: CoroutineScope,
    /** The current skip intervals, or null for an app without skip buttons. */
    private val skipIntervals: () -> SkipIntervals? = { null },
    /** The localized labels of the two skip buttons. */
    private val skipLabels: (SkipIntervals) -> Pair<CharSequence, CharSequence> = { "" to "" },
) : MediaLibrarySession.Callback {

    /**
     * What `onPlaybackResumption` plays: the last item and where it stopped, or null when there
     * is none (resumption is then refused). Called when a Bluetooth headset, the Assistant or
     * the system's media controls ask to play after the app was killed. [isForPlayback] false
     * asks only for the item's metadata: open nothing on the network then.
     */
    open suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? = null

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        val intervals = skipIntervals()
        if (intervals != null) {
            val defaults = when {
                session is MediaLibrarySession && controller.isTrusted -> MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                session is MediaLibrarySession -> MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS
                controller.isTrusted -> MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                else -> MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS
            }
            builder.setAvailableSessionCommands(defaults.buildUpon().add(SkipCommands.skipBack).add(SkipCommands.skipForward).build())
            val (back, forward) = skipLabels(intervals)
            builder.setMediaButtonPreferences(SkipCommands.buttons(intervals, back, forward))
        }
        return Futures.immediateFuture(builder.build())
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val intervals = skipIntervals()
        if (intervals != null && SkipCommands.handle(session.player, customCommand, intervals)) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val items = resumptionItems(isForPlayback)
                if (items == null) result.setException(UnsupportedOperationException("Nothing to resume")) else result.set(items)
            } catch (error: Throwable) {
                result.setException(error)
            }
        }
        return result
    }

    /**
     * Items from a controller (Android Auto, the Assistant) arrive with an id and no address;
     * they get a placeholder, which the data source resolves when it opens.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map(::withAddress).toMutableList())

    protected open fun withAddress(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else item.buildUpon().setUri(PlaybackUris.forItem(item.mediaId)).build()
}

/**
 * Artwork for the notification, lock screen and Auto through the app's own image loader (Coil,
 * with its cache keyed by record and image id rather than by signed URL). [load] gets the
 * `artworkUri` of an item and returns the picture, about 600 px, or null.
 *
 * Wrap it in Media3's `CacheBitmapLoader`, so the same cover is not decoded again for every
 * notification update.
 */
@UnstableApi
class ArtworkBitmapLoader(
    private val scope: CoroutineScope,
    private val load: suspend (Uri) -> Bitmap?,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future(null) { load(uri) }

    private fun future(dispatcher: CoroutineDispatcher?, block: suspend () -> Bitmap?): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        scope.launch(dispatcher ?: EmptyCoroutineContext) {
            try {
                val bitmap = block()
                if (bitmap == null) result.setException(IllegalStateException("No artwork")) else result.set(bitmap)
            } catch (error: Throwable) {
                result.setException(error)
            }
        }
        return result
    }
}
