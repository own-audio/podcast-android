@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.podcastplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import audio.own.podcastplayer.MainActivity
import audio.own.podcastplayer.playback.PodcastService
import audio.own.player.core.SkipCommands
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the widgets' buttons and covers do. */
object WidgetActions {
    enum class Command {
        PAUSE, SKIP_BACK, SKIP_FORWARD;

        /**
         * What the command does through the session's controller: the skips are the session's
         * own skip commands, so they go by the listener's intervals as the notification's do.
         */
        fun sendTo(player: Player, custom: (SessionCommand) -> Unit) = when (this) {
            PAUSE -> player.pause()
            SKIP_BACK -> custom(SkipCommands.skipBack)
            SKIP_FORWARD -> custom(SkipCommands.skipForward)
        }
    }

    val COMMAND = ActionParameters.Key<String>("command")

    /**
     * Play, from the widget as from the notification: a media-button intent the media service
     * gets as a foreground start, which Android allows for a tap on a widget. With nothing in
     * the player it resumes the last episode, as a headset's play button does.
     */
    fun play(context: Context): Action = actionStartService(playIntent(context), isForegroundService = true)

    /** One intent for every widget and size; it never changes. */
    fun playIntent(context: Context): Intent = play ?: Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, PodcastService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        .also { play = it }

    @Volatile private var play: Intent? = null

    /** Pause and the skips go to the media session through a controller, as any other remote does. */
    fun command(command: Command): Action = actionRunCallback<WidgetControlCallback>(actionParametersOf(COMMAND to command.name))

    /** The full player. */
    fun openPlayer(context: Context): Action = actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
    )

    /** Opens the app and plays [entry] from where the listener is. */
    fun open(context: Context, entry: WidgetSnapshot.Entry): Action = actionStartActivity(link(context, entry))

    fun link(context: Context, entry: WidgetSnapshot.Entry): Intent =
        Intent(Intent.ACTION_VIEW, WidgetSnapshot.playLink(entry.id)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openApp(context: Context): Action = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/** Sends a widget's [WidgetActions.Command] to the media session. */
class WidgetControlCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WidgetActions.COMMAND]?.let { name -> WidgetActions.Command.entries.firstOrNull { it.name == name } } ?: return
        withContext(Dispatchers.Main) {
            val controller = connect(context) ?: return@withContext
            try {
                command.sendTo(controller) { controller.sendCustomCommand(it, Bundle.EMPTY) }
                // The command is on its way before the controller lets go of the session.
                delay(SETTLE_MS)
            } finally {
                controller.release()
            }
        }
    }

    private suspend fun connect(context: Context): MediaController? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PodcastService::class.java))).buildAsync()
        suspendCancellableCoroutine { continuation ->
            future.addListener({ continuation.resume(runCatching { future.get() }.getOrNull()) }, Runnable::run)
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
    }
}
