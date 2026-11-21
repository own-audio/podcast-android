package audio.own.kit.audiobookshelf

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastsTest {
    private val http = testHttp()

    @Test
    fun `a podcast item decodes - show metadata, episodes with files, enclosures and odd numbers`() {
        val json = """
        {"id":"li1","libraryId":"lib1","addedAt":1,"updatedAt":2,"isMissing":false,"mediaType":"podcast",
         "media":{"metadata":{"title":"PÅ\u0099Ã­bÄ\u009bh","author":"Host","feedUrl":"https://f.example/rss","imageUrl":"https://f.example/a.jpg","explicit":false,"type":"serial","genres":["News"]},
          "coverPath":"/c.jpg","tags":[],"autoDownloadEpisodes":true,"maxEpisodesToKeep":5,
          "episodes":[{"id":"ep1","libraryItemId":"li1","title":"One","publishedAt":1767225600000,"duration":1683.5,"size":26963473,"guid":"g1","season":"","episode":625,"episodeType":"full",
            "enclosure":{"url":"https://f.example/1.mp3","type":"audio/mpeg","length":"26963473"},
            "audioFile":{"ino":18003,"mimeType":"audio/mpeg","duration":1683.5,"metadata":{"filename":"1.mp3","ext":".mp3","size":26963473}},"chapters":[]}]}}
        """
        val item = AbsJson.decodeFromString(PodcastItem.serializer(), json)
        assertEquals("mis-decoded titles repaired", "Příběh", item.media.metadata.title)
        assertEquals("serial", item.media.metadata.type)
        assertTrue(item.media.autoDownloadEpisodes)
        assertEquals(5, item.media.maxEpisodesToKeep)
        val episode = item.media.episodes!!.first()
        assertEquals("625", episode.episode)
        assertEquals("", episode.season)
        assertEquals("g1", episode.guid)
        assertEquals("https://f.example/1.mp3", episode.enclosure?.url)
        assertEquals("18003", episode.audioFile?.ino)
        assertEquals(1683.5, episode.duration, 0.0)
    }

    @Test
    fun `a show missing its title reads as empty, not as a failure`() {
        val item = AbsJson.decodeFromString(PodcastItem.serializer(), """{"id":"li1","libraryId":"lib1","media":{"metadata":{}}}""")
        assertEquals("", item.media.metadata.title)
        assertNull(item.media.episodes)
    }

    @Test
    fun `shows list minified, by title, page after page, libraries are only podcast ones`() = runTest {
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/libraries" -> json("""{"libraries":[{"id":"lib1","name":"Books","mediaType":"book"},{"id":"lib2","name":"Pods","mediaType":"podcast","folders":[{"id":"f1","fullPath":"/podcasts"}]}]}""")
                "/api/libraries/lib2/items" -> {
                    assertEquals("1", request.url.queryParameter("minified"))
                    assertEquals("media.metadata.title", request.url.queryParameter("sort"))
                    val page = request.url.queryParameter("page")!!.toInt()
                    json("""{"results":[{"id":"p$page","libraryId":"lib2","media":{"metadata":{"title":"Show $page"},"numEpisodes":3}}],"total":2,"limit":1,"page":$page}""")
                }
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val libraries = client.podcastLibraries()
            assertEquals(listOf("lib2"), libraries.map { it.id })
            assertEquals("/podcasts", libraries.single().folders?.single()?.fullPath)
            assertEquals(listOf("p0", "p1"), client.allPodcasts("lib2").map { it.id })
        }
    }

    @Test
    fun `episode progress, an episode play session, recent episodes and directory search use their paths`() = runTest {
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "GET" to "/api/me/progress/li1/ep1" -> text("Not Found", 404)
                "PATCH" to "/api/me/progress/li1/ep1" -> text("OK")
                "POST" to "/api/items/li1/play/ep1" ->
                    json("""{"id":"s1","libraryItemId":"li1","episodeId":"ep1","playMethod":0,"duration":10,"audioTracks":[{"contentUrl":"/api/items/li1/file/18003","ino":"18003"}]}""")
                "GET" to "/api/libraries/lib2/recent-episodes" -> {
                    assertEquals("0", request.url.queryParameter("page"))
                    json("""{"episodes":[{"id":"ep9","libraryItemId":"li1","title":"Nine","duration":5}],"total":1}""")
                }
                "GET" to "/api/search/podcast" -> {
                    assertEquals("darknet diaries", request.url.queryParameter("term"))
                    json("""[{"id":1296350485,"title":"Darknet Diaries","artistName":"Jack Rhysider","feedUrl":"https://f.example/dd","cover":"https://f.example/dd.jpg","genres":["Technology"]}]""")
                }
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            assertNull(client.episodeProgress("li1", "ep1"))
            client.updateEpisodeProgress("li1", "ep1", ProgressUpdate.of(120.0, 600.0, false))
            val device = DeviceInfo("d1", "own.audio Podcasts", "0.1.0", "Google", "Pixel 8")
            val session = client.playEpisode("li1", "ep1", device)
            assertEquals("ep1", session.episodeId)
            assertEquals("18003", session.audioTracks.single().ino)
            assertEquals(listOf("Nine"), client.recentEpisodes("lib2").map { it.title })
            assertEquals("https://f.example/dd", client.searchPodcasts("darknet diaries").single().feedUrl)
        }
    }

    @Test
    fun `subscribing names the folder path with a safe title`() = runTest {
        Stub { request ->
            assertEquals("POST" to "/api/podcasts", request.method to request.url.encodedPath)
            val b = request.bodyJson()
            assertEquals("/podcasts/ACDC Talk", b["path"]!!.jsonPrimitive.content)
            assertEquals("f1", b["folderId"]!!.jsonPrimitive.content)
            assertEquals("lib2", b["libraryId"]!!.jsonPrimitive.content)
            val media = b["media"]!!.jsonObject
            assertTrue(media["autoDownloadEpisodes"]!!.jsonPrimitive.boolean)
            assertEquals("https://f.example/rss", media["metadata"]!!.jsonObject["feedUrl"]!!.jsonPrimitive.content)
            assertEquals("AC/DC: Talk?", media["metadata"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            json("""{"id":"new","libraryId":"lib2","media":{"metadata":{"title":"AC/DC: Talk?"}}}""")
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val made = client.subscribe("https://f.example/rss", "AC/DC: Talk?", "lib2", Library.Folder("f1", "/podcasts"), autoDownload = true)
            assertEquals("new", made.id)
        }
        assertEquals("/podcasts/x", podcastPath(Library.Folder("f", "/podcasts/"), " x "))
    }
}
