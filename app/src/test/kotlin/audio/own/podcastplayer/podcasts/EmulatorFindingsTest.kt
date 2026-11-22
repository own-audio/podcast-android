package audio.own.podcastplayer.podcasts

import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.Chapter
import audio.own.podcast.library.Episode
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import audio.own.podcast.library.ListeningProgress
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the emulator test of 2026-11-22 found, kept from coming back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmulatorFindingsTest {
    @get:Rule val compose = createComposeRule()

    private val linked = SourcedEpisode(SourceRef.Phone, Episode(id = "278", showId = "pc20", title = "Episode 278", chaptersUrl = "https://example.com/278.json"))
    private val other = SourcedEpisode(SourceRef.Phone, Episode(id = "277", showId = "pc20", title = "Episode 277"))
    private val found = listOf(SpokenChapter("Welcome", 0, 60_000), SpokenChapter("Boosts", 60_000, 120_000))

    @Test
    fun `the page of the episode in the player lists the chapters the player read from the chapters document`() {
        val playing = PodcastPlayer.State(item = linked, chapters = found)
        assertEquals(listOf(Chapter(0, 60_000, "Welcome"), Chapter(60_000, 120_000, "Boosts")), shownChapters(linked, playing))
        assertTrue(shownChapters(other, playing).isEmpty())
    }

    @Test
    fun `chapters written in the feed itself are listed as they are`() {
        val inline = linked.copy(episode = linked.episode.copy(chapters = listOf(Chapter(0, null, "Intro"))))
        assertEquals("Intro", shownChapters(inline, PodcastPlayer.State(item = inline, chapters = found)).single().title)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp")
    fun `an empty Up Next says so at the top of the sheet, not below the screen`() {
        // A sheet offers its content the whole screen's height; a phone shows about half of it.
        compose.setContent {
            Box(Modifier.fillMaxSize()) { UpNextBody(emptyList(), PlayerActions.None, onDismiss = {}) }
        }
        val title = compose.onNodeWithText("Nothing Up Next").getUnclippedBoundsInRoot()
        assertTrue("title at ${title.top}", title.top < 300.dp)
        val text = compose.onNodeWithText("Add episodes with Play Next or Play Later from their menu.").getUnclippedBoundsInRoot()
        assertTrue("text at ${text.bottom}", text.bottom < 400.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp")
    fun `a row swiped to Played that stays in the list is marked once and springs back`() {
        // A show's page keeps the episode after it is marked played (New Episodes drops it).
        var progress by mutableStateOf<ListeningProgress?>(null)
        val marked = mutableListOf<Boolean>()
        val actions = object : EpisodeActions {
            override fun markPlayed(item: SourcedEpisode, played: Boolean) {
                marked += played
                progress = ListeningProgress(positionMs = 0, played = played, updatedAt = java.time.Instant.EPOCH)
            }
        }
        compose.setContent {
            EpisodeRow(EpisodeRowUi(other, null, progress, queued = false, current = false, playing = false, canPlay = true), actions, showsShowTitle = false)
        }
        compose.onNodeWithText("Episode 277").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("one swipe, one mark", listOf(true), marked)
        val title = compose.onNodeWithText("Episode 277").getUnclippedBoundsInRoot()
        assertTrue("the row is back in place, not left open at ${title.left}", title.left < 200.dp)
        repeat(3) {
            compose.onNodeWithText("Episode 277").performTouchInput { swipeRight() }
            compose.waitForIdle()
        }
        assertEquals("each swipe toggles once", listOf(true, false, true, false), marked)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `a skip button's number keeps to its icon at twice the font size`() {
        compose.setContent {
            val density = LocalDensity.current
            androidx.compose.foundation.layout.Column {
                SkipIcon(back = true, seconds = 15, size = 56.dp)
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    SkipIcon(back = true, seconds = 45, size = 56.dp)
                }
            }
        }
        val plain = compose.onNodeWithText("15").getUnclippedBoundsInRoot()
        val large = compose.onNodeWithText("45").getUnclippedBoundsInRoot()
        assertEquals("the number is part of the picture", plain.bottom - plain.top, large.bottom - large.top)
    }
}
