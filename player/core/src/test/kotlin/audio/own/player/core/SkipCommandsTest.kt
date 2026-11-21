package audio.own.player.core

import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkipCommandsTest {
    @Test
    fun `numbered icons where Media3 has them`() {
        assertEquals(CommandButton.ICON_SKIP_BACK_15, SkipCommands.backIcon(15))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, SkipCommands.forwardIcon(30))
        assertEquals(CommandButton.ICON_SKIP_BACK, SkipCommands.backIcon(20))
    }

    @Test
    fun `the buttons carry the skip commands`() {
        val buttons = SkipCommands.buttons(SkipIntervals(15, 30), "Back", "Forward")
        assertEquals(listOf(SkipCommands.ACTION_SKIP_BACK, SkipCommands.ACTION_SKIP_FORWARD), buttons.map { it.sessionCommand?.customAction })
    }
}
