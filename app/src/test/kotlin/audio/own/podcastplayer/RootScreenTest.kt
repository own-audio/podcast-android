package audio.own.podcastplayer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsTheFourTabs() {
        compose.setContent { RootScreen() }
        listOf("New Episodes", "Shows", "Downloads", "Discover").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        compose.onNodeWithText("Discover").performClick()
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { RootScreen() }
        listOf("Nové epizody", "Pořady", "Stažené", "Objevovat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
    }
}
