package audio.own.podcast.library

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A MockWebServer answering through [handler], keeping every request in order. An assertion that
 * fails inside the handler answers 500 and is thrown again on [close], so it is reported instead
 * of surfacing as a confusing error elsewhere.
 */
class ServerStub(private val handler: (RecordedRequest) -> MockResponse) : Closeable {
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val failures = CopyOnWriteArrayList<Throwable>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return try {
                    handler(request)
                } catch (e: Throwable) {
                    failures += e
                    MockResponse.Builder().code(500).body("stub failed").build()
                }
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    /** `METHOD /path` of every request. */
    val calls: List<String> get() = requests.map { "${it.method} ${it.url.encodedPath}" }

    fun count(call: String) = calls.count { it == call }

    override fun close() {
        server.close()
        failures.firstOrNull()?.let { throw it }
    }
}

fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

fun RecordedRequest.text(): String = body?.utf8() ?: ""

/** A moving clock both a source and its store read. */
class TestClock(var time: java.time.Instant = java.time.Instant.ofEpochSecond(1_793_000_000)) {
    fun advance(seconds: Long) {
        time = time.plusSeconds(seconds)
    }
}
