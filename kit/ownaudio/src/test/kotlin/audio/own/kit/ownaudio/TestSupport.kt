package audio.own.kit.ownaudio

import java.time.Instant
import java.util.Collections
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/** A MockWebServer that answers with [answer] and keeps every request it saw. */
class StubServer(private val answer: (RecordedRequest) -> MockResponse) : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    fun client(
        tokens: TokenSession? = TokenSession(farTokens()),
        cache: AnswerCache? = null,
        isOffline: () -> Boolean = { false },
        http: OkHttpClient = OkHttpClient(),
    ) = OwnAudioClient(
        baseUrl = baseUrl,
        http = http,
        userAgent = USER_AGENT,
        tokens = tokens,
        acceptLanguage = "cs-CZ",
        cache = cache,
        cacheIdentity = "server-1",
        isOffline = isOffline,
    )

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    override fun close() = server.close()

    companion object {
        const val USER_AGENT = "own.audio Music/1.0 (Android 16; Test)"
    }
}

fun farTokens(access: String = "a", refresh: String = "r") = Tokens(access, refresh, Instant.now().plusSeconds(3600), "")

fun expiredTokens(access: String = "a1", refresh: String = "r1") = Tokens(access, refresh, Instant.now().minusSeconds(10), "")

fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

fun problem(code: String, status: Int, extra: String = ""): MockResponse = json(
    """{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"$extra}""",
    status,
    "application/problem+json",
)

fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

fun tokenJson(access: String = "a1", refresh: String = "r1") =
    """{"access_token":"$access","token_type":"Bearer","expires_in":3600,"refresh_token":"$refresh","scope":"library:read playback"}"""

/** A recorded answer from `src/test/resources/answers`. */
fun answer(name: String): String =
    StubServer::class.java.getResource("/answers/$name.json")?.readText() ?: error("no answer $name")

fun RecordedRequest.text(): String = body?.utf8() ?: ""

fun RecordedRequest.form(): Map<String, String> = text().split("&").filter { it.isNotEmpty() }.associate {
    val (k, v) = it.split("=", limit = 2)
    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
}

/** Keeps every save, in order. */
class RecordingStore : TokenStore {
    val saved: MutableList<Tokens?> = java.util.concurrent.CopyOnWriteArrayList()
    override suspend fun save(tokens: Tokens?) {
        saved += tokens
    }
}

class MapCache : AnswerCache {
    val map = mutableMapOf<String, ByteArray>()
    override fun get(key: String) = map[key]
    override fun put(key: String, bytes: ByteArray) {
        map[key] = bytes
    }
}

const val TRACK = """{"id":"t1","title":"One","album_id":null,"recording_id":null,"credits":[],"genre_ids":[],"disc_number":null,"track_number":null,"duration_ms":1000,"visibility":"family","owner_id":"u1","content_type":null,"size_bytes":null,"image":null,"created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""

const val PLAYLIST = """{"id":"p1","name":"Road","description":null,"visibility":"private","owner_id":"u1","track_count":1,"duration_ms":1000,"image":null,"created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""

const val ME = """{"id":"u1","email":"a@example.com","display_name":"A","family":{"id":"f1","role":"admin","can_upload":true}}"""
