package audio.own.podcast.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FEED_URL = "https://feeds.example.com/show.xml"

class FeedLibraryTest {
    private fun library(world: World, store: FeedLibraryStore = InMemoryFeedLibraryStore(), queued: Collection<EpisodeKey> = emptyList()) =
        FeedLibrary(store, world.loader, now = { world.time }, queued = { queued })

    @Test
    fun `following a feed keeps its audio episodes - the inbox holds only the newest one`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 600_000), Item("e2", 2, 1_200_000), Item("e3", 3))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        assertEquals("Show", show.title)
        assertEquals("Host", show.author)
        assertEquals(FEED_URL, show.feedUrl)
        assertEquals("newest first; the post without audio is left out", listOf("e3", "e2", "e1"), library.episodes(show.id).map { it.id })
        assertEquals("a new follow brings its latest episode, not the archive", listOf("e3"), library.inbox().map { it.id })
        val again = library.follow(FEED_URL)
        assertEquals("following twice is one show", show.id, again.id)
        assertEquals(1, library.shows().size)
    }

    @Test
    fun `a refresh brings new episodes into the inbox - known ones keep when they were first seen`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1), Item("e2", 2))), etag = "\"a\"")
        val library = library(world)
        val show = library.follow(FEED_URL)
        val firstSeen = library.episode(EpisodeKey(show.id, "e1")).firstSeenAt
        assertTrue("unchanged (304) - nothing new", library.refresh().isEmpty())
        world.advance(3600)
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1), Item("e2", 2), Item("e4", 4))), etag = "\"b\"")
        assertEquals(listOf("e4"), library.refresh().map { it.id })
        assertEquals(listOf("e4", "e2"), library.inbox().map { it.id })
        assertEquals(firstSeen, library.episode(EpisodeKey(show.id, "e1")).firstSeenAt)
    }

    @Test
    fun `a feed that moved is followed to its new address - a dropped episode stays only if it is being heard`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 3_600_000), Item("e2", 2, 3_600_000), Item("e3", 3, 3_600_000))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        library.record(600_000, EpisodeKey(show.id, "e1"))
        world.advance(60)
        world.set(FEED_URL, feed(moved = "https://new.example.com/show.xml", items = listOf(Item("e3", 3, 3_600_000))))
        library.refresh()
        assertEquals("https://new.example.com/show.xml", library.shows().first().feedUrl)
        assertEquals("e1 is half heard and stays; e2 goes", setOf("e3", "e1"), library.episodes(show.id).map { it.id }.toSet())
    }

    @Test
    fun `a dropped episode waiting in Up Next stays`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1), Item("e2", 2))))
        val store = InMemoryFeedLibraryStore()
        val show = library(world, store).follow(FEED_URL)
        world.set(FEED_URL, feed(items = listOf(Item("e2", 2))))
        val library = library(world, store, queued = listOf(EpisodeKey(show.id, "e1")))
        library.refresh()
        assertEquals(listOf("e2", "e1"), library.episodes(show.id).map { it.id })
    }

    @Test
    fun `a failing feed is left as it was and does not stop the others`() = runTest {
        val world = World()
        val other = "https://feeds.example.com/other.xml"
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1))))
        world.set(other, feed(title = "Other", items = listOf(Item("o1", 1))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        library.follow(other)
        world.advance(60)
        world.setPage(FEED_URL)
        world.set(other, feed(title = "Other", items = listOf(Item("o1", 1), Item("o2", 2))))
        assertEquals(listOf("o2"), library.refresh().map { it.id })
        assertEquals(listOf("e1"), library.episodes(show.id).map { it.id })
    }

    @Test
    fun `positions - the end counts as heard, a stale report cannot move back, played leaves the inbox`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 600_000))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        val key = EpisodeKey(show.id, "e1")
        library.record(120_000, key)
        assertEquals(false, library.progress(key)?.played)
        val stale = ListeningProgress(5_000, played = false, updatedAt = world.time.minusSeconds(60))
        assertEquals("an older report loses", 120_000L, library.saveProgress(stale, key).positionMs)
        world.advance(10)
        library.record(580_000, key)
        assertEquals("20 s before the end is heard", true, library.progress(key)?.played)
        assertTrue(library.inbox().isEmpty())
        library.markPlayed(key, false)
        assertEquals(listOf("e1"), library.inbox().map { it.id })
        assertEquals(0L, library.progress(key)?.positionMs)
    }

    @Test
    fun `everything is stored - a new library over the same store has the shows, positions and dismissals`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 600_000), Item("e2", 2, 600_000))))
        val store = InMemoryFeedLibraryStore()
        val library = library(world, store)
        val show = library.follow(FEED_URL)
        val key = { id: String -> EpisodeKey(show.id, id) }
        library.record(42_000, key("e1"))
        library.dismissFromInbox(listOf(key("e2")))
        library.updateSettings(ShowSettings(skipIntroSecs = 30.0, speed = 1.5f), show.id)
        val reopened = library(world, store)
        assertEquals(listOf(show.id), reopened.shows().map { it.id })
        assertEquals(ShowSettings(skipIntroSecs = 30.0, speed = 1.5f), reopened.shows().first().settings)
        assertEquals(42_000L, reopened.progress(key("e1"))?.positionMs)
        assertTrue("e2 was dismissed", reopened.inbox().isEmpty())
        reopened.unfollow(show.id)
        assertTrue(reopened.shows().isEmpty())
        assertNull(reopened.progress(key("e1")))
    }

    @Test
    fun `a page that is not a feed is refused as such`() = runTest {
        val world = World()
        world.setPage("https://feeds.example.com/page")
        val library = library(world)
        val notAFeed = runCatching { library.follow("https://feeds.example.com/page") }.exceptionOrNull()
        assertTrue(notAFeed is SourceException.NotAFeed)
        val missing = runCatching { library.follow("https://feeds.example.com/missing") }.exceptionOrNull()
        assertTrue(missing is SourceException.Network)
    }

    @Test
    fun `a serial show lists oldest first and brings nothing old into the inbox`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(serial = true, items = listOf(Item("e1", 1), Item("e2", 2))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        assertEquals(listOf("e1", "e2"), library.episodes(show.id).map { it.id })
        assertTrue(library.inbox().isEmpty())
    }

    @Test
    fun `a show that doesn't want the inbox is not in it`() = runTest {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        library.updateSettings(ShowSettings(inInbox = false), show.id)
        assertTrue(library.inbox().isEmpty())
        assertTrue(runCatching { library.episodes("nobody") }.exceptionOrNull() is SourceException.NotFound)
        assertFalse(library.capabilities.contains(Capability.SEARCH))
    }
}

class EpisodeKeyTest {
    @Test
    fun `a key round-trips through its text, ids with bars included`() {
        val key = EpisodeKey("S1", "https://x.example/a|b")
        assertEquals(key, EpisodeKey.parse(key.toString()))
        assertNull(EpisodeKey.parse("no-bar"))
    }

    @Test
    fun `the end of an episode counts as heard`() {
        for ((position, heard) in listOf(570_001L to true, 590_000L to true, 500_000L to false, 0L to false)) {
            assertEquals("$position", heard, ListeningProgress.reachedEnd(position, 600_000))
            assertFalse(ListeningProgress.reachedEnd(position, null))
        }
        // 98 % of a long episode is more than 30 s before its end.
        assertTrue(ListeningProgress.reachedEnd(3_528_000, 3_600_000))
    }

    @Test
    fun `sourced ids name the source, the show and the episode`() {
        val server = SourceRef.Server(java.util.UUID.fromString("6f1c2a3b-0000-4000-8000-000000000001"))
        val library = SourceRef.Library(server.uuid, "lib-1")
        for (source in listOf(SourceRef.Phone, server, library)) {
            assertEquals(source, SourceRef.parse(source.id))
            val id = SourcedEpisode.id(source, EpisodeKey("show", "https://x.example/a|b/c"))
            assertEquals(source to EpisodeKey("show", "https://x.example/a|b/c"), SourcedEpisode.parse(id))
        }
        assertEquals("phone/show|ep", SourcedEpisode.id(SourceRef.Phone, EpisodeKey("show", "ep")))
        assertNull(SourceRef.parse("not-a-uuid"))
        assertNull(SourcedEpisode.parse("phone"))
    }
}
