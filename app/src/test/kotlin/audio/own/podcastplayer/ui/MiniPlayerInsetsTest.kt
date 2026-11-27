package audio.own.podcastplayer.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import audio.own.podcast.library.Episode
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.MiniPlayer
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on a tablet: with the rail, the mini player sat under the gesture handle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniPlayerInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val navBar = 48.dp
    private val title = "Why bread rises"
    private val player = PodcastPlayer.State(
        item = SourcedEpisode(SourceRef.Phone, Episode(id = "e", showId = "s", title = title, publishedAt = Instant.EPOCH, durationMs = 600_000, firstSeenAt = Instant.EPOCH)),
        positionMs = 60_000,
        durationMs = 600_000,
    )

    @Composable private fun Mini() = MiniPlayer(player, onOpen = {}, onToggle = {}, onSkipForward = {})

    private fun show(content: @Composable () -> Unit) {
        compose.setContent(content)
        val px = (navBar.value * compose.activity.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), insets)
        }
        compose.waitForIdle()
    }

    private fun assertClearsTheBar() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val mini = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val barTop = root.bottom - navBar.value * compose.density.density
        assertTrue("mini player bottom ${mini.bottom} must be above the bar at $barTop", mini.bottom <= barTop + 0.5f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `with the rail, the mini player clears the navigation bar`() {
        show { TabsScreen { TabBar(title = "New Episodes", bottomBar = { Mini() }) { Text("content") } } }
        assertClearsTheBar()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a page over the tabs, the mini player clears the navigation bar`() {
        show { Page(title = "A show", onBack = {}, bottomBar = { Mini() }) { Text("content") } }
        assertClearsTheBar()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `with the bottom bar, the mini player sits right on the tabs, not an inset higher`() {
        show { TabsScreen { TabBar(title = "New Episodes", bottomBar = { Mini() }) { Text("content") } } }
        val mini = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val tabsTop = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
            .minOf { it.boundsInRoot.top }
        val gap = tabsTop - mini.bottom
        assertTrue("gap $gap between mini player and tabs must be less than the inset", gap >= -0.5f && gap < navBar.value * compose.density.density)
    }
}
