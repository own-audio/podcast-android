package audio.own.podcastplayer.apple

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Records the answers the redirect hands over, instead of the whole app. */
class RedirectsApp : Application(), AppleRedirects {
    val answers = mutableListOf<Triple<String?, String?, String?>>()
    override fun appleRedirected(code: String?, error: String?, state: String?) {
        answers += Triple(code, error, state)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RedirectsApp::class)
class AppleRedirectActivityTest {
    private val id = "audio.own.podcastplayer"

    @Test
    fun onlyThisAppsAppleAddressIsRead() {
        assertEquals(AppleRedirectActivity.Answer("c1", null, "s1"), AppleRedirectActivity.parse(Uri.parse("$id:/apple?code=c1&state=s1"), id))
        assertEquals(AppleRedirectActivity.Answer(null, "access_denied", "s1"), AppleRedirectActivity.parse(Uri.parse("$id:/apple?error=access_denied&state=s1"), id))
        for (other in listOf(
            "$id:/other?code=c1&state=s1",
            "$id:/apple/more?code=c1",
            "$id://apple?code=c1&state=s1",
            "$id://evil.example/apple?code=c1",
            "$id:apple?code=c1",
            "$id:/apple?code=c1#frag",
            "audio.own.musicplayer:/apple?code=c1&state=s1",
            "https://own.audio/apple?code=c1&state=s1",
        )) {
            assertNull(other, AppleRedirectActivity.parse(Uri.parse(other), id))
        }
        assertNull(AppleRedirectActivity.parse(null, id))
    }

    @Test
    fun theManifestSendsTheAppleAddressToTheRedirectActivity() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}:/apple?code=c1&state=s1"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(app.packageName)
        val found = app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertEquals(listOf(AppleRedirectActivity::class.java.name), found)
    }

    @Test
    fun theAnswerIsHandedOverAndTheAppComesBack() {
        val app = RuntimeEnvironment.getApplication() as RedirectsApp
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}:/apple?code=c1&state=s1&extra=x"))
        val controller = Robolectric.buildActivity(AppleRedirectActivity::class.java, intent).create()
        assertEquals(listOf(Triple("c1", null, "s1")), app.answers)
        val next = shadowOf(controller.get()).nextStartedActivity
        assertNotNull(next)
        assertEquals("audio.own.podcastplayer.MainActivity", next.component?.className)
        assertNull("nothing from the browser is passed on", next.data)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun anythingElseIsDropped() {
        val app = RuntimeEnvironment.getApplication() as RedirectsApp
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${app.packageName}:/settings?code=c1&state=s1"))
        val controller = Robolectric.buildActivity(AppleRedirectActivity::class.java, intent).create()
        assertTrue(app.answers.isEmpty())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertTrue(controller.get().isFinishing)
    }
}
