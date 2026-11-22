package audio.own.podcastplayer.playback

import audio.own.podcast.library.Episode
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {
    private fun show(id: String, title: String, author: String? = null) = SourcedShow(SourceRef.Phone, Show(id = id, title = title, author = author, followedAt = Instant.EPOCH))

    private fun episode(id: String, day: Int) =
        SourcedEpisode(SourceRef.Phone, Episode(id = id, showId = "s1", title = "Episode $id", publishedAt = Instant.parse("2026-11-%02dT08:00:00Z".format(day))))

    private val shows = listOf(
        show("s1", "Hacker Public Radio"),
        show("s2", "Český rozhlas Plus", "Český rozhlas"),
        show("s3", "The Daily", "The New York Times"),
        show("s4", "Radiolab"),
    )

    @Test
    fun `nothing named, continue and resume all go on listening, in English and Czech`() {
        for (query in listOf(null, "", "  ", "continue listening", "Continue listening in own.podcast", "resume my podcast", "Pokračuj v poslechu", "pusť dál můj podcast", "podcasts")) {
            assertTrue("'$query'", VoiceSearch.isContinue(query))
        }
        assertFalse(VoiceSearch.isContinue("hacker public radio"))
        assertFalse(VoiceSearch.isContinue("play the daily"))
    }

    @Test
    fun `a show is found by its name without accents, case or the verb in front`() {
        assertEquals("s1", VoiceSearch.bestShow("play hacker public radio on own podcast", shows)?.show?.id)
        assertEquals("s2", VoiceSearch.bestShow("cesky rozhlas plus", shows)?.show?.id)
        assertEquals("s2", VoiceSearch.bestShow("Přehraj Český rozhlas", shows)?.show?.id)
        assertEquals("s3", VoiceSearch.bestShow("the daily", shows)?.show?.id)
        assertEquals("the whole name beats a word of it", "s4", VoiceSearch.bestShow("radiolab", shows)?.show?.id)
        assertEquals("by its author", "s3", VoiceSearch.bestShow("new york times", shows)?.show?.id)
        assertNull(VoiceSearch.bestShow("gardeners question time", shows))
    }

    @Test
    fun `continue picks one under way in New Episodes, else the newest that can play`() {
        val inbox = listOf(episode("new", 9), episode("under", 5), episode("old", 1))
        val progress = mapOf(inbox[1].id to ListeningProgress(60_000, false))
        assertEquals("under", VoiceSearch.continueCandidate(inbox, progress, { true })?.episode?.id)
        assertEquals("new", VoiceSearch.continueCandidate(inbox, emptyMap(), { true })?.episode?.id)
        assertEquals("offline: what is on the device", "old", VoiceSearch.continueCandidate(inbox, progress, { it.episode.id == "old" })?.episode?.id)
        assertNull(VoiceSearch.continueCandidate(inbox, progress, { false }))
    }

    @Test
    fun `play a show takes the episode under way, else the newest unplayed, else the newest`() {
        val episodes = listOf(episode("old", 1), episode("newest", 9), episode("mid", 5))
        assertEquals("newest", VoiceSearch.showEpisode(episodes, emptyMap(), { true })?.episode?.id)
        val under = mapOf(episodes[0].id to ListeningProgress(30_000, false))
        assertEquals("old", VoiceSearch.showEpisode(episodes, under, { true })?.episode?.id)
        val played = mapOf(episodes[1].id to ListeningProgress(0, true))
        assertEquals("mid", VoiceSearch.showEpisode(episodes, played, { true })?.episode?.id)
        val allPlayed = episodes.associate { it.id to ListeningProgress(0, true) }
        assertEquals("newest", VoiceSearch.showEpisode(episodes, allPlayed, { true })?.episode?.id)
        assertNull(VoiceSearch.showEpisode(episodes, emptyMap(), { false }))
    }

    @Test
    fun `folding keeps letters and digits only`() {
        assertEquals("cesky rozhlas 2", VoiceSearch.fold("  Český   rozhlas—2! "))
        assertEquals(100, VoiceSearch.score("Radiolab", "radiolab"))
        assertEquals(0, VoiceSearch.score("x", "radiolab"))
    }
}
