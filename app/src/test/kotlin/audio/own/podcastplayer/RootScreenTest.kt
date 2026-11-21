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
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.podcastplayer.ui.OfflineReason
import audio.own.podcastplayer.ui.TabsScreen
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

    private fun server(id: String) = ServerRecord(id, "own_audio", "Server $id", "https://$id.example.com", ServerAuth.OwnAudio("a@example.com", null))

    @Test
    fun showsTheFourTabsInOrder() {
        compose.setContent { TabsScreen() }
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
        compose.setContent { TabsScreen() }
        listOf("Pořady", "Stažené", "Objevovat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        compose.onAllNodesWithText("Nové epizody").assertCountEquals(2)
    }

    @Test
    fun theSourceSwitchOnlyWithAServerBesideThisDevice() {
        var servers by mutableStateOf(emptyList<ServerRecord>())
        compose.setContent { TabsScreen(servers = servers) }
        compose.onAllNodesWithContentDescription("All Sources").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        servers = listOf(server("a"))
        compose.onNodeWithContentDescription("All Sources").performClick()
        compose.onAllNodesWithText("This Device").assertCountEquals(1)
        compose.onAllNodesWithText("Server a").assertCountEquals(1)
    }

    @Test
    fun offlineModeFromTheBarAndItsLine() {
        var asked: Boolean? = null
        var offline by mutableStateOf<OfflineReason?>(null)
        compose.setContent { TabsScreen(offline = offline, onOfflineMode = { asked = it }) }
        compose.onNodeWithContentDescription("Offline Mode").performClick()
        assertEquals(true, asked)
        compose.onAllNodesWithText("Offline Mode is on").assertCountEquals(0)
        offline = OfflineReason.CHOSEN
        compose.onAllNodesWithText("Offline Mode is on").assertCountEquals(1)
        offline = OfflineReason.NO_NETWORK
        compose.onAllNodesWithText("No network").assertCountEquals(1)
    }
}
