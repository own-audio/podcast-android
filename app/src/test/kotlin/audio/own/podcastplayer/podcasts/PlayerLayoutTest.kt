package audio.own.podcastplayer.podcasts

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.podcast.library.Episode
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on a 10" tablet on its side: Speed, Sleep, Up Next and Output were below the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val player = PodcastPlayer.State(
        item = SourcedEpisode(SourceRef.Phone, Episode(id = "e", showId = "s", title = "Why bread rises", publishedAt = Instant.EPOCH, durationMs = 600_000, firstSeenAt = Instant.EPOCH)),
        positionMs = 60_000,
        durationMs = 600_000,
    )

    private fun show() {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                PlayerContent(player, emptyList(), PlayerActions.None, onClose = {})
            }
        }
        compose.waitForIdle()
    }

    private fun sideBySide() = compose.onAllNodesWithTag("player.sideBySide").fetchSemanticsNodes().isNotEmpty()

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a tablet on its side has the tools on screen beside the picture`() {
        show()
        assertTrue(sideBySide())
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in listOf("Speed ", "Sleep Timer", "Up Next", "Output")) {
            val bounds = compose.onNodeWithContentDescription(label, substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$label at ${bounds.bottom} within ${screen.bottom}", bounds.bottom <= screen.bottom)
            assertTrue("$label in the right half", bounds.left >= screen.width / 2)
        }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port")
    fun `a tablet upright keeps one column`() {
        show()
        assertEquals(false, sideBySide())
    }

    // Phones are held upright by the app (MainActivity); a narrow window keeps the column too.
    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun `a phone keeps one column`() {
        show()
        assertEquals(false, sideBySide())
    }
}
