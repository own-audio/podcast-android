package audio.own.podcastplayer.onboarding

import audio.own.core.strings.R
import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.mainThread
import audio.own.podcastplayer.servers.SignInAgainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelsTest {
    private val main = mainThread()
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url, demo = fake.url)

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        fake.close()
        harness.close()
    }

    @Test
    fun theFormAddsOnlyWithAnAddressAUsernameAndAPassword() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false)
        assertFalse(vm.state.value.canConnect)
        vm.setAddress(fake.url)
        vm.setUsername("a@example.com")
        assertFalse(vm.state.value.canConnect)
        vm.setPassword(fake.password)
        assertTrue(vm.state.value.canConnect)
        vm.connect()
        eventually { vm.state.value.done }
        assertEquals(1, harness.store.loadAll().size)
        assertFalse(vm.state.value.connecting)
    }

    @Test
    fun theFormShowsWhyItFailed() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false)
        vm.setAddress(fake.url)
        vm.setUsername("a@example.com")
        vm.setPassword("wrong")
        vm.connect()
        eventually { vm.state.value.error != null }
        assertEquals(R.string.error_rejected, vm.state.value.error?.id)
        assertFalse(vm.state.value.done)
    }

    @Test
    fun theGoogleButtonWaitsForTheAddressToSettle() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = true, lookupDelayMs = 50)
        vm.setAddress("ab")
        vm.setAddress(fake.url)
        eventually { vm.state.value.googleClientId != null }
        assertEquals("web-client-id", vm.state.value.googleClientId)
        // Only the address that stayed was asked about.
        assertEquals(1, fake.paths().count { it == "GET /.well-known/own-audio" })
        vm.googleFailed("configuration")
        assertNull(vm.state.value.googleClientId)
    }

    @Test
    fun noGoogleForAudiobookshelfOrGPodder() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = true, lookupDelayMs = 0)
        vm.setKind(ServerKinds.GPODDER)
        vm.setAddress(fake.url)
        Thread.sleep(200)
        assertNull(vm.state.value.googleClientId)
        assertTrue(fake.requests.isEmpty())
        vm.setKind(ServerKinds.OWN_AUDIO)
        eventually { vm.state.value.googleClientId != null }
    }

    @Test
    fun aChosenKindIsWhatIsSignedInTo() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false)
        vm.setKind(ServerKinds.AUDIOBOOKSHELF)
        vm.setAddress(fake.url)
        vm.setUsername("a@example.com")
        vm.setPassword(fake.password)
        vm.connect()
        eventually { vm.state.value.error != null }
        // own.audio answers there, but Audiobookshelf was asked for: it isn't one.
        assertEquals(R.string.error_invalid_address, vm.state.value.error?.id)
        assertFalse(fake.paths().contains("GET /.well-known/own-audio"))
    }

    @Test
    fun noGoogleInTheFossBuild() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false, lookupDelayMs = 0)
        vm.setAddress(fake.url)
        Thread.sleep(200)
        assertNull(vm.state.value.googleClientId)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun theDemoButton() = runBlocking {
        val vm = ServerFormViewModel(harness.model, harness.log, googleAvailable = false)
        vm.tryDemo()
        eventually { vm.state.value.error != null }
        assertEquals(R.string.demo_unavailable, vm.state.value.error?.id)
        fake.demo = "demo@example.com" to fake.password
        vm.tryDemo()
        eventually { vm.state.value.done }
        assertEquals("own.audio demo", harness.store.loadAll().single().displayName)
    }

    @Test
    fun signUpNeedsANameAndTwelveCharacters() = runBlocking {
        val vm = OwnAudioAccountViewModel(AccountMode.SIGN_UP, harness.model, harness.log, googleAvailable = true)
        eventually { vm.state.value.googleClientId != null }
        vm.setEmail("a@example.com")
        vm.setPassword("short")
        vm.setName("Alex")
        assertFalse(vm.state.value.canSubmit)
        vm.setPassword(fake.password)
        assertTrue(vm.state.value.canSubmit)
        vm.submit()
        eventually { vm.state.value.done }
        assertTrue(fake.paths().contains("POST /api/v2/auth/register"))
    }

    @Test
    fun twoFactorAfterTheAccountSheet() = runBlocking {
        fake.twoFactorFor = "a@example.com"
        val account = OwnAudioAccountViewModel(AccountMode.SIGN_IN, harness.model, harness.log, googleAvailable = false)
        account.setEmail("a@example.com")
        account.setPassword(fake.password)
        account.submit()
        eventually { harness.model.state.value.pendingTwoFactor != null }
        assertFalse(account.state.value.done)
        assertNull(account.state.value.error)

        val code = TwoFactorViewModel(harness.model)
        code.setCode("999999")
        code.check()
        eventually { code.state.value.error != null }
        assertEquals(R.string.error_wrong_code, code.state.value.error?.id)
        code.setCode("123456")
        code.check()
        eventually { account.state.value.done }
        assertNull(harness.model.state.value.pendingTwoFactor)
    }

    @Test
    fun cancellingTwoFactorDropsTheSignIn() = runBlocking {
        fake.twoFactorFor = "a@example.com"
        val account = OwnAudioAccountViewModel(AccountMode.SIGN_IN, harness.model, harness.log, googleAvailable = false)
        account.setEmail("a@example.com")
        account.setPassword(fake.password)
        account.submit()
        eventually { harness.model.state.value.pendingTwoFactor != null }
        TwoFactorViewModel(harness.model).cancel()
        assertNull(harness.model.state.value.pendingTwoFactor)
        assertTrue(harness.store.loadAll().isEmpty())
    }

    @Test
    fun signInAgainKeepsTheUsername() = runBlocking {
        harness.model.signIn(fake.url, "a@example.com", fake.password, "Mine")
        val id = harness.store.loadAll().single().id
        val vm = SignInAgainViewModel(id, harness.model, harness.log, googleAvailable = false)
        assertEquals("a@example.com", vm.state.value.username)
        assertEquals("Mine", vm.state.value.title)
        assertFalse(vm.state.value.canSubmit)
        vm.setPassword(fake.password)
        vm.submit()
        eventually { vm.state.value.done }
        assertEquals(1, harness.store.loadAll().size)
    }
}
