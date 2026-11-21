package audio.own.kit.gpodder

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class GPodderClientTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun client(password: String = "secret", http: OkHttpClient = OkHttpClient(), path: String = "/") =
        GPodderClient(server.url(path).toString(), "jo", password, http)

    @Test
    fun `subscriptions and episode actions - paths, basic auth, since, the API's timestamps`() = runTest {
        val auth = "Basic " + Base64.getEncoder().encodeToString("jo:secret".toByteArray())
        serve { request ->
            assertEquals(auth, request.headers["Authorization"])
            assertEquals("own.audio Podcasts", request.headers["User-Agent"])
            when (request.method to request.url.encodedPath) {
                "POST" to "/api/2/auth/jo/login.json" -> status(200)
                "POST" to "/api/2/devices/jo/phone-1.json" -> status(200)
                "GET" to "/api/2/subscriptions/jo/phone-1.json" -> {
                    assertEquals("since=0", request.url.encodedQuery)
                    json("""{"add":["https://a.example/rss"],"remove":["https://b.example/rss"],"timestamp":12345}""")
                }
                "POST" to "/api/2/subscriptions/jo/phone-1.json" -> json("""{"timestamp":12346,"update_urls":[["https://c.example/rss ","https://c.example/rss"]]}""")
                "GET" to "/api/2/episodes/jo.json" -> json(
                    """{"actions":[{"podcast":"https://a.example/rss","episode":"https://a.example/1.mp3","action":"play","timestamp":"2026-10-17T08:00:00","started":0,"position":120,"total":600,"device":"laptop"}],"timestamp":12347}""",
                )
                "POST" to "/api/2/episodes/jo.json" -> json("""{"timestamp":12348}""")
                else -> status(404)
            }
        }
        val client = client()
        client.login()
        client.registerDevice(id = "phone-1", caption = "Pixel")
        val changes = client.subscriptionChanges(device = "phone-1", since = 0)
        assertEquals(SubscriptionChanges(listOf("https://a.example/rss"), listOf("https://b.example/rss"), 12345), changes)
        val uploaded = client.uploadSubscriptionChanges(device = "phone-1", add = listOf("https://c.example/rss "), remove = emptyList())
        assertEquals(12346L, uploaded.timestamp)
        assertEquals(listOf(listOf("https://c.example/rss ", "https://c.example/rss")), uploaded.updateUrls)
        val actions = client.episodeActions(since = 0)
        assertEquals(120, actions.actions.first().position)
        assertEquals(Instant.parse("2026-10-17T08:00:00Z"), actions.actions.first().timestamp)
        val sent = client.uploadEpisodeActions(
            listOf(
                EpisodeAction(
                    podcast = "https://a.example/rss", episode = "https://a.example/1.mp3", action = EpisodeAction.Kind.PLAY,
                    timestamp = Instant.parse("2026-10-17T09:30:00.750Z"), started = 0, position = 300, total = 600, device = "phone-1",
                ),
            ),
        )
        assertEquals(12348L, sent.timestamp)
        assertTrue(sent.updateUrls.isEmpty())

        val device = requests.first { it.url.encodedPath == "/api/2/devices/jo/phone-1.json" }
        assertEquals("""{"caption":"Pixel","type":"mobile"}""", device.body?.utf8())
        val body = requests.last().body?.utf8()
        assertEquals(
            "canonical JSON: keys sorted, no nulls, the API's own timestamp form",
            """[{"action":"play","device":"phone-1","episode":"https://a.example/1.mp3","podcast":"https://a.example/rss","position":300,"started":0,"timestamp":"2026-10-17T09:30:00","total":600}]""",
            body,
        )
        assertTrue(requests.all { it.headers["Cookie"] == null })
    }

    @Test
    fun `a wrong password is unauthorized`() = runTest {
        serve { status(401) }
        expect<GPodderException.Unauthorized> { client("wrong").login() }
    }

    @Test
    fun `podfetch's 403 for wrong credentials still reads as a wrong password at sign-in`() = runTest {
        serve { status(403) }
        val client = client("wrong")
        expect<GPodderException.Unauthorized> { client.login() }
        expect<GPodderException.Forbidden> { client.uploadEpisodeActions(emptyList()) }
    }

    @Test
    fun `podfetch's admin account answers 409`() = runTest {
        serve { status(409) }
        val error = expect<GPodderException.Http> { client().subscriptionChanges("phone-1", 0) }
        assertEquals(409, error.status)
    }

    @Test
    fun `account-wide subscriptions are optional - 404, 400, 405 or an undecodable answer mean unsupported`() = runTest {
        var answer = status(404)
        serve { answer }
        val client = client()
        for (code in listOf(404, 400, 405)) {
            answer = status(code)
            assertNull("$code", client.allSubscriptionChanges(since = 0))
        }
        answer = json("""["https://a.example/rss"]""")
        assertNull("a plain list (the simple API) is not the changes form", client.allSubscriptionChanges(since = 0))
        answer = json("""{"add":["https://a.example/rss"],"remove":[],"timestamp":7}""")
        assertEquals(SubscriptionChanges(listOf("https://a.example/rss"), emptyList(), 7), client.allSubscriptionChanges(since = 3))
        assertEquals("/api/2/subscriptions/jo.json", requests.last().url.encodedPath)
        assertEquals("since=3", requests.last().url.encodedQuery)
        answer = status(500)
        expect<GPodderException.Http> { client.allSubscriptionChanges(since = 0) }
    }

    @Test
    fun `odd episode actions decode - unknown kinds, fractional seconds, zones on timestamps, nulls`() = runTest {
        serve {
            json(
                """{"actions":[
                    {"podcast":"p","episode":"e1","action":"flattr","timestamp":"2026-10-17T08:00:00"},
                    {"podcast":"p","episode":"e2","action":"PLAY","timestamp":"2026-10-17T08:00:00.123Z","position":120.0,"total":null,"guid":"g"},
                    {"podcast":"p","episode":"e3","action":"new"}
                ],"timestamp":5,"extra":true}""",
            )
        }
        val actions = client().episodeActions(since = 0).actions
        assertEquals(listOf(EpisodeAction.Kind.UNKNOWN, EpisodeAction.Kind.PLAY, EpisodeAction.Kind.NEW), actions.map { it.action })
        assertEquals(120, actions[1].position)
        assertNull(actions[1].total)
        assertEquals(Instant.parse("2026-10-17T08:00:00Z"), actions[1].timestamp)
        assertNull(actions[2].timestamp)
    }

    @Test
    fun `a host under a path prefix, a name that needs escaping`() = runTest {
        serve { status(200) }
        GPodderClient(server.url("/gpodder/").toString(), "jo smith@example.org", "secret", OkHttpClient()).login()
        assertEquals("/gpodder/api/2/auth/jo%20smith@example.org/login.json", requests.single().url.encodedPath)
    }

    @Test
    fun `cookies from the injected client are never sent`() = runTest {
        serve { MockResponse.Builder().code(200).addHeader("Set-Cookie", "sessionid=abc; Path=/").build() }
        val jar = object : CookieJar {
            val cookies = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                this.cookies += cookies
            }
            override fun loadForRequest(url: HttpUrl) = cookies.toList()
        }
        val client = client(http = OkHttpClient.Builder().cookieJar(jar).build())
        client.login()
        client.login()
        assertTrue(jar.cookies.isEmpty())
        assertTrue(requests.all { it.headers["Cookie"] == null })
    }

    @Test
    fun `a network failure is a network error`() = runTest {
        val client = client()
        server.close()
        expect<GPodderException.Network> { client.login() }
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }
}
