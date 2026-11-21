package audio.own.kit.audiobookshelf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A MockWebServer answering through [handler], keeping every request in order. An assertion
 * that fails inside the handler answers 500 and is thrown again when the stub is closed, so it
 * is reported instead of surfacing as a timeout.
 */
internal class Stub(private val handler: (RecordedRequest) -> MockResponse) : Closeable {
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val failures = CopyOnWriteArrayList<Throwable>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return try {
                    handler(request)
                } catch (e: Throwable) {
                    failures.add(e)
                    MockResponse.Builder().code(500).body("stub failed").build()
                }
            }
        }
        server.start()
    }

    val host: HttpUrl get() = server.url("/")

    val paths: List<String> get() = requests.map { it.url.encodedPath }

    override fun close() {
        server.close()
        failures.firstOrNull()?.let { throw it }
    }
}

internal val testOkHttp = OkHttpClient()

internal fun testHttp(sleeps: MutableList<Long>? = null, log: MutableList<String>? = null) = AudiobookshelfHttp(
    client = testOkHttp,
    userAgent = "own.audio Books/0.0.0-test (JVM)",
    rateLimit = RateLimitPolicy(sleep = { sleeps?.add(it) }),
    log = { log?.add(it) },
)

internal fun json(text: String, status: Int = 200): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(text).build()

internal fun text(text: String, status: Int = 200): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "text/html; charset=utf-8").body(text).build()

internal fun RecordedRequest.bodyJson(): JsonObject =
    Json.parseToJsonElement(body?.utf8()?.ifEmpty { "{}" } ?: "{}").jsonObject

internal fun b64url(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

/** A JWT whose `exp` is [expiresInSeconds] from now; the signature is not checked by anyone. */
internal fun jwt(expiresInSeconds: Long, id: String = "a1"): String {
    val exp = System.currentTimeMillis() / 1000 + expiresInSeconds
    val header = b64url("""{"alg":"HS256","typ":"JWT"}""")
    val payload = b64url("""{"userId":"u1","username":"booktest","jti":"$id","type":"access","iat":${exp - 3600},"exp":$exp}""")
    return "$header.$payload.sig"
}

internal fun userJson(access: String?, refresh: String? = "r1", legacy: String? = null): String {
    val a = access?.let { """"accessToken":"$it",""" } ?: ""
    val r = refresh?.let { """"refreshToken":"$it",""" } ?: ""
    val l = legacy?.let { """"token":"$it",""" } ?: ""
    return """{"user":{"id":"u1","username":"booktest","type":"user",$a$r$l"mediaProgress":[{"id":"p1","userId":"u1","libraryItemId":"li1","episodeId":null,"mediaItemId":"b1","mediaItemType":"book","duration":3780.85,"progress":0.0318,"currentTime":120.25,"isFinished":false,"hideFromContinueListening":false,"ebookLocation":null,"ebookProgress":0,"lastUpdate":1792091696595,"startedAt":1792091696595,"finishedAt":null}],"bookmarks":[{"libraryItemId":"li1","time":90,"title":"probe","createdAt":1792091696595}],"permissions":{"download":true,"update":false,"delete":false,"upload":false,"accessAllLibraries":true},"librariesAccessible":[]},"userDefaultLibraryId":"lib1","serverSettings":{"id":"s","version":"2.37.1"},"ereaderDevices":[],"Source":"docker"}"""
}

internal fun legacyTokens(token: String = "t") = TokenSession(Tokens(token, null, Tokens.NEVER))

internal class RecordingStore : TokenStore {
    val saved = CopyOnWriteArrayList<Tokens?>()
    var onSave: (Tokens?) -> Unit = {}
    override suspend fun save(tokens: Tokens?) {
        onSave(tokens)
        saved.add(tokens)
    }
}
