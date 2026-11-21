package audio.own.core.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OutboxTest {
    private val db = OutboxDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val outbox = Outbox(db)

    @After
    fun tearDown() = db.close()

    private fun favourite(item: String, on: Boolean, at: Long, server: String = "srv") =
        PendingChange(serverId = server, itemId = item, op = if (on) "favourite" else "unfavourite", kind = "song", mergeKey = "favourite:$item", clientId = "$item/$at", madeAt = at)

    private fun addToPlaylist(playlist: String, song: String, at: Long) =
        PendingChange(serverId = "srv", itemId = playlist, op = "playlist_add", value = song, clientId = "$playlist/$at", madeAt = at)

    /** Answers by a rule, and remembers what it was sent. */
    private class Sender(
        val onChanges: (List<PendingChange>) -> List<Delivery> = { list -> list.map { Delivery.DONE } },
        val onPlays: (List<PendingPlay>) -> List<Delivery> = { list -> list.map { Delivery.DONE } },
        val onProgress: (List<PendingProgress>) -> List<Delivery> = { list -> list.map { Delivery.DONE } },
    ) : OutboxSender {
        val changes = ArrayList<PendingChange>()
        val plays = ArrayList<PendingPlay>()
        val progress = ArrayList<PendingProgress>()
        override suspend fun sendChanges(serverId: String, changes: List<PendingChange>) = onChanges(changes).also { this.changes += changes }
        override suspend fun sendPlays(serverId: String, plays: List<PendingPlay>) = onPlays(plays).also { this.plays += plays }
        override suspend fun sendProgress(serverId: String, entries: List<PendingProgress>) = onProgress(entries).also { progress += entries }
    }

    @Test
    fun `the latest change of an item wins, playlist additions all count`() = runBlocking {
        outbox.recordChange(favourite("a", true, 1))
        outbox.recordChange(addToPlaylist("p", "s1", 2))
        outbox.recordChange(favourite("b", true, 3))
        outbox.recordChange(favourite("a", false, 4))
        outbox.recordChange(addToPlaylist("p", "s2", 5))
        val ops = outbox.changes().map { "${it.itemId}:${it.op}:${it.value}" }
        assertEquals(listOf("p:playlist_add:s1", "b:favourite:null", "a:unfavourite:null", "p:playlist_add:s2"), ops)
        assertEquals(4, outbox.pending.value)
    }

    @Test
    fun `sent oldest first, and done ones leave`() = runBlocking {
        outbox.recordChange(favourite("a", true, 1))
        outbox.recordChange(favourite("b", true, 2))
        val sender = Sender()
        val report = outbox.flush(sender)
        assertEquals(listOf("a", "b"), sender.changes.map { it.itemId })
        assertEquals(FlushReport(sent = 2), report)
        assertTrue(outbox.changes().isEmpty())
        assertEquals(0, outbox.pending.value)
    }

    @Test
    fun `a network error keeps everything for the next flush`() = runBlocking {
        outbox.recordChange(favourite("a", true, 1))
        outbox.recordChange(favourite("b", true, 2))
        val report = outbox.flush(Sender(onChanges = { throw IOException("no network") }))
        assertEquals(FlushReport(kept = 2), report)
        assertEquals(2, outbox.changes().size)
    }

    @Test
    fun `an entry kept stops the rest of its server, others go on`() = runBlocking {
        outbox.recordChange(favourite("a", true, 1))
        outbox.recordChange(favourite("b", true, 2))
        outbox.recordChange(favourite("c", true, 3))
        outbox.recordChange(favourite("x", true, 4, server = "other"))
        val report = outbox.flush(Sender(onChanges = { list -> list.map { if (it.itemId == "b") Delivery.KEEP else Delivery.DONE } }))
        assertEquals(FlushReport(sent = 2, kept = 2), report)
        assertEquals(listOf("b", "c"), outbox.changes().map { it.itemId })
    }

    @Test
    fun `a permanent refusal is dropped so it can't block the rest`() = runBlocking {
        outbox.recordChange(favourite("gone", true, 1))
        outbox.recordChange(favourite("b", true, 2))
        val report = outbox.flush(Sender(onChanges = { list -> list.map { if (it.itemId == "gone") Delivery.DROP else Delivery.DONE } }))
        assertEquals(FlushReport(sent = 1, dropped = 1), report)
        assertTrue(outbox.changes().isEmpty())
    }

    @Test
    fun `batches of at most 500`() = runBlocking {
        repeat(1_200) { outbox.recordChange(favourite("i$it", true, it.toLong())) }
        // The limit keeps the newest thousand.
        assertEquals(Outbox.CHANGES_LIMIT, outbox.changes().size)
        assertEquals("i200", outbox.changes().first().itemId)
        val sizes = ArrayList<Int>()
        outbox.flush(Sender(onChanges = { list -> sizes += list.size; list.map { Delivery.DONE } }))
        assertEquals(listOf(500, 500), sizes)
    }

    @Test
    fun `a play recorded twice is kept once`() = runBlocking {
        val id = Outbox.playId("t1", 1_000)
        assertEquals("t1@1000", id)
        assertTrue(outbox.recordPlay(PendingPlay(id, "srv", "t1", 1_000, 200_000, 199_000)))
        assertFalse(outbox.recordPlay(PendingPlay(id, "srv", "t1", 1_000, 300_000, 299_000)))
        assertEquals(1, outbox.plays().size)
        val sender = Sender()
        outbox.flush(sender)
        assertEquals(listOf(id), sender.plays.map { it.clientId })
        assertTrue(outbox.plays().isEmpty())
    }

    @Test
    fun `positions merge per session - a newer one replaces, listened time adds up`() = runBlocking {
        outbox.recordProgress(PendingProgress("srv", "b1", "s1", "book", positionMs = 1_000, listenedMs = 1_000, at = 10))
        outbox.recordProgress(PendingProgress("srv", "b1", "s1", "book", positionMs = 5_000, listenedMs = 4_000, at = 20))
        // A late report of an older position doesn't move it back.
        outbox.recordProgress(PendingProgress("srv", "b1", "s1", "book", positionMs = 2_000, listenedMs = 500, at = 15))
        outbox.recordProgress(PendingProgress("srv", "b1", "s2", "book", positionMs = 9_000, listenedMs = 100, at = 30))
        val all = outbox.progress()
        assertEquals(2, all.size)
        val first = all.first { it.sessionId == "s1" }
        assertEquals(5_000, first.positionMs)
        assertEquals(5_500, first.listenedMs)
        assertEquals(10, first.createdAt)
        val sender = Sender()
        outbox.flush(sender)
        assertEquals(listOf("s1", "s2"), sender.progress.map { it.sessionId })
        assertTrue(outbox.progress().isEmpty())
    }

    @Test
    fun `plays go before changes, and a removed server's entries are forgotten`() = runBlocking {
        outbox.recordChange(favourite("a", true, 1, server = "gone"))
        outbox.recordPlay(PendingPlay("t@1", "gone", "t", 1, 2, 1))
        outbox.forget("gone")
        assertEquals(0, outbox.pending.value)
        val order = ArrayList<String>()
        outbox.recordChange(favourite("a", true, 1))
        outbox.recordPlay(PendingPlay("t@1", "srv", "t", 1, 2, 1))
        outbox.flush(Sender(onChanges = { order += "changes"; it.map { Delivery.DONE } }, onPlays = { order += "plays"; it.map { Delivery.DONE } }))
        assertEquals(listOf("plays", "changes"), order)
    }
}
