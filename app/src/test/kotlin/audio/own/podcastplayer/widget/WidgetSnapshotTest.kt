package audio.own.podcastplayer.widget

import android.app.Application
import android.net.Uri
import audio.own.podcast.library.Episode
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetSnapshotTest {
    private val folder = Files.createTempDirectory("widget").toFile()
    private val show = Show(id = "s1", title = "A Podcast", imageUrl = "https://img.example/show.jpg", followedAt = Instant.EPOCH)

    @After
    fun clean() {
        folder.deleteRecursively()
    }

    private fun episode(id: String, image: String? = null) =
        SourcedEpisode(SourceRef.Phone, Episode(id = id, showId = "s1", title = "Episode $id", durationMs = 3_600_000, imageUrl = image))

    @Test
    fun `the episode playing, then the ones under way, then the newest`() {
        val inbox = listOf(episode("n1"), episode("n2"), episode("started"), episode("n3"))
        val progress = mapOf(inbox[2].id to ListeningProgress(900_000, false))
        val playing = episode("playing", image = "https://img.example/ep.jpg")
        val player = PodcastPlayer.State(item = playing, show = show, positionMs = 1_800_000, durationMs = 3_600_000)
        val picked = WidgetSnapshot.pick(player, inbox, progress) { show }
        assertEquals(listOf("playing", "started", "n1", "n2", "n3"), picked.map { it.entry.id.substringAfterLast('|') })
        val first = picked[0].entry
        assertEquals(0.5, first.fraction, 0.0)
        assertEquals(30 * 60_000L, first.remainingMs)
        assertEquals("A Podcast", first.show)
        assertEquals("https://img.example/ep.jpg", picked[0].coverUrl)
        assertEquals("the show's picture stands in", "https://img.example/show.jpg", picked[1].coverUrl)
        assertEquals(0.25, picked[1].entry.fraction, 0.0)
        assertEquals(0.0, picked[2].entry.fraction, 0.0)
        assertTrue(WidgetSnapshot.pick(PodcastPlayer.State(), emptyList(), emptyMap()) { null }.isEmpty())
        val many = (1..20).map { episode("m$it") }
        assertEquals(WidgetSnapshot.MAX_ENTRIES, WidgetSnapshot.pick(PodcastPlayer.State(), many, emptyMap()) { show }.size)
    }

    @Test
    fun `positions are rounded, so a playing episode doesn't rewrite the file every second`() {
        val item = episode("e")
        fun at(ms: Long) = WidgetSnapshot.pick(PodcastPlayer.State(item = item, positionMs = ms, durationMs = 3_600_000), emptyList(), emptyMap()) { show }.map { it.entry }
        assertEquals(at(600_000), at(601_000))
        assertFalse(at(600_000) == at(700_000))
    }

    @Test
    fun `the link escapes the whole id and gives it back`() {
        val id = "${UUID.randomUUID()}/show 1|https://example.com/ep?x=1#2"
        val link = WidgetSnapshot.playLink(id)
        assertEquals("ownpodcast", link.scheme)
        assertEquals("play", link.host)
        assertEquals(id, WidgetSnapshot.episodeId(link))
        assertNull(WidgetSnapshot.episodeId(Uri.parse("feed://example.com/rss")))
        assertNull(WidgetSnapshot.episodeId(Uri.parse("ownpodcast://other/x")))
    }

    @Test
    fun `the file is written only when the entries change, with their small pictures and no others`() = runBlocking {
        val store = WidgetStore(folder)
        var made = 0
        val jpeg: suspend (String) -> ByteArray? = { made++; byteArrayOf(1, 2, 3) }
        val one = WidgetSnapshot.pick(PodcastPlayer.State(), listOf(episode("a")), emptyMap()) { show }
        assertTrue(store.save(one, 1, jpeg))
        assertEquals(1, made)
        val name = store.load()!!.entries.single().cover!!
        assertTrue(store.cover(name).exists())
        assertFalse("same entries, later time: nothing written", store.save(one, 2, jpeg))
        assertEquals(1L, store.load()!!.atEpochMs)
        assertEquals("the picture is made once", 1, made)

        val none = WidgetSnapshot.pick(PodcastPlayer.State(), emptyList(), emptyMap()) { null }
        assertTrue(store.save(none, 3, jpeg))
        assertTrue(store.load()!!.entries.isEmpty())
        assertFalse("a picture no entry names goes", store.cover(name).exists())

        val unavailable = WidgetSnapshot.pick(PodcastPlayer.State(), listOf(episode("b")), emptyMap()) { show }
        assertTrue(store.save(unavailable, 4) { null })
        assertNull("no picture to be had: the entry draws the placeholder", store.load()!!.entries.single().cover)
    }
}
