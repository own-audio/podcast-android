package audio.own.podcastplayer.servers

import audio.own.core.servers.ServerAuth
import audio.own.core.strings.R
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.app.UserFacingException
import audio.own.podcastplayer.eventually
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import audio.own.podcastplayer.mainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(text).build()

private fun status(code: Int) = MockResponse.Builder().code(code).build()

/** A pretend server of another kind: own.audio's discovery is not there, [route] answers the rest. */
private class FakeServer(private val route: (RecordedRequest) -> MockResponse?) : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return route(request) ?: status(404)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    override fun close() = server.close()
}

/** Audiobookshelf 2.37: a JWT access token (exp 2030), a refresh token, [libraries] as its libraries. */
private fun audiobookshelf(libraries: String, upload: Boolean = true) = FakeServer { request ->
    when (request.method to request.url.encodedPath) {
        "GET" to "/status" -> json("""{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true}""")
        "POST" to "/login" -> if (request.body?.utf8()?.contains("\"right\"") == true) {
            json(
                """{"user":{"id":"u7","username":"alex","type":"user","accessToken":"$JWT","refreshToken":"r1","permissions":{"upload":$upload}},"userDefaultLibraryId":"lib1","serverSettings":{"version":"2.37.1"}}""",
            )
        } else {
            status(401)
        }
        "GET" to "/api/libraries" -> json("""{"libraries":$libraries}""")
        "GET" to "/api/me" -> if (request.headers["Authorization"] == "Bearer $JWT") json("""{"id":"u7","username":"alex","mediaProgress":[]}""") else status(401)
        else -> null
    }
}

/** `{"exp":1893456000}`: 2030-01-01. */
private const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE4OTM0NTYwMDB9.c2ln"

/** podfetch: its own config path, a 403 for a wrong password, a 409 for its admin. */
private class Podfetch : AutoCloseable {
    val devices: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val fake = FakeServer { request ->
        val path = request.url.encodedPath
        val auth = request.headers["Authorization"]
        when {
            path == "/api/v1/sys/config" -> json("""{"podindexConfigured":false,"rssFeed":"https://pod.example/rss","serverUrl":"https://pod.example","basicAuth":true,"oidcConfigured":false}""")
            path == "/api/2/devices/own-audio-probe.json" -> status(401)
            path == "/api/2/auth/admin/login.json" -> status(409)
            path == "/api/2/auth/alex/login.json" -> if (auth == Credentials.basic("alex", "right")) json("{}") else status(403)
            path.startsWith("/api/2/devices/alex/") && request.method == "POST" -> {
                devices += path.substringAfterLast('/') to request.body?.utf8().orEmpty()
                json("{}")
            }
            else -> null
        }
    }

    override fun close() = fake.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
class OtherServersTest {
    private val harness = ModelHarness()
    private val model get() = harness.model
    private val closing = mutableListOf<AutoCloseable>()
    private val main = mainThread()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        closing.forEach { it.close() }
        harness.close()
    }

    private fun <T : AutoCloseable> T.kept() = also { closing += it }

    private fun expectMessage(id: Int, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected a failure")
        } catch (e: UserFacingException) {
            assertEquals(id, e.text.id)
        }
    }

    private val podcastLibraries =
        """[{"id":"lib2","name":"Talks","mediaType":"podcast","displayOrder":2},{"id":"lib1","name":"Podcasts","mediaType":"podcast","displayOrder":1},{"id":"b1","name":"Books","mediaType":"book"}]"""

    @Test
    fun audiobookshelfIsFoundFromTheAddressAndSavedWithItsPodcastLibraries() = runBlocking {
        val abs = audiobookshelf(podcastLibraries).kept()
        model.signIn(abs.url, "alex", "right", "")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.AUDIOBOOKSHELF, saved.kind)
        assertEquals("u7", saved.userId)
        val auth = saved.auth as ServerAuth.Audiobookshelf
        assertEquals("alex", auth.userName)
        assertEquals("r1", auth.tokens?.refresh)
        assertEquals(1_893_456_000_000L, auth.tokens?.expiresAtEpochMs)
        assertEquals("podcast libraries only, in the server's order", listOf("Podcasts", "Talks"), saved.libraries!!.map { it.name })
        assertTrue("an account that may upload adds shows", harness.preferences.serverExtras.first()[saved.id]!!.canAddShows)
        assertTrue(model.audiobookshelf(saved.id) != null)
        eventually { model.state.value.connections[saved.id] is ConnectionState.Connected }
        model.checkNow(saved.id)
        assertEquals(ConnectionCheck.Status.OK, model.state.value.checks[saved.id]?.status)
    }

    @Test
    fun anAudiobookshelfAccountWithoutAPodcastLibraryIsNotAdded() {
        val abs = audiobookshelf("""[{"id":"b1","name":"Books","mediaType":"book"}]""").kept()
        expectMessage(AppR.string.error_no_podcast_library) { model.signIn(abs.url, "alex", "right", "", kind = ServerKinds.AUDIOBOOKSHELF) }
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun aWrongAudiobookshelfPasswordIsRefused() {
        val abs = audiobookshelf(podcastLibraries).kept()
        expectMessage(R.string.error_rejected) { model.signIn(abs.url, "alex", "wrong", "") }
    }

    @Test
    fun aGPodderAccountKeepsItsPasswordAndRegistersThisDevice() = runBlocking {
        val podfetch = Podfetch().kept()
        harness.preferences.install = "a1b2c3d4-e5f6-0000-0000-000000000000"
        model.signIn(podfetch.fake.url, "alex", "right", "")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.GPODDER, saved.kind)
        val auth = saved.auth as ServerAuth.GPodder
        assertEquals("right", auth.password)
        assertEquals("own-audio-podcasts-a1b2c3d4", auth.deviceId)
        val (device, body) = podfetch.devices.single()
        assertEquals("own-audio-podcasts-a1b2c3d4.json", device)
        assertTrue("a mobile device, captioned with the phone's name", body.contains("\"type\":\"mobile\"") && body.contains("Test phone"))
        assertEquals("podfetch", harness.preferences.serverExtras.first()[saved.id]!!.software)
        assertTrue(
            "every call carries the credentials, none a cookie",
            podfetch.fake.requests.filter { it.url.encodedPath.startsWith("/api/2/auth") }.all { it.headers["Authorization"] != null && it.headers["Cookie"] == null },
        )
    }

    @Test
    fun podfetchsAdminIsToldToUseAnotherAccount() {
        val podfetch = Podfetch().kept()
        expectMessage(AppR.string.error_podfetch_admin) { model.signIn(podfetch.fake.url, "admin", "right", "", kind = ServerKinds.GPODDER) }
    }

    @Test
    fun aWrongGPodderPasswordIsRefused() {
        val podfetch = Podfetch().kept()
        expectMessage(R.string.error_rejected) { model.signIn(podfetch.fake.url, "alex", "wrong", "") }
    }

    @Test
    fun librariesHideAndRemovingForgetsWhatWasKept() = runBlocking {
        val abs = audiobookshelf(podcastLibraries).kept()
        model.signIn(abs.url, "alex", "right", "")
        val id = harness.store.loadAll().single().id
        // The extras are saved in the background; each step waits until they are.
        model.setLibraryHidden(id, "lib2", hidden = true)
        eventually { model.state.value.extras(id).hiddenLibraries == setOf("lib2") }
        assertEquals(setOf("lib2"), harness.preferences.serverExtras.first()[id]!!.hiddenLibraries)
        model.setLibraryHidden(id, "lib2", hidden = false)
        eventually { model.state.value.extras(id).hiddenLibraries.isEmpty() }
        model.noteSynced(id, 1_000)
        eventually { model.state.value.extras(id).lastSyncedAtMs == 1_000L }
        model.remove(id)
        eventually { !model.state.value.extras.containsKey(id) }
        assertNull(harness.preferences.serverExtras.first()[id])
    }

    @Test
    fun theServerPageSyncsNowSaysWhyItFailedAndSwitchesLibraries() = runBlocking {
        val abs = audiobookshelf(podcastLibraries).kept()
        model.signIn(abs.url, "alex", "right", "")
        val id = harness.store.loadAll().single().id
        var asked = 0
        val vm = ServerDetailViewModel(id, model, ServerDetector(harness.http, "test", harness.log), harness.log, isOffline = { true }, sync = {
            asked++
            throw gpodderProblem(audio.own.kit.gpodder.GPodderException.Http(409))
        })
        eventually { vm.state.value.version == "2.37.1" }
        vm.syncNow()
        eventually { vm.state.value.error != null && !vm.state.value.syncing }
        assertEquals(1, asked)
        assertEquals(AppR.string.error_podfetch_admin, vm.state.value.error!!.id)
        vm.setLibraryShown("lib1", false)
        eventually { model.state.value.extras(id).hiddenLibraries == setOf("lib1") }
    }
}
