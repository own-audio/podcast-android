package audio.own.podcastplayer.apple

import audio.own.core.strings.R
import audio.own.kit.ownaudio.Grants
import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.eventually
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSignInTest {
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url)
    private val file = File(harness.dir, "apple-sign-in.json")
    private val store = FilePendingAppleStore(file)

    @After
    fun tearDown() {
        fake.close()
        harness.close()
    }

    private fun apple(clock: Clock = Clock.systemUTC()) =
        AppleSignIn(harness.model, store, REDIRECT, harness.log, harness.scope, clock)

    /** Starts a sign-in and waits for the page to open; answers what the browser would get. */
    private fun started(apple: AppleSignIn, origin: String = AppleSignIn.ORIGIN_FORM, replacing: String? = null): Started = runBlocking {
        apple.begin(fake.url, "", replacing, origin)
        eventually { apple.status.value is AppleStatus.Browser }
        val url = (apple.status.value as AppleStatus.Browser).url.toHttpUrl()
        apple.opened()
        apple.appLeft()
        Started(url.queryParameter("state")!!, url.queryParameter("code_challenge")!!, url.encodedPath, url.queryParameter("redirect_uri"))
    }

    private data class Started(val state: String, val challenge: String, val path: String, val redirect: String?)

    private fun tokenRequests() = fake.requests.filter { it.url.encodedPath == "/api/v2/auth/token" }

    @Test
    fun theCodeFromTheBrowserSignsInAndAddsTheServer() = runBlocking {
        val apple = apple()
        val s = started(apple)
        assertEquals("/api/v2/auth/apple/start", s.path)
        assertEquals(REDIRECT, s.redirect)
        assertTrue("the verifier waits on disk while the browser is open", file.exists())
        fake.appleCodes["code-1"] = s.challenge
        apple.redirected("code-1", null, s.state)
        assertTrue("checked at once, before the app's screen returns", apple.status.value is AppleStatus.Working)
        apple.appReturned()
        eventually { apple.status.value is AppleStatus.Done }
        val form = FakeOwnAudio.form(tokenRequests().single())
        assertEquals(Grants.APPLE_CODE, form["grant_type"])
        assertEquals("code-1", form["code"])
        assertEquals("Test phone", form["device_name"])
        assertEquals("android", form["device_kind"])
        assertEquals(1, harness.store.loadAll().size)
        assertFalse("the verifier is gone once used", file.exists())
        apple.dismiss(AppleSignIn.ORIGIN_FORM)
        assertEquals(AppleStatus.Idle, apple.status.value)
    }

    @Test
    fun backFromTheBrowserIsACancelWithoutAWord() = runBlocking {
        val apple = apple()
        started(apple)
        apple.appReturned()
        assertEquals(AppleStatus.Idle, apple.status.value)
        assertFalse(file.exists())
        assertTrue(tokenRequests().isEmpty())
    }

    @Test
    fun theAppOnScreenBeforeTheBrowserLeftIsNotACancel() = runBlocking {
        val apple = apple()
        apple.begin(fake.url, "", null, AppleSignIn.ORIGIN_FORM)
        eventually { apple.status.value is AppleStatus.Browser }
        apple.opened()
        apple.appReturned()
        assertTrue(apple.status.value is AppleStatus.Browser)
    }

    @Test
    fun cancellingAtAppleIsACancelToo() {
        val apple = apple()
        val s = started(apple)
        apple.redirected(null, "access_denied", s.state)
        assertEquals(AppleStatus.Idle, apple.status.value)
        assertFalse(file.exists())
        assertTrue(tokenRequests().isEmpty())
    }

    @Test
    fun anAnswerForAnotherSignInIsRefused() {
        val apple = apple()
        val s = started(apple)
        fake.appleCodes["code-1"] = s.challenge
        apple.redirected("code-1", null, "someone-elses-state")
        assertEquals(R.string.error_apple_state, (apple.status.value as AppleStatus.Failed).text.id)
        assertEquals(AppleSignIn.ORIGIN_FORM, apple.status.value.origin)
        assertTrue("the code is never sent", tokenRequests().isEmpty())
        apple.redirected("code-1", null, null)
        assertTrue(tokenRequests().isEmpty())
    }

    @Test
    fun aUsedOrExpiredCodeAsksToTryAgain() = runBlocking {
        val apple = apple()
        val s = started(apple)
        apple.redirected("never-issued", null, s.state)
        eventually { apple.status.value is AppleStatus.Failed }
        assertEquals(R.string.error_apple_expired, (apple.status.value as AppleStatus.Failed).text.id)
        assertTrue(harness.store.loadAll().isEmpty())
    }

    @Test
    fun theServersOwnFailureSaysAppleReturnedNothing() {
        val apple = apple()
        val s = started(apple)
        apple.redirected(null, "server_error", s.state)
        assertEquals(R.string.error_apple_failed, (apple.status.value as AppleStatus.Failed).text.id)
    }

    @Test
    fun twoFactorIsAskedNext() = runBlocking {
        val apple = apple()
        val s = started(apple)
        fake.appleCodes[FakeOwnAudio.MFA_APPLE_CODE] = s.challenge
        apple.redirected(FakeOwnAudio.MFA_APPLE_CODE, null, s.state)
        eventually { harness.model.state.value.pendingTwoFactor != null }
        eventually { apple.status.value == AppleStatus.Idle }
        harness.model.finishTwoFactor("123456")
        assertEquals(1, harness.store.loadAll().size)
    }

    @Test
    fun theSignInOutlivesTheProcess() = runBlocking {
        val s = started(apple())
        fake.appleCodes["code-1"] = s.challenge
        // Android ended the app while the browser was open; the redirect starts it again.
        val again = apple()
        assertEquals(AppleStatus.Browser(AppleSignIn.ORIGIN_FORM, "", opened = true), again.status.value)
        again.redirected("code-1", null, s.state)
        eventually { again.status.value is AppleStatus.Done }
        assertEquals(1, harness.store.loadAll().size)
    }

    @Test
    fun backFromTheBrowserAfterTheProcessEndedIsStillACancel() {
        started(apple())
        val again = apple()
        again.appReturned()
        assertEquals(AppleStatus.Idle, again.status.value)
        assertFalse(file.exists())
    }

    @Test
    fun anOldWaitingSignInIsDropped() {
        started(apple())
        val later = Clock.fixed(Instant.now().plusMillis(AppleSignIn.PENDING_FOR_MS + 60_000), ZoneOffset.UTC)
        val again = apple(later)
        assertEquals(AppleStatus.Idle, again.status.value)
        assertFalse(file.exists())
    }

    @Test
    fun aRedirectWithNothingWaitingIsIgnored() {
        val apple = apple()
        apple.redirected("code-1", null, "state")
        assertEquals(AppleStatus.Idle, apple.status.value)
        assertTrue(tokenRequests().isEmpty())
    }

    @Test
    fun noBrowserSaysSo() = runBlocking {
        val apple = apple()
        apple.begin(fake.url, "", null, AppleSignIn.ORIGIN_ACCOUNT)
        eventually { apple.status.value is AppleStatus.Browser }
        apple.noBrowser()
        assertEquals(R.string.error_no_browser, (apple.status.value as AppleStatus.Failed).text.id)
        assertFalse(file.exists())
    }

    @Test
    fun aServerWithoutAppleInTheBrowserIsRefusedBeforeTheBrowser() = runBlocking {
        fake.appleWeb = false
        val apple = apple()
        apple.begin(fake.url, "", null, AppleSignIn.ORIGIN_FORM)
        eventually { apple.status.value is AppleStatus.Failed }
        assertEquals(R.string.error_provider_off, (apple.status.value as AppleStatus.Failed).text.id)
        assertNull(store.load())
    }

    @Test
    fun signingInAgainKeepsTheSavedServer() = runBlocking {
        harness.model.signIn(fake.url, "a@example.com", fake.password, "Mine")
        val id = harness.store.loadAll().single().id
        val apple = apple()
        val s = started(apple, AppleSignIn.originServer(id), replacing = id)
        fake.appleCodes["code-2"] = s.challenge
        apple.redirected("code-2", null, s.state)
        eventually { apple.status.value is AppleStatus.Done }
        assertEquals(id, harness.store.loadAll().single().id)
        assertNotNull(harness.store.loadAll().single().auth)
    }

    companion object {
        const val REDIRECT = "audio.own.podcastplayer:/apple"
    }
}
