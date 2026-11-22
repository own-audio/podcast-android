package audio.own.podcastplayer.playback

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.Episode
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceException
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.downloads.DownloadedEpisode
import audio.own.podcastplayer.downloads.DownloadsState
import audio.own.podcastplayer.downloads.EpisodeDownload
import audio.own.podcastplayer.podcasts.PodcastState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoLibraryTest {
    private val show = Show(id = "s1", title = "Český rozhlas", author = "ČRo", imageUrl = "https://img.example/s1.jpg", followedAt = Instant.EPOCH)
    private val other = Show(id = "s2", title = "Hacker Public Radio", imageUrl = "https://img.example/s2.jpg", followedAt = Instant.EPOCH)

    private fun episode(id: String, day: Int, showId: String = "s1", image: String? = null) =
        SourcedEpisode(SourceRef.Phone, Episode(id = id, showId = showId, title = "Episode $id", publishedAt = Instant.parse("2026-11-%02dT08:00:00Z".format(day)), durationMs = 600_000, imageUrl = image))

    private class Library(
        override var state: PodcastState,
        override var player: audio.own.podcastplayer.playback.PodcastPlayer.State = audio.own.podcastplayer.playback.PodcastPlayer.State(),
        var playable: (SourcedEpisode) -> Boolean = { true },
        var episodes: List<SourcedEpisode> = emptyList(),
        var fails: Boolean = false,
    ) : AutoLibrary.Library {
        override fun canPlay(item: SourcedEpisode) = playable(item)

        override suspend fun episodes(show: SourcedShow): List<SourcedEpisode> = if (fails) throw SourceException.Network() else episodes
    }

    private object Words : AutoLibrary.Words {
        override fun title(entry: AutoContent.RootEntry) = entry.name
        override val chapters = "Chapters"
        override val followOnPhone = "Follow a show on your phone"
        override val noNewEpisodes = "No new episodes"
        override val nothingDownloaded = "Nothing downloaded"
        override val nothingHere = "Nothing here yet"
        override val noShows = "No shows yet"
        override val noChapters = "No chapters"
        override fun chapter(number: Int) = "Chapter $number"
        override fun failure(error: Throwable) = "Can't reach"
    }

    private fun titles(items: List<androidx.media3.common.MediaItem>) = items.map { it.mediaMetadata.title.toString() }

    @Test
    fun `with nothing followed the root says to follow a show on the phone`() = runBlocking {
        val tree = AutoLibrary(Library(PodcastState(loaded = true)), Words)
        val root = tree.children(AutoContent.ROOT)
        assertEquals(listOf("Follow a show on your phone"), titles(root))
        assertFalse(root.single().mediaMetadata.isPlayable == true)
        assertFalse(root.single().mediaMetadata.isBrowsable == true)
    }

    @Test
    fun `the root is New Episodes, Downloads and Shows, with Chapters while an episode with chapters is loaded`() = runBlocking {
        val library = Library(PodcastState(loaded = true, shows = listOf(SourcedShow(SourceRef.Phone, show))))
        val tree = AutoLibrary(library, Words)
        assertEquals(listOf(AutoContent.NEW_EPISODES, AutoContent.DOWNLOADS, AutoContent.SHOWS), tree.children(AutoContent.ROOT).map { it.mediaId })
        assertTrue(tree.children(AutoContent.ROOT).all { it.mediaMetadata.isBrowsable == true })
        assertEquals(
            "shows are a grid",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            tree.item(AutoContent.SHOWS)!!.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
        library.player = PodcastPlayer.State(item = episode("e1", 1), chapters = listOf(SpokenChapter("Intro", 0, 60_000), SpokenChapter("", 60_000, 600_000)), positionMs = 90_000)
        assertEquals(AutoContent.CHAPTERS, tree.children(AutoContent.ROOT).last().mediaId)
        val chapters = tree.children(AutoContent.CHAPTERS)
        assertEquals(listOf("Intro", "Chapter 2"), titles(chapters))
        assertEquals("${AutoContent.CHAPTER_PREFIX}1", chapters[1].mediaId)
        assertEquals(60_000L, tree.chapter(1)?.startMs)
    }

    @Test
    fun `New Episodes holds only what can play, at most 100, with artwork and progress`() = runBlocking {
        val inbox = (1..120).map { episode("e$it", 1 + it % 28) }
        val progress = mapOf(
            inbox[0].id to ListeningProgress(150_000, false, Instant.EPOCH),
            inbox[1].id to ListeningProgress(600_000, true, Instant.EPOCH),
        )
        val library = Library(PodcastState(loaded = true, shows = listOf(SourcedShow(SourceRef.Phone, show)), inbox = inbox, progress = progress))
        val tree = AutoLibrary(library, Words)
        val all = tree.children(AutoContent.NEW_EPISODES)
        assertEquals(AutoContent.MAX_ITEMS, all.size)
        val first = all[0]
        assertTrue(first.mediaMetadata.isPlayable == true)
        assertEquals("Český rozhlas", first.mediaMetadata.artist.toString())
        assertEquals("the show's picture when the episode has none", "https://img.example/s1.jpg", first.mediaMetadata.artworkUri.toString())
        val extras = first.mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
        assertEquals(0.25, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, all[1].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))

        library.playable = { it.episode.id == "e3" }
        assertEquals("offline, only what is on the phone", listOf(inbox[2].id), tree.children(AutoContent.NEW_EPISODES).map { it.mediaId })
        library.playable = { false }
        assertEquals(listOf("No new episodes"), titles(tree.children(AutoContent.NEW_EPISODES)))
    }

    @Test
    fun `Downloads are the finished ones, newest first`() = runBlocking {
        fun download(item: SourcedEpisode, done: Boolean, finished: Long) = EpisodeDownload(
            id = item.id,
            record = DownloadedEpisode(item.source.id, item.episode, show, false),
            status = if (done) EpisodeDownload.Status.DONE else EpisodeDownload.Status.DOWNLOADING,
            queuedAt = finished,
            finishedAt = finished.takeIf { done },
        )
        val a = episode("a", 1)
        val b = episode("b", 2)
        val c = episode("c", 3)
        val downloads = DownloadsState(listOf(download(a, true, 10), download(b, true, 30), download(c, false, 40)).associateBy { it.id }, loaded = true)
        val tree = AutoLibrary(Library(PodcastState(loaded = true, shows = listOf(SourcedShow(SourceRef.Phone, show)), downloads = downloads)), Words)
        assertEquals(listOf(b.id, a.id), tree.children(AutoContent.DOWNLOADS).map { it.mediaId })
        assertEquals("a listed download can be looked up", "Episode b", tree.item(b.id)?.mediaMetadata?.title.toString())
    }

    @Test
    fun `a show lists its playable episodes newest first, and a failure is one row`() = runBlocking {
        val shows = listOf(SourcedShow(SourceRef.Phone, show), SourcedShow(SourceRef.Phone, other))
        val library = Library(PodcastState(loaded = true, shows = shows), episodes = listOf(episode("old", 1), episode("new", 9), episode("mid", 5)))
        val tree = AutoLibrary(library, Words)
        val showRows = tree.children(AutoContent.SHOWS)
        assertEquals(listOf("Český rozhlas", "Hacker Public Radio"), titles(showRows))
        assertTrue(showRows.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        val listed = tree.children(showRows[0].mediaId)
        assertEquals(listOf("Episode new", "Episode mid", "Episode old"), titles(listed))
        assertEquals("what the tree listed plays without asking the source again", "new", tree.known(listed[0].mediaId)?.episode?.id)
        library.fails = true
        assertEquals(listOf("Can't reach"), titles(tree.children(showRows[0].mediaId)))
        assertNull(tree.item("${AutoContent.SHOW_PREFIX}phone/gone"))
    }

    @Test
    fun `search finds shows without accents, then episodes`() {
        val shows = listOf(SourcedShow(SourceRef.Phone, show), SourcedShow(SourceRef.Phone, other))
        val inbox = listOf(episode("radio-hour", 2, showId = "s2"))
        val tree = AutoLibrary(Library(PodcastState(loaded = true, shows = shows, inbox = inbox)), Words)
        assertEquals(listOf("Český rozhlas"), titles(tree.search("cesky rozhlas")))
        val hacker = tree.search("hacker public radio")
        assertEquals("Hacker Public Radio", hacker.first().mediaMetadata.title.toString())
        assertTrue("its new episode comes after the show", hacker.drop(1).any { it.mediaId == inbox[0].id })
        assertTrue(tree.search("gardening").isEmpty())
    }

    @Test
    fun `the speed button goes 1, 1_25 … 2, 0_75, 1 and a row's bar skips played and unstarted`() {
        var rate = 1.0f
        val seen = mutableListOf<Float>()
        repeat(6) {
            rate = AutoContent.nextRate(rate)
            seen += rate
        }
        assertEquals(listOf(1.25f, 1.5f, 1.75f, 2.0f, 0.75f, 1.0f), seen)
        assertEquals("a speed between steps goes to the next one up", 1.5f, AutoContent.nextRate(1.3f))
        assertNull(AutoContent.progress(ListeningProgress(0, false), 1000))
        assertNull(AutoContent.progress(ListeningProgress(500, true), 1000))
        assertNull(AutoContent.progress(ListeningProgress(500, false), null))
        assertEquals(1.0, AutoContent.progress(ListeningProgress(5000, false), 1000)!!, 0.0)
    }
}
