package audio.own.kit.audiobookshelf

import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Collections

/** Filters, flexible ids, mojibake and the 429 backoff. */
class WireFormatTest {
    // ---- Base64 filters

    @Test
    fun `filters are URL-safe base64 without padding`() {
        // "??>" and "???" are "Pz8+" and "Pz8/" in standard base64: the two characters that
        // would be read as a space and a path separator.
        assertEquals("Pz8-", ItemFilter.encode("??>"))
        assertEquals("Pz8_", ItemFilter.encode("???"))
        // "ab" would be "YWI=" with padding.
        assertEquals("YWI", ItemFilter.encode("ab"))
        assertEquals("narrators.${ItemFilter.encode("Jiří Ployhar")}", ItemFilter.NarratorName("Jiří Ployhar").value)
        val encoded = ItemFilter.encode("Jiří Ployhar")
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' })
        assertEquals("Jiří Ployhar", String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
        assertEquals("authors.${ItemFilter.encode("aut_1")}", ItemFilter.AuthorId("aut_1").value)
        assertEquals("progress.ZmluaXNoZWQ", ItemFilter.ByProgress(ItemFilter.ProgressState.FINISHED).value)
        assertEquals("progress.bm90LXN0YXJ0ZWQ", ItemFilter.ByProgress(ItemFilter.ProgressState.NOT_STARTED).value)
    }

    @Test
    fun `a filter arrives at the server unchanged`() = runTest {
        Stub { json("""{"results":[],"total":0,"limit":10,"page":0}""") }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), testHttp())
            client.items("lib1", filter = ItemFilter.NarratorName("??>"))
            assertTrue(stub.requests.single().target.contains("filter=narrators.Pz8-"))
        }
    }

    // ---- ino and friends

    @Test
    fun `ino decodes from a number or a string`() {
        fun ino(raw: String) = AbsJson.decodeFromString(AudioFile.serializer(), """{"ino":$raw}""").ino
        assertEquals("17088", ino("17088"))
        assertEquals("17088", ino("\"17088\""))
        assertEquals("17088", ino("17088.0"))
        assertEquals("1.5", ino("1.5"))
        assertEquals("649644248522215260", ino("649644248522215260"))
        assertNull(ino("null"))
        assertNull(AbsJson.decodeFromString(AudioFile.serializer(), "{}").ino)
    }

    @Test
    fun `a sequence or year sent as a number reads as text`() {
        val e = AbsJson.decodeFromString(SeriesEntry.serializer(), """{"id":"s","name":"S","sequence":3}""")
        assertEquals("3", e.sequence)
        val m = AbsJson.decodeFromString(BookMetadata.serializer(), """{"publishedYear":1949}""")
        assertEquals("1949", m.publishedYear)
    }

    @Test
    fun `nulls where a default exists take the default`() {
        val m = AbsJson.decodeFromString(BookMetadata.serializer(), """{"title":"T","genres":null,"explicit":null}""")
        assertEquals(emptyList<String>(), m.genres)
        assertEquals(false, m.explicit)
    }

    @Test
    fun `bookmark times in a path have no trailing zero`() {
        assertEquals("90", bookmarkTimeInPath(90.0))
        assertEquals("90.5", bookmarkTimeInPath(90.5))
        assertEquals("0", bookmarkTimeInPath(0.0))
    }

    // ---- Mojibake

    @Test
    fun `mojibake - UTF-8 read as Latin-1 is put back, clean text is untouched`() {
        assertEquals("1984 - èást 1", Mojibake.repair("1984 - Ã¨Ã¡st 1"))
        assertEquals("Příběh", Mojibake.repair("PÅ™Ã­bÄ›h"))
        assertEquals("Příběh", Mojibake.repair("Příběh"))
        assertEquals("Ender's Game", Mojibake.repair("Ender's Game"))
        // Read as Latin-1 the C1 controls survive; this is what a JSON escape like \u0099 gives.
        assertEquals("Příběh", Mojibake.repair("PÅ\u0099Ã­bÄ\u009Bh"))
        // A real "Ä" in otherwise clean text stays: it doesn't re-decode as UTF-8.
        assertEquals("Ärger im Paradies", Mojibake.repair("Ärger im Paradies"))
        assertEquals("Straße", Mojibake.repair("Straße"))
        assertNull(Mojibake.repair(null as String?))
    }

    // ---- 429

    private fun rateLimited(vararg answers: () -> mockwebserver3.MockResponse): Stub {
        val queue = answers.toMutableList()
        return Stub { synchronized(queue) { if (queue.isEmpty()) json("""{"libraries":[]}""") else queue.removeAt(0)() } }
    }

    @Test
    fun `a 429 waits what Retry-After says, then retries`() = runTest {
        val sleeps = java.util.concurrent.CopyOnWriteArrayList<Long>()
        rateLimited({ MockResponse429("3") }).use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), testHttp(sleeps))
            assertEquals(emptyList<Library>(), client.bookLibraries())
            assertEquals(listOf(3_000L), sleeps.toList())
            assertEquals(2, stub.requests.size)
        }
    }

    @Test
    fun `without Retry-After the wait doubles, and gives up after two retries`() = runTest {
        val sleeps = java.util.concurrent.CopyOnWriteArrayList<Long>()
        rateLimited({ MockResponse429() }, { MockResponse429() }, { MockResponse429() }).use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), testHttp(sleeps))
            try {
                client.bookLibraries()
                fail("expected RateLimited")
            } catch (e: AudiobookshelfError.RateLimited) {
                assertNull(e.retryAfterMillis)
            }
            assertEquals(listOf(2_000L, 4_000L), sleeps.toList())
            assertEquals(3, stub.requests.size)
        }
    }

    @Test
    fun `a server asking for minutes is not waited on`() = runTest {
        val sleeps = java.util.concurrent.CopyOnWriteArrayList<Long>()
        rateLimited({ MockResponse429("900") }).use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), testHttp(sleeps))
            try {
                client.bookLibraries()
                fail("expected RateLimited")
            } catch (e: AudiobookshelfError.RateLimited) {
                assertEquals(900_000L, e.retryAfterMillis)
            }
            assertTrue(sleeps.isEmpty())
            assertEquals(1, stub.requests.size)
        }
    }

    @Test
    fun `a 429 on a write is retried with the same body`() = runTest {
        val sleeps = mutableListOf<Long>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val queue = mutableListOf({ MockResponse429("1") })
        Stub { request ->
            bodies += request.body?.utf8() ?: ""
            synchronized(queue) { if (queue.isEmpty()) text("OK") else queue.removeAt(0)() }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), testHttp(sleeps))
            client.syncSession("s1", 10.0, 5.0, 100.0)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
            assertTrue(bodies[0].contains("\"timeListened\":5.0"))
            assertEquals(listOf(1_000L), sleeps)
        }
    }

    @Test
    fun `Retry-After as seconds or as an HTTP date`() {
        assertEquals(120_000L, AudiobookshelfHttp.retryAfterMillis(Headers.headersOf("Retry-After", "120")))
        val now = 1_700_000_000_000L
        val date = Headers.headersOf("Retry-After", "Tue, 14 Nov 2023 22:14:20 GMT")
        // 1_700_000_000 s is 22:13:20 that day.
        assertEquals(60_000L, AudiobookshelfHttp.retryAfterMillis(date, now = now))
        assertNull(AudiobookshelfHttp.retryAfterMillis(Headers.headersOf()))
        assertNull(AudiobookshelfHttp.retryAfterMillis(Headers.headersOf("Retry-After", "soon")))
    }

    // ---- Logging

    @Test
    fun `failures are logged with the path, never the query or a token`() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        Stub { text("Forbidden", 403) }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens("secret-token"), testHttp(log = log))
            try {
                client.search("lib1", "private words")
            } catch (_: AudiobookshelfError.Http) {
            }
            assertEquals(1, log.size)
            assertTrue(log.single().startsWith("GET /api/libraries/lib1/search: HTTP 403"))
            assertTrue(log.none { it.contains("private") || it.contains("secret") })
        }
    }
}
