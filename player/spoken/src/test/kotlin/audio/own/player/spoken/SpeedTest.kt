package audio.own.player.spoken

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SpeedTest {
    @Test
    fun `speed is rounded to 0_05 and bounded`() {
        assertEquals(1.35f, PlaybackSpeed.normalized(1.37f))
        assertEquals(3.0f, PlaybackSpeed.normalized(9f))
        assertEquals(0.5f, PlaybackSpeed.normalized(0.1f))
        assertEquals(1.75f, PlaybackSpeed.normalized(1.75f))
        assertEquals(1.0f, PlaybackSpeed.normalized(Float.NaN))
    }

    @Test
    fun `speed text keeps two decimals and drops trailing zeros`() {
        val en = Locale.US
        assertEquals("1×", SpeedText.text(1f, en))
        assertEquals("1.25×", SpeedText.text(1.25f, en))
        assertEquals("1.75×", SpeedText.text(1.75f, en))
        assertEquals("0.75×", SpeedText.text(0.75f, en))
        assertEquals("1.5×", SpeedText.text(1.5f, en))
        assertEquals("1.15×", SpeedText.text(1.15f, en))
        assertEquals("1.05×", SpeedText.text(1.05f, en))
        assertEquals("3×", SpeedText.text(3f, en))
        assertEquals("1.35×", SpeedText.text(PlaybackSpeed.normalized(1.37f), en))
    }

    @Test
    fun `speed text uses the locale's decimal sign`() {
        assertEquals("0,75×", SpeedText.text(0.75f, Locale.forLanguageTag("cs-CZ")))
        assertEquals("1,25", SpeedText.number(1.25f, Locale.GERMANY))
    }
}
