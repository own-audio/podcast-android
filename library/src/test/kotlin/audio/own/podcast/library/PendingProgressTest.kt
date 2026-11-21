package audio.own.podcast.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PendingProgressTest {
    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(1_800_000_000 + seconds)

    private fun entry(id: String, positionMs: Long, seconds: Long, played: Boolean = false) =
        PendingProgress.Entry(id, ListeningProgress(positionMs, played, at(seconds)))

    @Test
    fun `one entry per episode - the newer position wins, an older one never replaces it`() = runTest {
        val pending = PendingProgress(InMemoryValueStore())
        pending.record(entry("s/a", 10_000, 10))
        pending.record(entry("s/a", 20_000, 20))
        pending.record(entry("s/a", 5_000, 5))
        assertEquals(listOf(20_000L), pending.all().map { it.progress.positionMs })
    }

    @Test
    fun `oldest first - sent removes only what was sent, not a newer position recorded since`() = runTest {
        val pending = PendingProgress(InMemoryValueStore())
        val first = entry("s/b", 1, 30)
        pending.record(first)
        pending.record(entry("s/a", 2, 10, played = true))
        assertEquals(listOf("s/a", "s/b"), pending.all().map { it.id })
        pending.record(entry("s/b", 9, 40))
        pending.sent(first)
        assertEquals(listOf(2L, 9L), pending.all().map { it.progress.positionMs })
    }

    @Test
    fun `it survives a restart, and a removed server's entries go`() = runTest {
        val box = InMemoryValueStore.Box<List<PendingProgress.Entry>>()
        PendingProgress(InMemoryValueStore(box)).record(entry("server1/x", 7, 1))
        PendingProgress(InMemoryValueStore(box)).record(entry("server2/y", 8, 2))
        val again = PendingProgress(InMemoryValueStore(box))
        assertEquals(2, again.all().size)
        again.removeAll("server1")
        assertEquals(listOf("server2/y"), again.all().map { it.id })
    }
}
