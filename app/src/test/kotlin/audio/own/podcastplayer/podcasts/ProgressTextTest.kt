package audio.own.podcastplayer.podcasts

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on the emulator: an episode at its very end said "1 min left". */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ProgressTextTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `at the very end it says Played, not a minute left`() {
        assertEquals("Played", ProgressText.leftAtSpeed(res, 0, 1f))
        assertEquals("Played", ProgressText.leftAtSpeed(res, 400, 1.5f))
        assertEquals("Played", ProgressText.leftAtSpeed(res, -20, 1f))
    }

    @Test
    fun `before the end the time left at the speed`() {
        assertEquals("1 min left", ProgressText.leftAtSpeed(res, 20_000, 1f))
        assertEquals("20 min left", ProgressText.leftAtSpeed(res, 30 * 60_000L, 1.5f))
    }
}
