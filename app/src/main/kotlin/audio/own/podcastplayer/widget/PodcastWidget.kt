package audio.own.podcastplayer.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import audio.own.podcastplayer.OwnAudioApp

/**
 * What the three widgets share: they read [WidgetState] when drawn and again on every redraw
 * ([WidgetData.version]); the picker shows them with [WidgetSamples] from Android 15.
 */
abstract class PodcastWidget : GlanceAppWidget() {
    /** Whether the shelf's covers are needed, not only the book playing. */
    protected abstract val shelfCovers: Boolean

    /** How large the pictures are decoded, in pixels across. */
    protected abstract val coverPx: Int

    @Composable
    abstract fun Content(state: WidgetState)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val readAt = WidgetData.version.value
        val first = read(context)
        provideContent {
            val version by WidgetData.version.collectAsState()
            val state by produceState(first, version) { if (version != readAt) value = read(context) }
            WidgetLook.Theme { Content(state) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = WidgetSamples.state()
        provideContent { WidgetLook.Theme { Content(sample) } }
    }

    private suspend fun read(context: Context): WidgetState {
        val player = (context.applicationContext as? OwnAudioApp)?.container?.player
        return WidgetData.load(context, player?.state?.value, player?.isHeld ?: false, shelfCovers, coverPx)
    }
}

/** Redraws the widgets placed on the home screen; widgets nobody placed cost nothing. */
object WidgetRedraw {
    suspend fun players(context: Context) {
        WidgetData.version.value++
        PlayerStripWidget().updateAll(context)
        NowPlayingWidget().updateAll(context)
    }

    suspend fun all(context: Context) {
        players(context)
        UpNextWidget().updateAll(context)
    }
}

/**
 * The picker's previews drawn by the widgets themselves (Android 15 and later), with made-up
 * episodes. Sent once per app version: the system allows only a few a day.
 */
object WidgetPreviews {
    suspend fun publish(context: Context, versionCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences("widget-previews", Context.MODE_PRIVATE)
        if (prefs.getInt(SENT, 0) == versionCode) return
        val manager = GlanceAppWidgetManager(context)
        val results = listOf(PlayerStripWidgetReceiver::class, NowPlayingWidgetReceiver::class, UpNextWidgetReceiver::class).map { receiver ->
            runCatching { manager.setWidgetPreviews(receiver) }.getOrDefault(GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_RATE_LIMITED)
        }
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) prefs.edit { putInt(SENT, versionCode) }
    }

    private const val SENT = "sentForVersion"
}
