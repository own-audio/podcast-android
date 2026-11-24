package audio.own.podcastplayer.widget

import audio.own.podcast.library.Episode
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.PodcastState
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The player widgets are redrawn on a change and once a minute while playing, never while paused. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPlayerUpdatesTest {
    private fun episode(id: String) = SourcedEpisode(SourceRef.Phone, Episode(id = id, showId = "s1", title = "Episode $id", durationMs = 3_600_000))

    @Test
    fun `redrawn on play, pause and another episode, and once a minute only while playing`() = runTest {
        val player = MutableStateFlow(PodcastPlayer.State())
        var redraws = 0
        val updater = WidgetUpdater(
            context = RuntimeEnvironment.getApplication(),
            store = WidgetStore(Files.createTempDirectory("widget").toFile()),
            library = MutableStateFlow(PodcastState()),
            player = player,
            jpeg = { null },
            log = AppLog(Files.createTempDirectory("logs").toFile()),
            redraw = {},
            redrawPlayers = { redraws++ },
        )
        updater.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals("drawn once at the start", 1, redraws)

        player.value = PodcastPlayer.State(item = episode("e1"), isPlaying = true)
        advanceTimeBy(1_000)
        assertEquals(2, redraws)
        player.value = player.value.copy(positionMs = 5_000)
        advanceTimeBy(59_500)
        assertEquals("a minute later, for the progress; not for every tick", 3, redraws)

        player.value = player.value.copy(isPlaying = false)
        advanceTimeBy(1_000)
        assertEquals(4, redraws)
        advanceTimeBy(10 * 60_000)
        assertEquals("nothing while paused", 4, redraws)

        player.value = PodcastPlayer.State(item = episode("e2"))
        advanceTimeBy(1_000)
        assertEquals("another episode", 5, redraws)
    }
}
