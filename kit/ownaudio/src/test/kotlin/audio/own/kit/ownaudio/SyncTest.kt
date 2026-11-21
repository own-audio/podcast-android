package audio.own.kit.ownaudio

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncTest {

    // region Server-sent events

    @Test
    fun `event stream - fields until a blank line, comments skipped, CRLF and CR too`() {
        val parser = EventStreamParser()
        val text = ": keep-alive\n\nevent: library\nid: 41\ndata: {\"seq\":41}\n\r\nevent: library\r\nid: 42\r\ndata: a\r\ndata: b\r\n\r\nevent: identify\rdata:x\r\r"
        val events = parser.feed(text.toByteArray())
        assertEquals(
            listOf(
                LiveEvent("library", "41", """{"seq":41}"""),
                LiveEvent("library", "42", "a\nb"),
                LiveEvent("identify", "42", "x"),
            ),
            events,
        )
    }

    @Test
    fun `event stream - an event split across reads and a data-less event`() {
        val parser = EventStreamParser()
        val bytes = "data: héllo\n\nevent: nothing\n\n".toByteArray()
        val events = bytes.toList().chunked(3).flatMap { parser.feed(it.toByteArray()) }
        assertEquals(listOf(LiveEvent("message", null, "héllo")), events)
    }

    @Test
    fun `events - library events arrive, with the bearer and Last-Event-ID, onOpen once`() = runBlocking {
        StubServer {
            json("event: library\nid: 41\ndata: {\"seq\":41}\n\n: keep-alive\n\nevent: identify\ndata: {\"run_id\":\"r1\",\"status\":\"ready\",\"groups_done\":5,\"groups_total\":5}\n\n", type = "text/event-stream")
        }.use { stub ->
            val opened = AtomicInteger()
            val events = stub.client().events(lastEventId = "40", onOpen = { opened.incrementAndGet() }).toList()
            assertEquals(listOf("library", "identify"), events.map { it.type })
            assertEquals("41", events.first().id)
            assertEquals(41, events.first().json!!.jsonObject["seq"]!!.jsonPrimitive.int)
            assertEquals("ready", IdentifyProgress.of(events[1])?.status)
            assertNull(IdentifyProgress.of(events[0]))
            assertEquals(1, opened.get())
            val sent = stub.requests.single()
            assertEquals("40", sent.headers["Last-Event-ID"])
            assertEquals("Bearer a", sent.headers["Authorization"])
            assertEquals("text/event-stream", sent.headers["Accept"])
            assertEquals(StubServer.USER_AGENT, sent.headers["User-Agent"])
        }
    }

    @Test
    fun `events - a 401 is refreshed once`() = runBlocking {
        StubServer {
            when {
                it.url.encodedPath == "/api/v2/auth/token" -> json(tokenJson("a2", "r2"))
                it.headers["Authorization"] == "Bearer a2" -> json("event: library\nid: 1\ndata: {}\n\n", type = "text/event-stream")
                else -> problem("unauthorized", 401)
            }
        }.use { stub ->
            val events = stub.client(tokens = TokenSession(farTokens("a1", "r1"))).events().toList()
            assertEquals(1, events.size)
            assertEquals(listOf("GET /api/v2/events", "POST /api/v2/auth/token", "GET /api/v2/events"), stub.paths())
        }
    }

    @Test
    fun `events - silence longer than the limit is a dead connection`() = runBlocking {
        StubServer {
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                .body("event: library\nid: 1\ndata: {}\n\n").bodyDelay(2, TimeUnit.SECONDS).build()
        }.use { stub ->
            val error = runCatching { stub.client().events(silenceMs = 300).toList() }.exceptionOrNull()
            assertTrue("$error", error is OwnAudioException.Network)
        }
    }

    @Test
    fun `events - the collector stopping cancels the stream`() = runBlocking {
        StubServer {
            // One event, then nothing for a long time: only cancelling ends the read.
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                .body("event: library\nid: 1\ndata: {}\n\n" + " ".repeat(10)).throttleBody(40, 5, TimeUnit.SECONDS).build()
        }.use { stub ->
            val event = withTimeout(4_000) { stub.client().events().first() }
            assertEquals("library", event.type)
        }
    }

    // endregion

    // region Snapshot

    private val snapshotBody = """
        {"type":"begin","cursor":"c1"}
        {"type":"record","kind":"track","id":"t1","record":$TRACK}
        {"type":"record","kind":"favourite.track","id":"t1","record":{"kind":"track","id":"t1","created_at":"2026-10-11T12:00:00Z"}}
        {"type":"future_line","x":1}

        {"type":"id","kind":"album","id":"al1"}
        {"type":"end","cursor":"c1"}
    """.trimIndent() + "\n"

    @Test
    fun `snapshot - begin, records, ids and end, unknown lines skipped`() = runBlocking {
        StubServer { json(snapshotBody, type = "application/x-ndjson") }.use { stub ->
            val lines = stub.client().snapshot(kinds = listOf("track", "favourite")).toList()
            assertEquals(5, lines.size)
            assertEquals(SnapshotLine.Begin("c1"), lines.first())
            assertEquals(SnapshotLine.End("c1"), lines.last())
            val track = lines[1] as SnapshotLine.Record
            assertEquals("One", Change(track.kind, track.id, "upsert", track.record).decode(Track.serializer())?.title)
            val favourite = lines[2] as SnapshotLine.Record
            assertEquals("t1", Change(favourite.kind, favourite.id, "upsert", favourite.record).decode(Favourite.serializer())?.id)
            assertEquals(SnapshotLine.Id("album", "al1"), lines[3])
            val sent = stub.requests.single()
            assertEquals("track,favourite", sent.url.queryParameter("kinds"))
            assertEquals("application/x-ndjson", sent.headers["Accept"])
        }
    }

    @Test
    fun `snapshot - a body without its end line is truncated, with where to resume`() = runBlocking {
        val cut = snapshotBody.lines().filter { !it.contains("\"end\"") && !it.contains("\"id\",") }.joinToString("\n")
        StubServer { json(cut, type = "application/x-ndjson") }.use { stub ->
            val seen = mutableListOf<SnapshotLine>()
            try {
                stub.client().snapshot().collect { seen += it }
                fail("expected SnapshotTruncated")
            } catch (e: OwnAudioException.SnapshotTruncated) {
                assertEquals("favourite.track:t1", e.after)
                assertEquals("c1", e.cursor)
            }
            assertEquals(3, seen.size)
        }
    }

    @Test
    fun `snapshot - resuming sends after and cursor`() = runBlocking {
        StubServer { json("""{"type":"end","cursor":"c1"}""" + "\n", type = "application/x-ndjson") }.use { stub ->
            stub.client().snapshot(after = "track:t9", cursor = "c1").toList()
            val url = stub.requests.single().url
            assertEquals("track:t9", url.queryParameter("after"))
            assertEquals("c1", url.queryParameter("cursor"))
        }
    }

    @Test
    fun `snapshot line parsing`() {
        assertEquals(SnapshotLine.Begin("c1"), SnapshotLine.parse("""{"type":"begin","cursor":"c1"}"""))
        assertNull(SnapshotLine.parse("""{"type":"record","kind":"track"}"""))
        assertNull(SnapshotLine.parse("""[1,2]"""))
    }

    // endregion

    // region Changes and push

    @Test
    fun `changes decode and offer records by kind`() = runBlocking {
        StubServer {
            json("""{"changes":[{"kind":"track","id":"t1","op":"upsert","record":$TRACK},{"kind":"album","id":"a1","op":"delete","record":null},{"kind":"translation","id":"x","op":"upsert","record":{"a":1}}],"cursor":"c2","has_more":true,"reset":false}""")
        }.use { stub ->
            val answer = stub.client().changes("c1", limit = 100)
            assertEquals("c2", answer.cursor)
            assertTrue(answer.hasMore)
            assertEquals("One", answer.changes[0].decode(Track.serializer())?.title)
            assertNull(answer.changes[1].decode(Album.serializer()))
            assertEquals("c1", stub.requests.single().url.queryParameter("cursor"))
        }
        val recorded = OwnAudioJson.decodeFromString(ChangesAnswer.serializer(), answer("changes"))
        assertFalse(recorded.reset)
    }

    @Test
    fun `offline changes go as one push, each change snake_case, answered in order`() = runBlocking {
        StubServer { json("""[{"client_id":"f1","status":"ok"},{"client_id":"p1","status":"duplicate"}]""") }.use { stub ->
            val at = Instant.ofEpochSecond(1_791_000_000)
            val results = stub.client().push(
                listOf(
                    OfflineChange.favourite(ItemKind.ALBUM, "al1", false, at, "f1"),
                    OfflineChange.play(Play("p1", ItemKind.TRACK, "t1", at, at.plusSeconds(200), 190_000)),
                ),
            )
            assertEquals(listOf("ok", "duplicate"), results.map { it.status })
            val sent = stub.requests.single()
            assertEquals("/api/v2/sync/push", sent.url.encodedPath)
            val changes = OwnAudioJson.parseToJsonElement(sent.text()).jsonObject["changes"]!!.jsonArray
            val first = changes[0].jsonObject
            assertEquals("unfavourite", first["op"]!!.jsonPrimitive.content)
            assertEquals("album", first["kind"]!!.jsonPrimitive.content)
            assertFalse("what a change doesn't have is left out", "rating" in first || "finished" in first)
            val second = changes[1].jsonObject
            assertEquals(190_000, second["listened_ms"]!!.jsonPrimitive.int)
            assertEquals("p1", second["client_id"]!!.jsonPrimitive.content)
            assertEquals("android", second["device_kind"]!!.jsonPrimitive.content)
            assertEquals("2026-10-03T04:00:00Z", second["started_at"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `stream calls ignore the injected client's call timeout`() = runBlocking {
        StubServer {
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-ndjson")
                .body("""{"type":"begin","cursor":"c"}""" + "\n" + """{"type":"end","cursor":"c"}""" + "\n").bodyDelay(700, TimeUnit.MILLISECONDS).build()
        }.use { stub ->
            val http = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            assertEquals(2, stub.client(http = http).snapshot().toList().size)
        }
    }

    // endregion
}
