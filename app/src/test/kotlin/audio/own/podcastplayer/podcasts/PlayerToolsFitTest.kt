package audio.own.podcastplayer.podcasts

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.Episode
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rychlost, Spánek, Kapitoly, Na řadě, Výstup: every caption whole on a small phone and with larger text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayerToolsFitTest {
    @get:Rule val compose = createComposeRule()

    private val player = PodcastPlayer.State(
        item = SourcedEpisode(SourceRef.Phone, Episode(id = "e", showId = "s", title = "Why bread rises", publishedAt = Instant.EPOCH, durationMs = 600_000, firstSeenAt = Instant.EPOCH)),
        positionMs = 60_000,
        durationMs = 600_000,
        rate = 1.25f,
        chapters = listOf(SpokenChapter("Start", 0, 300_000), SpokenChapter("End", 300_000, 600_000)),
        sleepRemainingSecs = 600.0,
    )

    private fun assertCaptionsFit() {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = true, reduceMotion = true) {
                PlayerContent(player, emptyList(), PlayerActions.None, onClose = {})
            }
        }
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val captions = compose.onAllNodesWithTag("player.tool.label", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("${captions.size} captions", captions.size >= 4)
        for (node in captions) {
            val coordinates = node.layoutInfo.coordinates
            val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
            assertTrue("caption at ${bounds.left}..${bounds.right} is on screen", bounds.left >= screen.left - 0.5f && bounds.right <= screen.right + 0.5f)
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertEquals("${layout.layoutInput.text} on one line", 1, layout.lineCount)
            assertFalse("${layout.layoutInput.text} whole, not cut or ellipsized", layout.hasVisualOverflow || layout.isLineEllipsized(0))
        }
    }

    @Test @Config(qualifiers = "cs-w360dp-h780dp") fun czechOnASmallPhone() = assertCaptionsFit()

    @Test @Config(qualifiers = "cs-w360dp-h780dp", fontScale = 1.3f) fun czechOnASmallPhoneWithLargerText() = assertCaptionsFit()

    @Test @Config(qualifiers = "w360dp-h780dp", fontScale = 1.3f) fun englishOnASmallPhoneWithLargerText() = assertCaptionsFit()
}
