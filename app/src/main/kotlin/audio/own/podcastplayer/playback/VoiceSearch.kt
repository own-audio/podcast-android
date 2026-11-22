package audio.own.podcastplayer.playback

import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.podcasts.PodcastState
import java.text.Normalizer
import java.util.Locale

/**
 * What the assistant, the car's search and a "play from search" intent asked for, matched to what
 * the listener follows. Ported from the iOS app's `PodcastIntents`: "continue listening" goes on
 * with the episode in the player, else the last one played, else one under way, else the newest
 * new episode; "play ‹show›" plays the show's episode under way, else its newest unplayed one.
 * Words are compared without accents or case, so "Cesky rozhlas" finds "Český rozhlas".
 */
object VoiceSearch {
    /** Lower case, accents and punctuation gone, single spaces. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return decomposed.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim().replace(SPACES, " ")
    }

    /** The bare request, without "play" in front or the app's name behind. */
    fun strip(query: String): String {
        var text = fold(query)
        for (suffix in APP_SUFFIXES) if (text.endsWith(" $suffix")) text = text.removeSuffix(" $suffix")
        for (prefix in VERBS) if (text.startsWith("$prefix ")) text = text.removePrefix("$prefix ")
        return text.trim()
    }

    /** Nothing named, or only "continue", "resume", "my podcast": go on with what was playing. */
    fun isContinue(query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val text = strip(query)
        return text.isEmpty() || text in CONTINUE_PHRASES || fold(query) in CONTINUE_PHRASES
    }

    /** How well [candidate] answers [query], 0 (not at all) to 100 (the same words). */
    fun score(query: String, candidate: String): Int {
        val q = fold(query)
        val c = fold(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        if (c.startsWith(q) || q.startsWith(c)) return 80
        if (c.contains(q)) return 70
        val words = q.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return 0
        val theirs = c.split(' ').toSet()
        val hits = words.count { word -> theirs.any { it == word || (word.length >= 4 && it.startsWith(word)) } }
        return if (hits == 0) 0 else 60 * hits / words.size
    }

    /** Followed shows that match, the best first; at least half the words must be there. */
    fun rankedShows(query: String, shows: List<SourcedShow>): List<SourcedShow> {
        val text = strip(query)
        return shows.map { it to maxOf(score(text, it.show.title), score(text, it.show.author.orEmpty()) - 10) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun bestShow(query: String, shows: List<SourcedShow>): SourcedShow? = rankedShows(query, shows).firstOrNull()

    /** Episodes whose title matches, the best first. */
    fun rankedEpisodes(query: String, episodes: List<SourcedEpisode>, state: PodcastState): List<SourcedEpisode> {
        val text = strip(query)
        return episodes.map { it to maxOf(score(text, it.episode.title), (state.show(it)?.title?.let { title -> score(text, title) } ?: 0) - 20) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * "Continue listening" when nothing is in the player and no episode was played last: one under
     * way in New Episodes, else the newest that can play.
     */
    fun continueCandidate(inbox: List<SourcedEpisode>, progress: Map<String, ListeningProgress>, canPlay: (SourcedEpisode) -> Boolean): SourcedEpisode? {
        val playable = inbox.filter(canPlay)
        return playable.firstOrNull { (progress[it.id]?.positionMs ?: 0) > 0 && progress[it.id]?.played != true } ?: playable.firstOrNull()
    }

    /** "Play ‹show›": the episode under way, else the newest unplayed that can play, else the newest. */
    fun showEpisode(episodes: List<SourcedEpisode>, progress: Map<String, ListeningProgress>, canPlay: (SourcedEpisode) -> Boolean): SourcedEpisode? {
        val byDate = episodes.sortedByDescending { it.episode.publishedAt ?: it.episode.firstSeenAt }
        val under = byDate.firstOrNull { (progress[it.id]?.positionMs ?: 0) > 0 && progress[it.id]?.played != true && canPlay(it) }
        return under ?: byDate.firstOrNull { progress[it.id]?.played != true && canPlay(it) } ?: byDate.firstOrNull { canPlay(it) }
    }

    private const val MIN_SCORE = 30
    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
    private val VERBS = listOf("play", "listen to", "put on", "prehraj", "prehrat", "pust", "pustit", "poslouchat")
    private val APP_SUFFIXES = listOf("on own podcast", "in own podcast", "on own audio podcasts", "in own audio podcasts", "v own podcast", "na own podcast", "own podcast")
    private val CONTINUE_PHRASES = setOf(
        "continue", "continue listening", "resume", "resume listening", "keep listening", "my podcast", "my podcasts", "podcast", "podcasts",
        "continue my podcast", "resume my podcast", "pokracovat", "pokracuj", "pokracuj v poslechu", "pokracovat v poslechu", "muj podcast", "podcasty",
        "pust dal muj podcast", "dal",
    )
}
