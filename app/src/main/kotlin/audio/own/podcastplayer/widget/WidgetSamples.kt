package audio.own.podcastplayer.widget

import androidx.glance.ImageProvider
import audio.own.podcastplayer.R

/** Made-up episodes for the widget picker's previews (Android 15 and later draws them from here). */
object WidgetSamples {
    private val episodes = listOf(
        Triple("Why we still love radio", "The Quiet Hour", 0.42),
        Triple("Gardens in winter", "Slow Weekends", 0.0),
        Triple("A short history of maps", "Curious Minds", 0.0),
        Triple("Bread, slowly", "Kitchen Table", 0.12),
        Triple("Night trains of Europe", "Far and Near", 0.0),
        Triple("The sound of rain", "Field Notes", 0.0),
        Triple("Learning to listen", "The Quiet Hour", 0.0),
        Triple("Small towns, big ideas", "Main Street", 0.0),
    )

    private val pictures = listOf(
        R.drawable.widget_sample_cover_1, R.drawable.widget_sample_cover_2, R.drawable.widget_sample_cover_3, R.drawable.widget_sample_cover_4,
        R.drawable.widget_sample_cover_5, R.drawable.widget_sample_cover_6, R.drawable.widget_sample_cover_7, R.drawable.widget_sample_cover_8,
    )

    private val lengths = listOf(52, 38, 61, 27, 45, 33, 58, 41)

    fun state(): WidgetState {
        val shelf = episodes.mapIndexed { index, (title, show, fraction) ->
            WidgetSnapshot.Entry("sample|$index", title, show, fraction, remainingMs = ((1 - fraction) * lengths[index] * 60_000).toLong(), cover = "sample$index")
        }
        return WidgetState(
            player = WidgetPlayer(
                title = "Why we still love radio", show = "The Quiet Hour", cover = "sample0", isPlaying = true,
                positionMs = 1_310_000, durationMs = 3_120_000, remainingMs = 1_810_000,
            ),
            shelf = shelf,
            covers = pictures.mapIndexed { index, res -> "sample$index" to ImageProvider(res) }.toMap(),
        )
    }
}
