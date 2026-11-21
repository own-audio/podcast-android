package audio.own.podcast.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UpNextTest {
    private fun entry(id: String) = UpNext.Entry(id, "phone", Episode(id, "s", "Episode $id"), null)

    private fun queue() = UpNext(InMemoryValueStore())

    private suspend fun UpNext.ids() = all().map { it.id }

    @Test
    fun `Play Next goes first, Play Later last - nothing is queued twice`() = runTest {
        val up = queue()
        up.playLater(entry("a"))
        up.playLater(entry("b"))
        up.playNext(entry("c"))
        up.playLater(entry("a"))
        assertEquals(listOf("c", "a", "b"), up.ids())
        up.playNext(entry("b"))
        assertEquals(listOf("b", "c", "a"), up.ids())
    }

    @Test
    fun `taking the next drops the one that just played, wherever it was`() = runTest {
        val up = queue()
        for (id in listOf("a", "b", "c")) up.playLater(entry(id))
        assertEquals("a", up.takeNext("b")?.id)
        assertEquals(listOf("c"), up.ids())
        assertEquals("c", up.takeNext(null)?.id)
        assertNull(up.takeNext(null))
    }

    @Test
    fun `reorder and remove, as a list's edit mode does`() = runTest {
        val up = queue()
        for (id in listOf("a", "b", "c", "d")) up.playLater(entry(id))
        up.move(setOf(3), 0)
        assertEquals(listOf("d", "a", "b", "c"), up.ids())
        up.move(setOf(0), 4)
        assertEquals(listOf("a", "b", "c", "d"), up.ids())
        up.remove("b")
        up.removeAll { it.id == "d" }
        assertEquals(listOf("a", "c"), up.ids())
        up.clear()
        assertTrue(up.all().isEmpty())
    }

    @Test
    fun `it survives a restart`() = runTest {
        val box = InMemoryValueStore.Box<List<UpNext.Entry>>()
        UpNext(InMemoryValueStore(box)).playLater(entry("x"))
        assertEquals(listOf("x"), UpNext(InMemoryValueStore(box)).ids())
    }
}

class PlayOnTest {
    private fun sourced(id: String, firstSeen: Long = 0, show: String = "s") =
        SourcedEpisode(SourceRef.Phone, Episode(id, show, "Episode $id", firstSeenAt = Instant.ofEpochSecond(firstSeen)))

    private fun entry(item: SourcedEpisode, source: String = item.source.id) = UpNext.Entry(item.id, source, item.episode, null)

    @Test
    fun `the next playable entry of Up Next, the unplayable ones taken out on the way`() = runTest {
        val up = UpNext(InMemoryValueStore())
        val (a, b, c) = listOf(sourced("a"), sourced("b"), sourced("c"))
        up.playLater(entry(a))
        up.playLater(entry(b, source = "a-removed-server"))
        up.playLater(entry(c))
        up.playLater(entry(sourced("d")))
        val next = PlayOn.next(a.id, up, emptyList(), continueWithNewEpisodes = true, canPlay = { it.id != b.id }, isPlayed = { false })
        assertEquals(c.id, next?.episode?.id)
        assertEquals(listOf(sourced("d").id), up.all().map { it.id })
    }

    @Test
    fun `when Up Next runs out the newest new episode plays on, if the listener wants it`() = runTest {
        val up = UpNext(InMemoryValueStore())
        val inbox = listOf(sourced("new"), sourced("heard"), sourced("offline"), sourced("old"))
        val canPlay = { it: SourcedEpisode -> it.episode.id != "offline" }
        val isPlayed = { it: SourcedEpisode -> it.episode.id == "heard" }
        assertEquals("new", PlayOn.next(sourced("x").id, up, inbox, true, canPlay, isPlayed)?.episode?.episode?.id)
        assertEquals("not the one that just ended", "old", PlayOn.next(inbox[0].id, up, inbox.filter { it.episode.id != "heard" && it.episode.id != "offline" }, true, canPlay, isPlayed)?.episode?.episode?.id)
        assertNull("Play New Episodes After Up Next is off", PlayOn.next(sourced("x").id, up, inbox, false, canPlay, isPlayed))
    }
}

class AutoQueueTest {
    private fun sourced(id: String, firstSeen: Long, show: String) =
        SourcedEpisode(SourceRef.Phone, Episode(id, show, "Episode $id", firstSeenAt = Instant.ofEpochSecond(firstSeen)))

    @Test
    fun `the first look only notes the time`() {
        val inbox = listOf(sourced("e2", 200, "auto"), sourced("e1", 100, "auto"))
        val result = AutoQueue.arrivals(null, Instant.ofEpochSecond(300), inbox, setOf("phone/auto"))
        assertEquals(Instant.ofEpochSecond(300), result.lookedAt)
        assertTrue(result.toQueue.isEmpty())
    }

    @Test
    fun `new episodes of a show set to auto-queue join the queue, oldest first`() {
        val inbox = listOf(sourced("e3", 250, "auto"), sourced("other", 240, "manual"), sourced("e2", 220, "auto"), sourced("e1", 100, "auto"))
        val result = AutoQueue.arrivals(Instant.ofEpochSecond(200), Instant.ofEpochSecond(300), inbox, setOf("phone/auto"))
        assertEquals(listOf("e2", "e3"), result.toQueue.map { it.episode.id })
    }
}
