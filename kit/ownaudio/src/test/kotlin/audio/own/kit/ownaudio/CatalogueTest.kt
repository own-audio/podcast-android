package audio.own.kit.ownaudio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {

    @Test
    fun `a concert's chapters come with the track, an ordinary track has none`() = runBlocking {
        StubServer {
            if (it.url.encodedPath.endsWith("/t1")) {
                json(TRACK.dropLast(1) + ""","chapter_count":2,"chapters":[{"position":1,"title":"Intro","start_ms":0},{"position":2,"title":"Crawling","start_ms":566000}]}""")
            } else {
                json(TRACK.replace("\"t1\"", "\"t2\""))
            }
        }.use { stub ->
            val client = stub.client()
            assertEquals(listOf(TrackChapter(1, "Intro", 0), TrackChapter(2, "Crawling", 566_000)), client.trackChapters("t1"))
            assertTrue(client.trackChapters("t2").isEmpty())
            assertEquals(null, stub.requests.first().url.queryParameter("include"))
        }
    }

    @Test
    fun `concerts are the albums of kind live`() = runBlocking {
        StubServer { json("""{"items":[],"next":null}""") }.use { stub ->
            stub.client().albums(kind = "live")
            val url = stub.requests.single().url
            assertEquals("/api/v2/albums", url.encodedPath)
            assertEquals("live", url.queryParameter("kind"))
        }
    }

    @Test
    fun `edited chapters go as one PUT, snake_case`() = runBlocking {
        StubServer { json("""[{"position":1,"title":"Intro","start_ms":0}]""") }.use { stub ->
            val stored = stub.client().setTrackChapters("t1", listOf("Intro" to 0L))
            assertEquals(listOf("Intro"), stored.map { it.title })
            val sent = stub.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("/api/v2/tracks/t1/chapters", sent.url.encodedPath)
            assertEquals("""{"chapters":[{"start_ms":0,"title":"Intro"}]}""", sent.text())
        }
    }

    @Test
    fun `an album with its contributors`() = runBlocking {
        val first = OwnAudioJson.parseToJsonElement(answer("albums")).let { it as kotlinx.serialization.json.JsonObject }
        val album = (first["items"] as kotlinx.serialization.json.JsonArray)[0]
        val withIncluded = kotlinx.serialization.json.JsonObject(
            (album as kotlinx.serialization.json.JsonObject) + ("included" to first["included"]!!),
        )
        StubServer { json(withIncluded.toString()) }.use { stub ->
            val answer = stub.client().album("x")
            assertTrue(answer.record.title.isNotEmpty())
            assertTrue(answer.included!!.contributors.isNotEmpty())
            assertEquals("contributors", stub.requests.single().url.queryParameter("include"))
        }
    }

    @Test
    fun `visibility and pictures name the upload`() = runBlocking {
        StubServer {
            when (it.url.encodedPath) {
                "/api/v2/tracks/t1/visibility" -> json("""{"visibility":"family"}""")
                "/api/v2/tracks/t1/image" -> if (it.method == "PUT") json(TRACK) else noContent()
                "/api/v2/playlists/p1/image" -> json(PLAYLIST)
                else -> json("""{"id":"c1","name":"Artist","kind":"person"}""")
            }
        }.use { stub ->
            val client = stub.client()
            assertEquals("family", client.setTrackVisibility("t1", "family").visibility)
            client.setTrackImage("t1", "up1")
            client.setPlaylistImage("p1", "up2")
            client.setContributorImage("c1", "up3")
            client.deleteImage("tracks/t1")
            assertEquals(
                listOf("PUT /api/v2/tracks/t1/visibility", "PUT /api/v2/tracks/t1/image", "PUT /api/v2/playlists/p1/image", "PUT /api/v2/contributors/c1/image", "DELETE /api/v2/tracks/t1/image"),
                stub.paths(),
            )
            assertEquals("""{"upload_id":"up1"}""", stub.requests[1].text())
        }
    }

    @Test
    fun `image links get a size the server makes`() {
        val link = "https://music.example.com/api/v2/images/abc?e=1796256000&s=signature"
        assertEquals("$link&w=256", OwnAudioImages.sized(link, 200))
        assertEquals("$link&w=1024", OwnAudioImages.sized(link, 4000))
        assertEquals("https://music.example.com/api/v2/images/abc?e=1&s=x&w=64", OwnAudioImages.sized("https://music.example.com/api/v2/images/abc?e=1&s=x&w=512", 10))
    }
}
