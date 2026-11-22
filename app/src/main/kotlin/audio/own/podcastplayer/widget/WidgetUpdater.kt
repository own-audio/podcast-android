package audio.own.podcastplayer.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.PodcastState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps the widget's snapshot in step with the library and the player: a second after a change,
 * so a burst of them writes once, and only when the entries differ. The widget is redrawn then.
 */
class WidgetUpdater(
    private val context: Context,
    private val store: WidgetStore,
    private val library: StateFlow<PodcastState>,
    private val player: StateFlow<PodcastPlayer.State>,
    private val jpeg: suspend (String) -> ByteArray?,
    private val log: AppLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) = scope.launch {
        combine(library.filter { it.loaded }, player) { state, playing -> WidgetSnapshot.pick(playing, state.inbox, state.progress, state::show) }
            .distinctUntilChanged { a, b -> a.map { it.entry } == b.map { it.entry } }
            .debounce(DEBOUNCE_MS)
            .collect { picked ->
                try {
                    if (store.save(picked, now(), jpeg)) UpNextWidget().updateAll(context)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.error("widget", "the snapshot wasn't written: ${error.javaClass.simpleName}")
                }
            }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L
    }
}
