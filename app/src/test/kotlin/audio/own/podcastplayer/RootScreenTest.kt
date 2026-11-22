package audio.own.podcastplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import audio.own.podcast.library.SourceRef
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.EpisodeActions
import audio.own.podcastplayer.podcasts.NewEpisodesContent
import audio.own.podcastplayer.podcasts.OfflineReason
import audio.own.podcastplayer.podcasts.PodcastState
import audio.own.podcastplayer.podcasts.SourceInfo
import audio.own.podcastplayer.ui.Tab
import audio.own.podcastplayer.ui.TabsScreen
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootScreenTest {
    @get:Rule val compose = createComposeRule()

    private val phone = SourceInfo(SourceRef.Phone, "This Device", null)

    private fun server(name: String) = SourceInfo(SourceRef.Server(UUID.nameUUIDFromBytes(name.toByteArray())), "Server $name", "own_audio")

    @Composable
    private fun Tabs(state: PodcastState = PodcastState(sources = listOf(phone)), offlineMode: Boolean = false, onOfflineMode: (Boolean) -> Unit = {}) {
        TabsScreen { tab ->
            if (tab == Tab.NEW_EPISODES) {
                NewEpisodesContent(
                    state = state,
                    player = PodcastPlayer.State(),
                    actions = EpisodeActions.None,
                    canPlay = { true },
                    offlineMode = offlineMode,
                    onOfflineMode = onOfflineMode,
                    onSettings = {},
                    onSource = {},
                    onRefresh = {},
                )
            } else {
                Text(tab.name)
            }
        }
    }

    @Test
    fun showsTheFourTabsInOrder() {
        compose.setContent { Tabs() }
        listOf("Shows", "Downloads", "Discover").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        // New Episodes is the tab and the bar's title.
        compose.onAllNodesWithText("New Episodes").assertCountEquals(2)
        compose.onAllNodesWithText("No New Episodes").assertCountEquals(1)
        compose.onNodeWithText("Discover").performClick()
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { Tabs() }
        listOf("Pořady", "Stažené", "Objevovat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        compose.onAllNodesWithText("Nové epizody").assertCountEquals(2)
    }

    @Test
    fun theSourceSwitchOnlyWithAServerBesideThisDevice() {
        var state by mutableStateOf(PodcastState(sources = listOf(phone)))
        compose.setContent { Tabs(state) }
        compose.onAllNodesWithContentDescription("All Sources").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        state = PodcastState(sources = listOf(server("a"), phone))
        compose.onNodeWithContentDescription("All Sources").performClick()
        compose.onAllNodesWithText("This Device").assertCountEquals(1)
        compose.onAllNodesWithText("Server a").assertCountEquals(1)
    }

    @Test
    fun offlineModeFromTheBarAndItsLine() {
        var asked: Boolean? = null
        var offline by mutableStateOf<OfflineReason?>(null)
        compose.setContent { Tabs(PodcastState(sources = listOf(phone), offline = offline), onOfflineMode = { asked = it }) }
        compose.onNodeWithContentDescription("Offline Mode").performClick()
        assertEquals(true, asked)
        compose.onAllNodesWithText("Offline Mode is on").assertCountEquals(0)
        offline = OfflineReason.CHOSEN
        compose.onAllNodesWithText("Offline Mode is on").assertCountEquals(1)
        offline = OfflineReason.NO_NETWORK
        compose.onAllNodesWithText("No network").assertCountEquals(1)
    }
}
