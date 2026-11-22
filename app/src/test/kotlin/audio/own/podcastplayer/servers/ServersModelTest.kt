package audio.own.podcastplayer.servers

import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.ServerAuth
import audio.own.core.strings.R
import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.app.UserFacingException
import audio.own.podcastplayer.eventually
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServersModelTest {
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url, demo = fake.url)
    private val model get() = harness.model

    @After
    fun tearDown() {
        fake.close()
        harness.close()
    }

    private fun expectMessage(id: Int, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected a failure")
        } catch (e: UserFacingException) {
            assertEquals(id, e.text.id)
        }
    }

    @Test
    fun signingInSavesTheServerWithItsIdentityAndAddresses() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.OWN_AUDIO, saved.kind)
        assertEquals("own.audio", saved.displayName)
        assertEquals("srv-1", saved.serverIdentity)
        assertEquals("u1", saved.userId)
        assertEquals("a@example.com", (saved.auth as ServerAuth.OwnAudio).userName)
        assertEquals("r1", (saved.auth as ServerAuth.OwnAudio).tokens?.refresh)
        assertEquals(listOf("http://192.168.1.10:8080"), saved.addresses.filter { it.origin == AddressOrigin.SERVER }.map { it.url })
        assertEquals(saved.id, model.state.value.activeId)
        assertEquals(saved.id, harness.preferences.activeServerId())
        val form = FakeOwnAudio.form(fake.requests.first { it.url.encodedPath == "/api/v2/auth/token" })
        assertEquals("Test phone", form["device_name"])
        assertEquals("android", form["device_kind"])
    }

    @Test
    fun aWrongPasswordSavesNothing() {
        expectMessage(R.string.error_rejected) { model.signIn(fake.url, "a@example.com", "wrong", "") }
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun aServerTooOldIsRefusedBeforeSigningIn() {
        fake.revision = 15
        expectMessage(R.string.error_too_old) { model.signIn(fake.url, "a@example.com", fake.password, "") }
        assertFalse(fake.paths().any { it.endsWith("/auth/token") })
    }

    /** Answers as a server that is not own.audio: own.audio's discovery is not there. */
    private fun notOwnAudio(answers: Map<String, MockResponse>) {
        fake.extra = { request ->
            when (val path = request.url.encodedPath) {
                "/.well-known/own-audio" -> MockResponse.Builder().code(404).body("not here").build()
                else -> answers[path]
            }
        }
    }

    @Test
    fun anAudiobookshelfAddressIsSignedInThere() {
        notOwnAudio(mapOf("/status" to FakeOwnAudio.json("""{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true}""")))
        expectMessage(R.string.error_generic) { model.signIn(fake.url, "a", "b", "") }
        assertTrue(fake.paths().contains("POST /login"))
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun podfetchIsDetectedBeforeAudiobookshelfAndSignedInAsGPodder() = runBlocking {
        notOwnAudio(mapOf("/api/v1/sys/config" to FakeOwnAudio.json("""{"podindexConfigured":false,"rssFeed":"x","basicAuth":true}""")))
        assertEquals(Detected.GPodder(fake.url, "podfetch"), ServerDetector(harness.http, "test", harness.log).detect(fake.url))
        expectMessage(R.string.error_generic) { model.signIn(fake.url, "a", "b", "") }
        assertTrue(fake.paths().contains("POST /api/2/auth/a/login.json"))
    }

    @Test
    fun aGPodderServerRefusesTheProbeWithoutASignIn() = runBlocking {
        notOwnAudio(mapOf("/api/2/devices/own-audio-probe.json" to MockResponse.Builder().code(401).build()))
        assertEquals(Detected.GPodder(fake.url, null), ServerDetector(harness.http, "test", harness.log).detect(fake.url))
        assertEquals(
            listOf("GET /.well-known/own-audio", "GET /api/v1/sys/config", "GET /status", "GET /api/2/devices/own-audio-probe.json"),
            fake.paths(),
        )
    }

    @Test
    fun aChosenKindAsksOnlyForThatKind() = runBlocking {
        notOwnAudio(mapOf("/status" to FakeOwnAudio.json("""{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true}""")))
        val detector = ServerDetector(harness.http, "test", harness.log)
        assertEquals(Detected.Audiobookshelf(fake.url), detector.detect(fake.url, ServerKinds.AUDIOBOOKSHELF))
        assertEquals(listOf("GET /status"), fake.paths())
        // Something answers there, but not own.audio.
        expectMessage(R.string.error_invalid_address) { detector.detect(fake.url, ServerKinds.OWN_AUDIO) }
    }

    @Test
    fun aWebServerOfNoKnownKindIsNotAServerAddress() {
        notOwnAudio(emptyMap())
        expectMessage(R.string.error_invalid_address) { model.signIn(fake.url, "a", "b", "") }
    }

    @Test
    fun aConsoleAddressLeadsToItsApi() {
        assertEquals(listOf("https://api-beta.own.audio"), audio.own.core.servers.TypedAddress.candidates("app-beta.own.audio"))
        assertEquals(listOf("https://api.own.audio"), audio.own.core.servers.TypedAddress.candidates("own.audio"))
    }

    @Test
    fun nothingAnsweringIsUnreachable() {
        expectMessage(R.string.error_unreachable) { model.signIn("http://127.0.0.1:9", "a", "b", "") }
    }

    @Test
    fun twoFactorWaitsForTheCodeAndAWrongCodeKeepsWaiting() = runBlocking {
        fake.twoFactorFor = "a@example.com"
        try {
            model.signIn(fake.url, "a@example.com", fake.password, "Home")
            fail("expected two-factor")
        } catch (e: TwoFactorNeeded) {
            // expected
        }
        assertEquals("mfa-1", model.state.value.pendingTwoFactor?.mfaToken)
        assertTrue(harness.store.loadAll().isEmpty())
        expectMessage(R.string.error_wrong_code) { model.finishTwoFactor("000000") }
        assertNotNull(model.state.value.pendingTwoFactor)
        val signedIn = async { model.signedIn.first() }
        model.finishTwoFactor(" 123456 ")
        assertNull(model.state.value.pendingTwoFactor)
        assertEquals("Home", harness.store.loadAll().single().displayName)
        assertEquals(harness.store.loadAll().single().id, signedIn.await())
    }

    @Test
    fun theDemoSignsInWithTheAccountItsDiscoveryOffers() = runBlocking {
        fake.demo = "demo@example.com" to fake.password
        model.addDemo()
        val saved = harness.store.loadAll().single()
        assertEquals(ServersModel.DEMO_NAME, saved.displayName)
        assertEquals("demo@example.com", FakeOwnAudio.form(fake.requests.first { it.url.encodedPath == "/api/v2/auth/token" })["username"])
    }

    @Test
    fun noDemoOnTheServerSaysSo() {
        expectMessage(R.string.demo_unavailable) { model.addDemo() }
    }

    @Test
    fun signUpRegistersThenSignsIn() = runBlocking {
        model.createAccount("Alex", " a@example.com ", fake.password)
        assertEquals(listOf("POST /api/v2/auth/register", "POST /api/v2/auth/token"), fake.paths().filter { it.contains("/auth/") })
        assertEquals(1, harness.store.loadAll().size)
    }

    @Test
    fun aTakenEmailIsSaidPlainly() {
        fake.extra = { if (it.url.encodedPath == "/api/v2/auth/register") FakeOwnAudio.problem("email_taken", 409) else null }
        expectMessage(R.string.error_email_taken) { model.createAccount("Alex", "a@example.com", "long enough password") }
    }

    @Test
    fun googleIsOfferedOnlyWithTheServersWebClient() = runBlocking {
        assertEquals("web-client-id", model.googleClientId(fake.url))
        fake.google = false
        assertNull(model.googleClientId(fake.url))
        assertNull(model.googleClientId("http://127.0.0.1:9"))
    }

    @Test
    fun googleSendsTheIdTokenAsAJwtBearer() = runBlocking {
        model.signInWithGoogle(fake.url, "good-id-token", "")
        val form = FakeOwnAudio.form(fake.requests.first { it.url.encodedPath == "/api/v2/auth/token" })
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        assertEquals("google", form["provider"])
        assertEquals("good-id-token", form["assertion"])
        assertEquals(1, harness.store.loadAll().size)
    }

    @Test
    fun anExistingPasswordAccountRefusesGoogle() {
        expectMessage(R.string.error_identity_conflict) { model.signInWithGoogle(fake.url, "other-token", "") }
    }

    @Test
    fun signingInAgainKeepsTheServerAndRefusesAnotherOne() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "Mine")
        val id = harness.store.loadAll().single().id
        model.signInAgain(id, "a@example.com", fake.password)
        assertEquals(id, harness.store.loadAll().single().id)
        assertEquals("Mine", harness.store.loadAll().single().displayName)
        fake.identity = "someone-else"
        expectMessage(R.string.error_different_server) { model.signInAgain(id, "a@example.com", fake.password) }
        assertEquals(1, harness.store.loadAll().size)
    }

    /** The server answers as [email] from now on: another user on it. */
    private fun answerAs(email: String, id: String) {
        val me = FakeOwnAudio.ME.replace("a@example.com", email).replace("\"u1\"", "\"$id\"")
        fake.extra = { if (it.url.encodedPath == "/api/v2/me" && it.method == "GET") FakeOwnAudio.json(me) else null }
    }

    private fun tokenRequests() = fake.paths().count { it == "POST /api/v2/auth/token" }

    @Test
    fun anOwnAudioAccountAddedAgainIsRefusedBeforeSigningIn() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "Mine")
        expectMessage(AppR.string.error_already_added) { model.signIn(fake.url + "/", " A@Example.com ", fake.password, "Again") }
        assertEquals(1, tokenRequests())
        assertEquals(listOf("Mine"), harness.store.loadAll().map { it.displayName })
    }

    @Test
    fun theSameAccountThroughGoogleIsRefusedAndItsNewSessionEnded() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        expectMessage(AppR.string.error_already_added) { model.signInWithGoogle(fake.url, "good-id-token", "") }
        assertEquals(1, harness.store.loadAll().size)
        eventually { fake.paths().contains("POST /api/v2/auth/revoke") }
    }

    @Test
    fun anotherUserOnTheSameServerIsAdded() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        answerAs("b@example.com", "u2")
        model.signIn(fake.url, "b@example.com", fake.password, "")
        assertEquals(listOf("a@example.com", "b@example.com"), harness.store.loadAll().map { it.auth.userName })
    }

    @Test
    fun theDemoAddedAgainIsRefused() = runBlocking {
        fake.demo = "demo@example.com" to fake.password
        model.addDemo()
        expectMessage(AppR.string.error_already_added) { model.addDemo() }
        assertEquals(listOf(ServersModel.DEMO_NAME), harness.store.loadAll().map { it.displayName })
    }

    @Test
    fun anAddressTypedDifferentlyNamesTheSameServer() {
        val key = addressKey("https://pod.example.com")
        assertEquals(key, addressKey("http://pod.example.com/"))
        assertEquals(key, addressKey("HTTPS://Pod.Example.COM/"))
        assertEquals(key, addressKey(" pod.example.com "))
        assertEquals(key, addressKey("https://pod.example.com:443"))
        assertEquals(addressKey("http://10.0.0.5:8080"), addressKey("https://10.0.0.5:8080/"))
        assertFalse(key == addressKey("https://pod.example.com:8443"))
        assertFalse(key == addressKey("https://pod.example.com/other"))
    }

    @Test
    fun renamingAndRemoving() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        answerAs("b@example.com", "u2")
        model.signIn(fake.url, "b@example.com", fake.password, "Second")
        val (first, second) = harness.store.loadAll()
        model.rename(first.id, "  Kitchen  ")
        assertEquals("Kitchen", model.state.value.server(first.id)?.displayName)
        assertEquals(second.id, model.state.value.activeId)
        model.remove(second.id)
        assertEquals(listOf(first.id), harness.store.loadAll().map { it.id })
        assertEquals(first.id, model.state.value.activeId)
        assertEquals(1, harness.cleared)
        eventually { fake.paths().contains("POST /api/v2/auth/revoke") }
    }

    @Test
    fun deletingTheAccountExplainsRefusalsAndRemovesTheServerOnSuccess() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val id = harness.store.loadAll().single().id
        fake.deleteProblem = "last_admin"
        expectMessage(R.string.delete_account_last_admin) { model.deleteAccount(id, "pw") }
        fake.deleteProblem = "demo_read_only"
        expectMessage(R.string.delete_account_demo) { model.deleteAccount(id, "pw") }
        fake.deleteProblem = "invalid_credentials"
        expectMessage(R.string.delete_account_wrong_password) { model.deleteAccount(id, "pw") }
        assertEquals(1, harness.store.loadAll().size)
        fake.deleteProblem = null
        model.deleteAccount(id, "pw")
        assertTrue(harness.store.loadAll().isEmpty())
        val delete = fake.requests.last { it.method == "DELETE" && it.url.encodedPath == "/api/v2/me" }
        assertEquals("""{"password":"pw"}""", delete.body?.utf8())
    }

    @Test
    fun checkNowFindsTheAddressAndTheSignIn() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val id = harness.store.loadAll().single().id
        model.checkNow(id)
        assertEquals(ConnectionCheck.Status.OK, model.state.value.checks[id]?.status)
        assertEquals(ConnectionState.Connected(fake.url), model.state.value.connections[id])
        assertFalse(model.state.value.isRefused(id))
    }

    @Test
    fun aRefusedRefreshMarksTheServerForSigningInAgain() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val id = harness.store.loadAll().single().id
        fake.meStatus = 401
        fake.refreshRefused = true
        model.checkNow(id)
        assertEquals(ConnectionCheck.Status.REJECTED, model.state.value.checks[id]?.status)
        assertTrue(model.state.value.isRefused(id))
        assertNull((harness.store.loadAll().single().auth as ServerAuth.OwnAudio).tokens)
    }

    @Test
    fun anAnotherServerAtTheAddressIsNotUsed() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val id = harness.store.loadAll().single().id
        // The home address it learned answers nothing here; the one it was added with answers as another server.
        fake.identity = "someone-else"
        model.chooseAddress(id)
        assertEquals(ConnectionState.Unreachable, model.state.value.connections[id])
    }

    @Test
    fun addressesTheListenerAddsCanBeRemoved() = runBlocking {
        model.signIn(fake.url, "a@example.com", fake.password, "")
        val id = harness.store.loadAll().single().id
        model.addAddress(id, "10.0.0.5:4533")
        assertTrue(model.state.value.server(id)!!.addresses.any { it.url == "http://10.0.0.5:4533" && it.origin == AddressOrigin.USER })
        model.removeAddress(id, "http://10.0.0.5:4533")
        assertFalse(model.state.value.server(id)!!.addresses.any { it.origin == AddressOrigin.USER })
        expectMessage(R.string.error_invalid_address) { model.addAddress(id, "http://") }
    }
}
