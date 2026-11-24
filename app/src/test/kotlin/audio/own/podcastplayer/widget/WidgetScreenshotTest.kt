package audio.own.podcastplayer.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import audio.own.podcastplayer.R
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three widgets as the home screen draws them, from Glance's own output, in light and dark;
 * and the picker's preview layouts, which are also the preview images for Android 11 and older
 * (`res/drawable-nodpi/widget_preview_*_image.png`, copied from here). Record with
 * `:app:recordRoborazziPlayDebug`.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetScreenshotTest {
    private val sample = WidgetSamples.state()
    private val paused = sample.copy(player = sample.player!!.copy(isPlaying = false))
    private val resume = WidgetState(resume = sample.shelf[1], covers = sample.covers)
    private val long = sample.copy(
        player = sample.player!!.copy(title = "A Very Long Episode Title That Does Not Fit On One Line At All", show = "A Show With A Very Long Name Indeed"),
    )

    private val strip = DpSize(350.dp, 80.dp)
    private val big = DpSize(350.dp, 180.dp)
    private val shelf = DpSize(350.dp, 180.dp)

    @Test
    fun light() = shootAll("light")

    @Test
    @Config(qualifiers = "+night")
    fun dark() = shootAll("dark")

    private fun shootAll(theme: String) {
        shoot("widget_strip_playing_$theme", strip) { PlayerStripContent(sample) }
        shoot("widget_big_playing_$theme", big) { NowPlayingContent(sample) }
        shoot("widget_shelf_$theme", shelf) { ShelfContent(sample) }
        if (theme == "light") {
            shoot("widget_strip_continue_light", strip) { PlayerStripContent(resume) }
            shoot("widget_strip_narrow_light", DpSize(160.dp, 80.dp)) { PlayerStripContent(paused) }
            shoot("widget_strip_tall_light", DpSize(350.dp, 140.dp)) { PlayerStripContent(paused) }
            shoot("widget_strip_long_light", strip) { PlayerStripContent(long) }
            shoot("widget_strip_empty_light", strip) { PlayerStripContent(WidgetState()) }
            shoot("widget_big_continue_light", big) { NowPlayingContent(resume) }
            shoot("widget_big_long_light", big) { NowPlayingContent(long) }
            shoot("widget_big_small_light", DpSize(160.dp, 160.dp)) { NowPlayingContent(paused) }
            shoot("widget_big_tall_light", DpSize(350.dp, 300.dp)) { NowPlayingContent(sample) }
            shoot("widget_shelf_old_2x2_light", DpSize(170.dp, 180.dp)) { ShelfContent(sample) }
            shoot("widget_shelf_tall_light", DpSize(350.dp, 300.dp)) { ShelfContent(sample) }
            shoot("widget_shelf_empty_light", shelf) { ShelfContent(WidgetState()) }
        }
        preview("widget_preview_player_strip_image_$theme", R.layout.widget_preview_player_strip, strip)
        preview("widget_preview_now_playing_image_$theme", R.layout.widget_preview_now_playing, big)
        preview("widget_preview_shelf_image_$theme", R.layout.widget_preview_shelf, shelf)
    }

    private fun shoot(name: String, size: DpSize, content: @Composable () -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val views = runBlocking { GlanceRemoteViews().compose(context, size) { WidgetLook.Theme(content) }.remoteViews }
        capture(name, size) { parent -> views.apply(context, parent) }
    }

    private fun preview(name: String, layout: Int, size: DpSize) =
        capture(name, size) { parent -> LayoutInflater.from(parent.context).inflate(layout, parent, false) }

    private fun capture(name: String, size: DpSize, make: (ViewGroup) -> View) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val view = make(frame)
        frame.addView(view, FrameLayout.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
        view.captureRoboImage("src/test/screenshots/$name.png")
    }
}
