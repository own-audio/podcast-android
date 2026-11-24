package audio.own.podcastplayer.apple

import audio.own.core.strings.R
import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import audio.own.podcastplayer.onboarding.AccountMode
import audio.own.podcastplayer.onboarding.OwnAudioAccountViewModel
import audio.own.podcastplayer.onboarding.ServerFormViewModel
import audio.own.podcastplayer.servers.SignInAgainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The sign-in screens with Sign in with Apple: the button where the server offers it, busy, errors, done. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppleViewModelsTest {
    private val main = mainThread()
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url, demo = fake.url)
    private val store = FilePendingAppleStore(File(harness.dir, "apple-sign-in.json"))
    private val apple = newApple()

    private fun newApple() = AppleSignIn(harness.model, store, AppleSignInTest.REDIRECT, harness.log, harness.scope)

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        fake.close()
        harness.close()
    }

    /** Waits for the page, opens and leaves for it; the state and challenge the browser carries. */
    private suspend fun browser(apple: AppleSignIn): Pair<String, String> {
        eventually { (apple.status.value as? AppleStatus.Browser)?.url?.isNotEmpty() == true }
        val url = (apple.status.value as AppleStatus.Browser).url.toHttpUrl()
        apple.opened()
        apple.appLeft()
        return url.queryParameter("state")!! to url.queryParameter("code_challenge")!!
    }

    @Test
    fun theFormOffersAppleOnlyWhereTheServerRunsItInTheBrowser() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false, lookupDelayMs = 0, apple = apple)
        vm.setAddress(fake.url)
        eventually { vm.state.value.appleWeb }
        assertNull("no Google in the foss build", vm.state.value.googleClientId)
        fake.appleWeb = false
        vm.setAddress("${fake.url}/")
        Thread.sleep(200)
        assertFalse(vm.state.value.appleWeb)
    }

    @Test
    fun noAppleWithoutTheBrowserFlow() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false, lookupDelayMs = 0)
        vm.setAddress(fake.url)
        Thread.sleep(200)
        assertFalse(vm.state.value.appleWeb)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun theFormIsBusyWhileTheBrowserIsOpenAndClosesWhenSignedIn() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = true, lookupDelayMs = 0, apple = apple)
        vm.setAddress(fake.url)
        eventually { vm.state.value.appleWeb && vm.state.value.googleClientId != null }
        vm.signInWithApple()
        val (state, challenge) = browser(apple)
        eventually { vm.state.value.appleWorking }
        assertTrue(vm.state.value.busy)
        fake.appleCodes["c"] = challenge
        apple.redirected("c", null, state)
        eventually { vm.state.value.done }
        assertFalse(vm.state.value.appleWorking)
        assertEquals(1, harness.store.loadAll().size)
        eventually { apple.status.value == AppleStatus.Idle }
    }

    @Test
    fun cancellingLeavesTheFormAsItWas() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false, lookupDelayMs = 0, apple = apple)
        vm.setAddress(fake.url)
        eventually { vm.state.value.appleWeb }
        vm.signInWithApple()
        browser(apple)
        eventually { vm.state.value.appleWorking }
        apple.appReturned()
        eventually { !vm.state.value.appleWorking }
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.done)
    }

    @Test
    fun aStateMismatchAndAnExpiredCodeAreShownOnTheScreen() = runBlocking {
        val vm = OwnAudioAccountViewModel(AccountMode.SIGN_IN, harness.model, harness.log, googleAvailable = false, apple = apple)
        eventually { vm.state.value.appleWeb }
        vm.signInWithApple()
        val (state, _) = browser(apple)
        apple.redirected("c", null, "forged")
        eventually { vm.state.value.error != null }
        assertEquals(R.string.error_apple_state, vm.state.value.error?.id)
        assertFalse(vm.state.value.appleWorking)
        assertEquals("the screen took it", AppleStatus.Idle, apple.status.value)

        vm.signInWithApple()
        val (second, _) = browser(apple)
        assertNull("a new try clears the old error", vm.state.value.error)
        apple.redirected("used-already", null, second)
        eventually { vm.state.value.error != null }
        assertEquals(R.string.error_apple_expired, vm.state.value.error?.id)
        assertFalse(state == second)
    }

    @Test
    fun twoFactorAfterAppleIsAskedOverTheScreen() = runBlocking {
        val vm = OwnAudioAccountViewModel(AccountMode.SIGN_UP, harness.model, harness.log, googleAvailable = false, apple = apple)
        eventually { vm.state.value.appleWeb }
        vm.signInWithApple()
        val (state, challenge) = browser(apple)
        fake.appleCodes[FakeOwnAudio.MFA_APPLE_CODE] = challenge
        apple.redirected(FakeOwnAudio.MFA_APPLE_CODE, null, state)
        eventually { harness.model.state.value.pendingTwoFactor != null }
        eventually { !vm.state.value.appleWorking }
        assertNull(vm.state.value.error)
        harness.model.finishTwoFactor("123456")
        eventually { vm.state.value.done }
    }

    @Test
    fun aScreenMadeAgainAfterTheProcessEndedGetsTheOutcome() = runBlocking {
        val before = OwnAudioAccountViewModel(AccountMode.SIGN_IN, harness.model, harness.log, googleAvailable = false, apple = apple)
        eventually { before.state.value.appleWeb }
        before.signInWithApple()
        val (state, challenge) = browser(apple)
        fake.appleCodes["c"] = challenge
        // A new process: a new AppleSignIn from the file, the redirect, then the screen again.
        val restored = newApple()
        restored.redirected("c", null, state)
        eventually { restored.status.value is AppleStatus.Done }
        val after = OwnAudioAccountViewModel(AccountMode.SIGN_IN, harness.model, harness.log, googleAvailable = false, apple = restored)
        eventually { after.state.value.done }
        assertEquals(AppleStatus.Idle, restored.status.value)
    }

    @Test
    fun signInAgainWithAppleKeepsTheServer() = runBlocking {
        harness.model.signIn(fake.url, "a@example.com", fake.password, "Mine")
        val id = harness.store.loadAll().single().id
        val vm = SignInAgainViewModel(id, harness.model, harness.log, googleAvailable = false, apple = apple)
        eventually { vm.state.value.appleWeb }
        vm.signInWithApple()
        val (state, challenge) = browser(apple)
        fake.appleCodes["c"] = challenge
        apple.redirected("c", null, state)
        eventually { vm.state.value.done }
        assertEquals(id, harness.store.loadAll().single().id)
        assertEquals("Mine", harness.store.loadAll().single().displayName)
    }
}
