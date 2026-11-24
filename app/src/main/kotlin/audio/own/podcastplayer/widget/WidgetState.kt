package audio.own.podcastplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.ImageProvider
import audio.own.podcastplayer.playback.PodcastPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The episode in the player as the widgets show it, read from the app's player when they are drawn. */
data class WidgetPlayer(
    val title: String,
    /** The show. */
    val show: String,
    /** The picture's file name in the widget folder; written by [WidgetStore] with the episode's entry. */
    val cover: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Time left at the listener's speed. */
    val remainingMs: Long = (durationMs - positionMs).coerceAtLeast(0),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        /** What is loaded in the player; null for nothing, or the last episode only [held] for the first Play. */
        fun of(state: PodcastPlayer.State, held: Boolean): WidgetPlayer? {
            val item = state.item ?: return null
            if (held) return null
            val duration = state.durationMs.takeIf { it > 0 } ?: item.episode.durationMs ?: 0
            return WidgetPlayer(
                title = item.episode.title,
                show = state.show?.title.orEmpty(),
                cover = (item.episode.imageUrl ?: state.show?.imageUrl)?.let(WidgetSnapshot::coverName),
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = duration,
                remainingMs = ((duration - state.positionMs).coerceAtLeast(0) / state.rate.coerceAtLeast(0.1f)).toLong(),
            )
        }
    }
}

/** Everything the three widgets draw. */
data class WidgetState(
    /** What is in the player; null when nothing is (the app was closed, or nothing was played). */
    val player: WidgetPlayer? = null,
    /** The episode offered when [player] is null: the one under way, or the next new one. */
    val resume: WidgetSnapshot.Entry? = null,
    /** Up Next: the episodes under way, then the newest. */
    val shelf: List<WidgetSnapshot.Entry> = emptyList(),
    /** Pictures by file name; one per name, so every size of a widget shares the same bitmap. */
    val covers: Map<String, ImageProvider> = emptyMap(),
    /** The listener's skip intervals, as the notification shows them. */
    val skipBackSecs: Int = 15,
    val skipForwardSecs: Int = 30,
)

/**
 * Reads the widgets' state: the snapshot file for Up Next, the live player for
 * what plays. [version] goes up on every redraw, so a widget whose session is still running
 * reads again instead of drawing what it read last.
 */
object WidgetData {
    val version = MutableStateFlow(0L)

    fun folder(context: Context) = File(context.filesDir, "widget")

    suspend fun load(context: Context, playback: PodcastPlayer.State?, held: Boolean, shelfCovers: Boolean, coverPx: Int): WidgetState = withContext(Dispatchers.IO) {
        val store = WidgetStore(folder(context))
        val entries = store.load()?.entries.orEmpty()
        val player = playback?.let { WidgetPlayer.of(it, held) }
        val resume = entries.firstOrNull()
        val names = buildSet {
            (player?.cover ?: resume?.cover)?.let(::add)
            if (shelfCovers) entries.mapNotNullTo(this) { it.cover }
        }
        val covers = names.mapNotNull { name -> decode(store.cover(name), coverPx)?.let { name to ImageProvider(it) } }.toMap()
        WidgetState(
            player = player,
            resume = if (player == null) resume else null,
            shelf = entries,
            covers = covers,
            skipBackSecs = playback?.skip?.backSecs ?: 15,
            skipForwardSecs = playback?.skip?.forwardSecs ?: 30,
        )
    }

    /**
     * A picture no larger than [px] across: a home screen holds every widget's bitmaps in one
     * parcel with a size limit, so they are not decoded at the file's size when smaller will do.
     */
    fun decode(file: File, px: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (bitmap.width <= px) bitmap else Bitmap.createScaledBitmap(bitmap, px, bitmap.height * px / bitmap.width, true)
        }.getOrNull()
    }
}
