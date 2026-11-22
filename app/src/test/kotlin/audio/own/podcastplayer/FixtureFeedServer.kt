package audio.own.podcastplayer

import java.util.Collections
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer

/**
 * podcast-ios's `UITests/Fixtures/serve.py` for the JVM tests: the fixture feed of three short
 * episodes (`src/test/resources/fixtures/feed/`), their audio with byte ranges, the third one's
 * chapters document, and the misuse cases the tests need: a page that is HTML, a feed of
 * thousands of episodes, a file whose connection drops halfway. Nothing goes to the internet.
 */
class FixtureFeedServer : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    /** Episodes the feed gains, newest first, as a publisher adds them. */
    @Volatile var extraEpisodes: List<String> = emptyList()

    /** The audio files' connection drops halfway through the body. */
    @Volatile var cutDownloads = false

    /** The audio files come slowly, so a test can stop one halfway. */
    @Volatile var slowDownloads = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    val base: String get() = server.url("/").toString().trimEnd('/')
    val feedUrl: String get() = "$base/feed.xml"
    val htmlUrl: String get() = "$base/page.html"
    fun bigFeedUrl(episodes: Int) = "$base/big.xml?episodes=$episodes"
    fun audio(n: Int): ByteArray = resource("ep$n.m4a")

    fun paths(): List<String> = requests.map { it.url.encodedPath }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path == "/feed.xml" -> text(feed(), "application/rss+xml")
            path == "/chapters.json" -> text(String(resource("chapters.json")), "application/json+chapters")
            path == "/page.html" -> text("<!DOCTYPE html><html><head><title>Not a feed</title></head><body><h1>Welcome</h1></body></html>", "text/html")
            path == "/big.xml" -> text(bigFeed(request.url.queryParameter("episodes")?.toIntOrNull() ?: 5_000), "application/rss+xml")
            Regex("/ep(\\d)\\.m4a").matches(path) -> file(resource(path.drop(1)), request.headers["Range"])
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun feed(): String {
        val extra = extraEpisodes.joinToString("") { id ->
            """
    <item>
      <title>Fixture Episode $id</title>
      <guid>fixture-$id</guid>
      <pubDate>Mon, 19 Oct 2026 08:00:00 +0000</pubDate>
      <enclosure url="$base/ep1.m4a" type="audio/mp4" length="52779"/>
      <itunes:duration>12</itunes:duration>
    </item>"""
        }
        return String(resource("feed.xml")).replace("{{BASE}}", base).replace("<itunes:author>own.audio</itunes:author>", "<itunes:author>own.audio</itunes:author>$extra")
    }

    private fun bigFeed(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Five Thousand</title><itunes:author>Archive</itunes:author>""")
        // One a day back from 2026-10-16, newest first, as feeds list them.
        val newest = java.time.LocalDate.of(2026, 10, 16)
        for (i in 0 until count) {
            val day = newest.minusDays(i.toLong()).atTime(8, 0).atOffset(java.time.ZoneOffset.UTC)
            val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(day)
            append("<item><title>Archive episode ${count - i}</title><guid>archive-${count - i}</guid><pubDate>$date</pubDate>")
            append("""<enclosure url="$base/ep1.m4a" type="audio/mp4" length="52779"/><itunes:duration>12</itunes:duration></item>""")
        }
        append("</channel></rss>")
    }

    /** As serve.py answers: the whole file, or the asked range with `206` and `Content-Range`. */
    private fun file(bytes: ByteArray, range: String?): MockResponse {
        val match = range?.let { Regex("bytes=(\\d*)-(\\d*)$").find(it) }
        val builder = MockResponse.Builder().addHeader("Content-Type", "audio/mp4").addHeader("Accept-Ranges", "bytes")
        val body = if (match == null) {
            builder.code(200)
            bytes
        } else {
            val start = match.groupValues[1].toIntOrNull() ?: 0
            val end = minOf(match.groupValues[2].toIntOrNull() ?: (bytes.size - 1), bytes.size - 1)
            if (start >= bytes.size) return MockResponse.Builder().code(416).build()
            builder.code(206).addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            bytes.copyOfRange(start, end + 1)
        }
        builder.body(Buffer().write(body))
        if (cutDownloads) builder.onResponseBody(SocketEffect.ShutdownConnection)
        if (slowDownloads) builder.throttleBody(4_096, 100, TimeUnit.MILLISECONDS)
        return builder.build()
    }

    private fun text(body: String, type: String) = MockResponse.Builder().addHeader("Content-Type", "$type; charset=utf-8").body(body).build()

    override fun close() = server.close()

    companion object {
        fun resource(name: String): ByteArray =
            requireNotNull(FixtureFeedServer::class.java.getResourceAsStream("/fixtures/feed/$name")) { "no fixture $name" }.use { it.readBytes() }
    }
}
