package audio.own.podcastplayer.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import audio.own.core.servers.ServerAuth
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.core.designsystem.theme.AccentChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on the emulator: after two servers were swiped away, the next row stayed open with no question. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class SettingsSwipeTest {
    @get:Rule val compose = createComposeRule()

    private fun gpodder(id: String, name: String) = ServerRecord(
        id = id, kind = ServerKinds.GPODDER, displayName = name, host = "https://$id.example.com",
        auth = ServerAuth.GPodder("alex", "secret", "own-audio-podcasts-0123abcd"),
    )

    @Test
    fun `each server swiped away asks first, also the one that moved up into a removed row's place`() {
        var servers by mutableStateOf(listOf(gpodder("a", "First"), gpodder("b", "Second"), gpodder("c", "Third")))
        val removed = mutableListOf<String>()
        compose.setContent {
            SettingsContent(
                ui = SettingsUi(servers, accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1"),
                onBack = {}, onOpenServer = {}, onRemoveServer = { id -> removed += id; servers = servers.filterNot { it.id == id } },
                onAddServer = {}, onOfflineMode = {}, onPlayback = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
            )
        }
        for (name in listOf("First", "Second", "Third")) {
            compose.onNodeWithText(name).performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().let { check(it.isNotEmpty()) { "no question for $name" } }
            compose.onNodeWithText("Remove $name?").assertExists()
            compose.onAllNodesWithText("Remove")[compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size - 1].performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("a", "b", "c"), removed)
    }
}
