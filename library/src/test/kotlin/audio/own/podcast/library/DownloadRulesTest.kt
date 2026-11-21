package audio.own.podcast.library

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DownloadRulesTest {
    private fun item(id: String, day: Int, played: Boolean = false, downloaded: Boolean = false) =
        DownloadRules.Item(id, Instant.ofEpochSecond(1_800_000_000L + day * 86_400L), played, downloaded)

    @Test
    fun `no rules, nothing changes`() {
        val items = listOf(item("a", 3, played = true, downloaded = true), item("b", 2))
        assertEquals(DownloadRules.Plan(), DownloadRules.plan(items, ShowSettings(), null))
    }

    @Test
    fun `the newest N unplayed are downloaded, never the back catalogue`() {
        val items = listOf(item("old", 1), item("new", 5), item("mid", 3, played = true), item("got", 4, downloaded = true))
        val plan = DownloadRules.plan(items, ShowSettings(autoDownload = 3), null)
        assertEquals(listOf("new"), plan.download)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `played downloads go when Delete When Played is on, except the one playing`() {
        val items = listOf(item("a", 3, played = true, downloaded = true), item("b", 2, played = true, downloaded = true), item("c", 1, downloaded = true))
        assertEquals(listOf("a"), DownloadRules.plan(items, ShowSettings(deleteWhenPlayed = true), "b").remove)
    }

    @Test
    fun `Keep at Most keeps the newest and lets the oldest go, the playing one never`() {
        val items = (1..5).map { item("e$it", it, downloaded = true) }
        assertEquals(listOf("e1", "e2"), DownloadRules.plan(items, ShowSettings(keepAtMost = 3), null).remove)
        assertEquals(listOf("e2"), DownloadRules.plan(items, ShowSettings(keepAtMost = 3), "e1").remove)
    }

    @Test
    fun `new downloads count against Keep at Most, so the rules don't fight`() {
        val items = listOf(item("new", 5), item("d4", 4, downloaded = true), item("d3", 3, downloaded = true))
        val plan = DownloadRules.plan(items, ShowSettings(autoDownload = 1, keepAtMost = 2), null)
        assertEquals(listOf("new"), plan.download)
        assertEquals(listOf("d3"), plan.remove)
        // A limit smaller than the downloads it would ask for: only the newest come.
        val tight = DownloadRules.plan(listOf(item("x", 3), item("y", 2)), ShowSettings(autoDownload = 2, keepAtMost = 1), null)
        assertEquals(listOf("x"), tight.download)
    }

    @Test
    fun `settings saved before the download rules existed still load`() {
        val old = """{"inInbox":false,"autoQueue":false,"skipIntroSecs":30}"""
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString(ShowSettings.serializer(), old)
        assertFalse(settings.inInbox)
        assertEquals(30.0, settings.skipIntroSecs, 0.0)
        assertEquals(0, settings.autoDownload)
        assertFalse(settings.deleteWhenPlayed)
        assertNull(settings.keepAtMost)
        assertFalse(settings.hasDownloadRules)
    }
}

class EvictionPolicyTest {
    private val day = 86_400L
    private val now = Instant.ofEpochSecond(1_792_000_000)

    private val candidates = listOf(
        EvictionPolicy.Candidate("playing", 400, false, now),
        EvictionPolicy.Candidate("old", 300, false, now.minusSeconds(30 * day)),
        EvictionPolicy.Candidate("finished", 200, true, now.minusSeconds(1 * day)),
        EvictionPolicy.Candidate("recent", 100, false, now.minusSeconds(2 * day)),
    )

    @Test
    fun `no cap, nothing goes - under the cap, nothing goes`() {
        assertTrue(EvictionPolicy(null).evictions(candidates, null).isEmpty())
        assertTrue(EvictionPolicy(1000).evictions(candidates, null).isEmpty())
    }

    @Test
    fun `played episodes go first, then the longest unplayed, until it fits - the playing episode never`() {
        assertEquals(listOf("finished"), EvictionPolicy(900).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old"), EvictionPolicy(500).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent"), EvictionPolicy(50).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent", "playing"), EvictionPolicy(50).evictions(candidates, null))
    }
}
