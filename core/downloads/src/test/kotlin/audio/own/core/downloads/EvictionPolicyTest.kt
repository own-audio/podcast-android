package audio.own.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS apps' `EvictionPolicyTests`. */
class EvictionPolicyTest {
    private val day = 86_400_000L
    private val now = 1_792_000_000_000L

    private val candidates = listOf(
        EvictionPolicy.Candidate("playing", 400, false, now),
        EvictionPolicy.Candidate("old", 300, false, now - 30 * day),
        EvictionPolicy.Candidate("finished", 200, true, now - 1 * day),
        EvictionPolicy.Candidate("recent", 100, false, now - 2 * day),
    )

    @Test
    fun `no cap or under the cap, nothing goes`() {
        assertTrue(EvictionPolicy(null).evictions(candidates, null).isEmpty())
        assertTrue(EvictionPolicy(1000).evictions(candidates, null).isEmpty())
    }

    @Test
    fun `finished go first, then the longest unplayed, until it fits, never the playing one`() {
        assertEquals(listOf("finished"), EvictionPolicy(900).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old"), EvictionPolicy(500).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent"), EvictionPolicy(50).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent", "playing"), EvictionPolicy(50).evictions(candidates, null))
    }

    @Test
    fun `songs - the ones not played for longest go first, the playing one stays`() {
        val songs = listOf(
            EvictionPolicy.Candidate("old", 40, false, now - 9 * day),
            EvictionPolicy.Candidate("playing", 40, false, now - 20 * day),
            EvictionPolicy.Candidate("recent", 40, false, now),
            EvictionPolicy.Candidate("middle", 40, false, now - 3 * day),
        )
        assertEquals(listOf("old", "middle"), EvictionPolicy(90).evictions(songs, "playing"))
        assertTrue(EvictionPolicy(null).evictions(songs, null).isEmpty())
    }
}
