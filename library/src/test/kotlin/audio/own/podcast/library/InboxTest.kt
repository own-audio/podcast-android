package audio.own.podcast.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxTest {
    private fun episode(id: String, published: Long?, firstSeen: Long = 0, show: String = "s") =
        Episode(id, show, "Episode $id", publishedAt = published?.let(Instant::ofEpochSecond), firstSeenAt = Instant.ofEpochSecond(firstSeen))

    @Test
    fun `newest first by the publication date, else when first seen, at most the limit`() {
        val episodes = listOf(episode("a", 100), episode("b", null, firstSeen = 300), episode("c", 200))
        assertEquals(listOf("b", "c", "a"), Inbox.newestFirst(episodes).map { it.id })
        assertEquals(Inbox.LIMIT, Inbox.newestFirst((1..250).map { episode("e$it", it.toLong()) }).size)
    }

    @Test
    fun `new to the listener - first seen after the follow, or the newest at the follow`() {
        val followed = Instant.ofEpochSecond(1000)
        assertTrue(Inbox.isNew(episode("later", 1, firstSeen = 1001), followed, null))
        assertFalse(Inbox.isNew(episode("archive", 1, firstSeen = 1000), followed, null))
        assertTrue(Inbox.isNew(episode("latest", 1, firstSeen = 1000), followed, "latest"))
        val latest = episode("latest", 1, firstSeen = 1000)
        assertFalse(Inbox.isInInbox(latest, ShowSettings(), followed, "latest", ListeningProgress(0, played = true), dismissed = false))
        assertFalse(Inbox.isInInbox(latest, ShowSettings(), followed, "latest", null, dismissed = true))
        assertFalse(Inbox.isInInbox(latest, ShowSettings(inInbox = false), followed, "latest", null, dismissed = false))
        assertFalse("a show never seen has nothing new", Inbox.isInInbox(latest, ShowSettings(), null, "latest", null, dismissed = false))
        assertTrue(Inbox.isInInbox(latest, ShowSettings(), followed, "latest", ListeningProgress(1000, played = false), dismissed = false))
    }

    @Test
    fun `the latest at the follow is the newest published - none for a serial show`() {
        val episodes = listOf(episode("a", 100), episode("undated", null), episode("c", 300), episode("b", 200))
        assertEquals("c", Inbox.latestAtFollow(episodes, serial = false))
        assertNull(Inbox.latestAtFollow(episodes, serial = true))
        assertNull(Inbox.latestAtFollow(emptyList(), serial = false))
    }

    @Test
    fun `a server's episodes - first sightings are kept, the newest at first sight is in the inbox`() = runTest {
        var now = Instant.ofEpochSecond(1000)
        val store = ListeningStore(InMemoryValueStore(), now = { now })
        val seen = store.observe(listOf(episode("old", 100, show = "srv"), episode("newest", 200, show = "srv")), "srv")
        assertTrue(seen.all { it.firstSeenAt == Instant.ofEpochSecond(1000) })
        assertEquals(listOf("newest"), seen.filter { store.isInInbox(it) }.map { it.id })
        now = Instant.ofEpochSecond(2000)
        val again = store.observe(listOf(episode("old", 100, show = "srv"), episode("newest", 200, show = "srv"), episode("fresh", 300, show = "srv")), "srv")
        assertEquals(listOf(1000L, 1000L, 2000L), again.map { it.firstSeenAt.epochSecond })
        assertEquals(listOf("newest", "fresh"), again.filter { store.isInInbox(it) }.map { it.id })
        store.dismiss(listOf(EpisodeKey("srv", "fresh")))
        store.save(ListeningProgress(0, played = true, updatedAt = now), EpisodeKey("srv", "newest"))
        assertTrue(again.none { store.isInInbox(it) })
        store.setSettings(ShowSettings(speed = 1.2f), "srv")
        assertEquals(1.2f, store.settings("srv").speed)
        store.forget("srv")
        assertNull(store.firstSeen("srv"))
        assertEquals(ShowSettings(), store.settings("srv"))
        assertTrue(store.allProgress().isEmpty())
    }

    @Test
    fun `a server's positions - the newer wins`() = runTest {
        val store = ListeningStore(InMemoryValueStore())
        val key = EpisodeKey("srv", "e")
        store.save(ListeningProgress(60_000, false, Instant.ofEpochSecond(20)), key)
        val kept = store.save(ListeningProgress(5_000, false, Instant.ofEpochSecond(10)), key)
        assertEquals(60_000L, kept.positionMs)
        assertEquals(mapOf(key to kept), store.allProgress())
    }
}
