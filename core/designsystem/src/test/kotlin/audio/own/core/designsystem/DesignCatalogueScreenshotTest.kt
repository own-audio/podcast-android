package audio.own.core.designsystem

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.components.DesignCatalogue
import audio.own.core.designsystem.theme.OwnAudioTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The catalogue in light, dark, Czech and at the largest font scale. Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-xxhdpi")
class DesignCatalogueScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean) {
        compose.setContent { OwnAudioTheme(darkTheme = dark, reduceMotion = true) { DesignCatalogue() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun light() = shoot("catalogue_light", dark = false)

    @Test fun dark() = shoot("catalogue_dark", dark = true)

    @Test @Config(qualifiers = "+cs") fun czech() = shoot("catalogue_cs", dark = false)

    @Test @Config(fontScale = 2f) fun largestText() = shoot("catalogue_font200", dark = false)
}
