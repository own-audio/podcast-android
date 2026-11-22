package audio.own.podcastplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on the emulator: Back on the Shows tab left the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabsBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `Back on another tab goes to New Episodes, and Back there leaves the app`() {
        compose.setContent { TabsScreen { tab -> Text("content ${tab.name}") } }
        compose.onNodeWithText("Shows").performClick()
        compose.onNodeWithText("content SHOWS").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("content NEW_EPISODES").assertExists()
        assertFalse(compose.activity.isFinishing)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("on New Episodes, Back is the system's", compose.activity.isFinishing)
    }
}
