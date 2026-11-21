package audio.own.podcast.library

import audio.own.kit.feed.Mojibake

/**
 * An episode's chapters as the player lists them, from whichever place had them: the feed's
 * inline ones (or the server's), the feed's `podcast:chapters` document, or the file's own.
 */
object EpisodeChapters {
    data class Mark(val startMs: Long, val endMs: Long?, val title: String)

    /** Unknown length: an hour past the last start stands in until the file says. */
    const val UNKNOWN_END_MS = 3_600_000L

    /**
     * Sorted; marks at or past the end dropped; each ends at the next one's start (or its own end
     * when that is sooner); the last runs to the end of the episode.
     */
    fun closed(marks: List<Mark>, durationMs: Long): List<Chapter> {
        val known = durationMs > 0
        val sorted = marks
            .filter { it.startMs >= 0 && (!known || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        val end = if (known) durationMs else (sorted.lastOrNull()?.startMs ?: 0) + UNKNOWN_END_MS
        return sorted.mapIndexed { index, mark ->
            val next = sorted.getOrNull(index + 1)?.startMs ?: end
            val stop = minOf(mark.endMs?.takeIf { it > mark.startMs } ?: next, next)
            Chapter(startMs = mark.startMs, endMs = stop, title = Mojibake.repair(mark.title))
        }
    }

    /** The marks of an episode's own chapters, those meant for the table of contents. */
    fun marks(chapters: List<Chapter>): List<Mark> =
        chapters.filter { it.isInTableOfContents }.map { Mark(it.startMs, it.endMs, it.title) }
}
