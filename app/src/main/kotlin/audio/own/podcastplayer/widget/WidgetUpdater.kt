package audio.own.podcastplayer.widget

import android.content.Context
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.PodcastState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps the widgets in step with the library and the player. The snapshot is written a second
 * after a change, so a burst of them writes once, and only when the entries differ; every widget
 * is redrawn then. The players are redrawn ([redrawPlayers]) on play, pause and another episode
 * and, for the progress, once a minute while playing: never more often, and not at all while
 * paused.
 */
class WidgetUpdater(
    private val context: Context,
    private val store: WidgetStore,
    private val library: StateFlow<PodcastState>,
    private val player: StateFlow<PodcastPlayer.State>,
    private val jpeg: suspend (String) -> ByteArray?,
    private val log: AppLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val redraw: suspend () -> Unit = { WidgetRedraw.all(context) },
    private val redrawPlayers: suspend () -> Unit = { WidgetRedraw.players(context) },
    private val tickMs: Long = 60_000,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            player.map { PlayingKey(it.item?.id, it.isPlaying) }.distinctUntilChanged().debounce(PLAYER_DEBOUNCE_MS).collectLatest { key ->
                drawPlayers()
                while (key.isPlaying) {
                    delay(tickMs)
                    drawPlayers()
                }
            }
        }
        scope.launch { snapshots() }
    }

    private suspend fun drawPlayers() {
        try {
            redrawPlayers()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("widget", "the player widgets weren't drawn: ${error.javaClass.simpleName}")
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun snapshots() {
        combine(library.filter { it.loaded }, player) { state, playing -> WidgetSnapshot.pick(playing, state.inbox, state.progress, state::show) }
            .distinctUntilChanged { a, b -> a.map { it.entry } == b.map { it.entry } }
            .debounce(DEBOUNCE_MS)
            .collect { picked ->
                try {
                    if (store.save(picked, now(), jpeg)) redraw()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.error("widget", "the snapshot wasn't written: ${error.javaClass.simpleName}")
                }
            }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L

        /** Play then pause at once, or a skip through episodes, draws once. */
        const val PLAYER_DEBOUNCE_MS = 250L
    }
}

/** What the player widgets redraw for: another episode, or play/pause. */
data class PlayingKey(val episodeId: String?, val isPlaying: Boolean)
