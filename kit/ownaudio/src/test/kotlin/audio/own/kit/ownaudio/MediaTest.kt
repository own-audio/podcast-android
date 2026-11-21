package audio.own.kit.ownaudio

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {

    @Test
    fun `an upload link is asked for with the exact size, the PUT sends its headers and no token`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "POST" to "/api/v2/uploads" -> json(
                    """{"upload_id":"u1","url":"${it.requestUrl("/storage/put/u1?X-Sig=abc")}","method":"PUT","headers":{"Content-Type":"audio/mpeg","x-amz-meta-owner":"me"},"expires_at":"2026-10-16T01:00:00Z"}""",
                    201,
                )
                "PUT" to "/storage/put/u1" -> json("", 200)
                "POST" to "/api/v2/books" -> json("""{"id":"b9","title":"Imported","credits":[],"visibility":"private","owner_id":"me","source":"upload","created_at":"2026-10-16T00:00:00Z","updated_at":"2026-10-16T00:00:00Z"}""", 201)
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val client = stub.client()
            val link = client.createUpload(UploadKinds.BOOK_FILE, "01.mp3", "audio/mpeg", 3)
            assertEquals("u1", link.uploadId)
            assertEquals("""{"content_type":"audio/mpeg","filename":"01.mp3","kind":"book_file","size_bytes":3}""", stub.requests[0].text())
            client.putUpload(link, byteArrayOf(1, 2, 3))
            val put = stub.requests[1]
            assertEquals("PUT", put.method)
            assertEquals("abc", put.url.queryParameter("X-Sig"))
            assertNull("storage links carry their own signature", put.headers["Authorization"])
            assertEquals("audio/mpeg", put.headers["Content-Type"])
            assertEquals("me", put.headers["x-amz-meta-owner"])
            assertEquals(3L, put.bodySize)

            val book = client.createBook(
                NewBook(title = "Imported", author = "A; B", visibility = "private", files = listOf(NewBook.File("u1", "001 - 01.mp3"))),
                idempotencyKey = "import-1",
            )
            assertEquals("b9", book.id)
            val made = stub.requests[2]
            assertEquals("import-1", made.headers["Idempotency-Key"])
            assertTrue(made.text().contains(""""relative_path":"001 - 01.mp3""""))
        }
    }

    @Test
    fun `narration - the estimate and the job go up as multipart with the fields and the file`() = runBlocking {
        val source = File.createTempFile("book-", ".txt").apply { writeText("Chapter one. It was a dark and stormy night.") }
        try {
            StubServer {
                when (it.url.encodedPath) {
                    "/api/v2/narration/estimate" -> json("""{"estimated_char_count":44}""")
                    else -> json("""{"id":"j1","title":"Night","stages":["extracting","narrating","assembling"],"stage":"queued","error":null,"chapters":[],"download_url":null,"book_id":null,"quoted_price_cents":600,"actual_cost_cents":null,"charged_price_cents":null,"currency":"USD"}""", 201)
                }
            }.use { stub ->
                val client = stub.client()
                assertEquals(44, client.narrationEstimate(source).estimatedCharCount)
                val job = client.createNarration(source, "Night", "en", "en", "v", NarrationOutput.MULTI_FILE)
                assertEquals("j1", job.id)
                assertFalse(job.isTerminal)
                val sent = stub.requests[1]
                assertTrue(sent.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
                val body = sent.text()
                assertTrue(body.contains("name=\"voice_profile_id\"\r\nContent-Length: 1\r\n\r\nv\r\n") || body.contains("name=\"voice_profile_id\"\r\n\r\nv\r\n"))
                assertTrue(body.contains("name=\"output_mode\""))
                assertTrue(body.contains("filename=\"${source.name}\""))
                assertTrue(body.contains("Content-Type: text/plain"))
                assertTrue(body.contains("Chapter one. It was a dark and stormy night."))
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun `podcasts - follow by feed, unfollow, refresh, episodes, the directory`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "GET" to "/api/v2/podcasts" -> json(answer("podcasts"))
                "POST" to "/api/v2/podcasts" -> json(
                    OwnAudioJson.parseToJsonElement(answer("podcasts")).let { p -> (p as kotlinx.serialization.json.JsonObject)["items"]!!.let { i -> (i as kotlinx.serialization.json.JsonArray)[0].toString() } },
                    201,
                )
                "POST" to "/api/v2/podcasts/refresh", "POST" to "/api/v2/podcasts/p1/refresh" -> json("""{"queued":2}""", 202)
                "GET" to "/api/v2/podcasts/p1/episodes" -> json("""{"items":[{"id":"e1","podcast_id":"p1","guid":"g","title":"Ep","stored":false,"created_at":"2026-10-11T12:00:00Z","published_at":"2026-10-10T08:00:00Z"}]}""")
                "GET" to "/api/v2/me/podcasts/unplayed" -> json("""{"items":[{"podcast_id":"p1","unplayed":3}]}""")
                "GET" to "/api/v2/podcasts/directory/categories" -> json("""{"items":[{"category":"technology","feed_count":10,"active_count":4}]}""")
                "GET" to "/api/v2/podcasts/directory/search" -> json("""{"items":[{"title":"Show","feed_url":"https://feeds.example.com/a.xml","categories":[],"podcast_id":null}]}""")
                "GET" to "/api/v2/podcasts/directory/preview" -> json("""{"show":{"title":"Show","feed_url":"https://feeds.example.com/a.xml","categories":["tech"]},"episodes":[{"guid":"g1","title":"One"}]}""")
                else -> noContent()
            }
        }.use { stub ->
            val client = stub.client()
            assertTrue(client.podcasts().items.isNotEmpty())
            val followed = client.follow("https://feeds.example.com/a.xml", idempotencyKey = "f1")
            assertTrue(followed.feedUrl.isNotEmpty())
            assertEquals("""{"feed_url":"https://feeds.example.com/a.xml"}""", stub.requests.last().text())
            assertEquals("f1", stub.requests.last().headers["Idempotency-Key"])
            assertEquals(2L, client.refreshAllPodcasts().queued)
            assertEquals(2L, client.refresh("p1").queued)
            assertEquals("Ep", client.episodes("p1").items.single().title)
            assertEquals(3L, client.unplayed().items.single().unplayed)
            client.unfollow("p1")
            assertEquals(4L, client.directoryCategories().items.single().activeCount)
            assertEquals("Show", client.directorySearch("sh").items.single().title)
            assertEquals("One", client.directoryPreview("https://feeds.example.com/a.xml").episodes.single().title)
            assertTrue("DELETE /api/v2/podcasts/p1" in stub.paths())
        }
    }

    @Test
    fun `summaries - none yet is null, made on request, questions`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "GET" to "/api/v2/summaries/episodes/e1/summary" -> problem("not_found", 404)
                "POST" to "/api/v2/summaries/episodes/e1/summary" -> json("""{"language":"en","length":"short","text":"About X.","charged_micro":1200}""")
                "GET" to "/api/v2/summaries/episodes/e1/questions" -> json("""{"items":[{"id":"q1","question":"Why?","answer":"Because.","charged_micro":300}]}""")
                "POST" to "/api/v2/summaries/episodes/e1/ask" -> json("""{"id":"q2","question":"How?","answer":"So.","charged_micro":300}""")
                else -> noContent()
            }
        }.use { stub ->
            val client = stub.client()
            assertNull(client.summary("e1", "en", SummaryLength.SHORT))
            assertEquals("short", stub.requests.last().url.queryParameter("length"))
            assertEquals(SummaryLength.SHORT, client.summarize("e1", "en", SummaryLength.SHORT).length)
            assertEquals("""{"language":"en","length":"short"}""", stub.requests.last().text())
            assertEquals("Because.", client.questions("e1").single().answer)
            assertEquals("q2", client.ask("e1", "How?").id)
            client.clearQuestions("e1")
        }
    }

    @Test
    fun `playback info asks for a kind and an id with the capabilities`() = runBlocking {
        StubServer {
            json("""{"decision":"transcode","url":"https://music.example.com/api/v2/stream?i=x&s=y","expires_at":"2026-10-11T13:00:00Z","content_type":"audio/aac","bitrate_kbps":256,"reasons":["container"],"source":{"content_type":"audio/flac","codec":"flac","sample_rate_hz":44100}}""")
        }.use { stub ->
            val info = stub.client().playbackInfo("book_file", "f1", PlaybackCapabilities(direct = listOf("audio/mpeg")), startMs = 5000)
            assertEquals("transcode", info.decision)
            assertEquals(44100, info.source?.sampleRateHz)
            assertEquals(
                """{"capabilities":{"direct":["audio/mpeg"],"transcode":["aac","mp3"]},"id":"f1","kind":"book_file","start_ms":5000}""",
                stub.requests.single().text(),
            )
        }
    }

    @Test
    fun `bookmarks, favourites, ratings and plays`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "POST" to "/api/v2/me/bookmarks" -> json("""{"id":"b1","kind":"book","item_id":"x","position_ms":1000,"label":"here","has_audio_note":false,"created_at":"2026-10-11T12:00:00Z"}""", 201)
                "PATCH" to "/api/v2/me/bookmarks/b1" -> json("""{"id":"b1","kind":"book","item_id":"x","position_ms":2000,"label":"","has_audio_note":false,"created_at":"2026-10-11T12:00:00Z"}""")
                "GET" to "/api/v2/me/favourites" -> json(answer("favourites"))
                "POST" to "/api/v2/me/plays" -> json("""[{"client_id":"p1","status":"ok"}]""")
                else -> noContent()
            }
        }.use { stub ->
            val client = stub.client()
            client.createBookmark(ItemKind.BOOK, "x", positionMs = 1000, label = "here")
            assertEquals("""{"item_id":"x","kind":"book","label":"here","position_ms":1000}""", stub.requests.last().text())
            client.updateBookmark("b1", positionMs = 2000)
            assertEquals("""{"position_ms":2000}""", stub.requests.last().text())
            client.setFavourite(ItemKind.ALBUM, "a1", true)
            client.setFavourite(ItemKind.ALBUM, "a1", false)
            client.setRating(ItemKind.TRACK, "t1", 4)
            assertEquals("""{"rating":4}""", stub.requests.last().text())
            client.setFeedback(ItemKind.TRACK, "t1", "dislike")
            client.favourites(ItemKind.TRACK)
            assertEquals("track", stub.requests.last().url.queryParameter("kind"))
            val now = java.time.Instant.parse("2026-10-11T12:00:00Z")
            assertEquals("ok", client.reportPlays(listOf(Play("p1", ItemKind.TRACK, "t1", now, now.plusSeconds(60), 60_000))).single().status)
            assertTrue(
                stub.paths().containsAll(
                    listOf("PUT /api/v2/me/favourites/album/a1", "DELETE /api/v2/me/favourites/album/a1", "PUT /api/v2/me/ratings/track/t1", "PUT /api/v2/me/feedback/track/t1"),
                ),
            )
        }
    }

    private fun mockwebserver3.RecordedRequest.requestUrl(path: String): String =
        url.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/') + path
}
