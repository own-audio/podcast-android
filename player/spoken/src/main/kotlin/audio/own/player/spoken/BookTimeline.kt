package audio.own.player.spoken

import kotlin.math.roundToLong

/** One file of a book as the engine plays it. */
data class SpokenPart(
    val id: String,
    /** The link or file to play. A plain string so this module stays free of Android types. */
    val uri: String,
    val durationMs: Long,
    /** An HLS playlist rather than a file (a server transcoding on the fly). */
    val isHls: Boolean = false,
    /**
     * The link is the original file and answers Range requests, so it can stream through the
     * look-ahead window; a transcode made as it goes can't.
     */
    val streamsByRange: Boolean = false,
    /** The server's content type, for when the first bytes don't tell. Only a hint. */
    val contentType: String? = null,
) {
    val isUnresolved: Boolean get() = uri.startsWith("$UNRESOLVED_SCHEME:")

    /** Whether this part can stream through the look-ahead window; HLS never can. */
    val isWindowable: Boolean get() = streamsByRange && !isHls

    companion object {
        const val UNRESOLVED_SCHEME = "spoken-unresolved"

        /**
         * A part with no copy on the phone and no link yet (a partly downloaded book started with
         * no network): the player resolves it when its turn comes.
         */
        fun unresolved(id: String, durationMs: Long) =
            SpokenPart(id = id, uri = "$UNRESOLVED_SCHEME:$id", durationMs = durationMs)
    }
}

data class SpokenChapter(val title: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Where a book position falls: the part and the offset into it. */
data class PartPosition(val index: Int, val offsetMs: Long)

/**
 * A book's files laid end to end, and the arithmetic between book time and (part, offset).
 * All times are milliseconds of book time unless named otherwise; only the player's edge
 * converts to a file and an offset, so the UI and position reports see one timeline.
 */
class BookTimeline(parts: List<SpokenPart>, chapters: List<SpokenChapter> = emptyList()) {
    val parts: List<SpokenPart> = parts.toList()
    val chapters: List<SpokenChapter> = chapters.sortedBy { it.startMs }

    /** Where each part begins. */
    val starts: List<Long>
    val durationMs: Long

    init {
        var total = 0L
        starts = this.parts.map { part -> total.also { total += part.durationMs } }
        durationMs = total
    }

    val isEmpty: Boolean get() = parts.isEmpty()
    val hasChapters: Boolean get() = chapters.isNotEmpty()

    /**
     * The part playing at [positionMs] and the offset in it. Past the end: the last part at its
     * end; before the start: the first at 0.
     */
    fun locate(positionMs: Long): PartPosition? {
        if (parts.isEmpty()) return null
        val clamped = clamp(positionMs)
        val index = starts.indexOfLast { it <= clamped }.coerceAtLeast(0)
        return PartPosition(index, clamped - starts[index])
    }

    fun position(partIndex: Int, offsetMs: Long): Long {
        if (partIndex !in parts.indices) return 0
        return clamp(starts[partIndex] + offsetMs)
    }

    fun clamp(positionMs: Long): Long = positionMs.coerceIn(0, durationMs)

    fun indexOfPart(id: String): Int? = parts.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    fun partEnd(index: Int): Long = starts[index] + parts[index].durationMs

    // Chapters

    fun chapterIndex(positionMs: Long): Int? {
        if (chapters.isEmpty()) return null
        return chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    }

    fun chapterAt(positionMs: Long): SpokenChapter? = chapterIndex(positionMs)?.let { chapters[it] }

    /** Where the next chapter starts, or null in the last one: the caller then goes to the end. */
    fun nextChapterStart(fromMs: Long): Long? = chapters.firstOrNull { it.startMs > fromMs }?.startMs

    /** Where the next-chapter button goes: the next chapter, or the end of the book in the last. */
    fun nextChapterTarget(fromMs: Long): Long = nextChapterStart(fromMs) ?: durationMs

    /**
     * The start of the current chapter, or of the one before it when the current one only just
     * began ([graceMs]), as a track button does.
     */
    fun previousChapterStart(fromMs: Long, graceMs: Long = PREVIOUS_CHAPTER_GRACE_MS): Long {
        val index = chapterIndex(fromMs) ?: return 0
        val current = chapters[index]
        if (fromMs - current.startMs <= graceMs && index > 0) return chapters[index - 1].startMs
        return current.startMs
    }

    /** Where the current chapter ends, or with no chapters, the current part. */
    fun endOfChapter(positionMs: Long): Long {
        chapterAt(positionMs)?.let { return minOf(it.endMs, durationMs) }
        val located = locate(positionMs) ?: return durationMs
        return partEnd(located.index)
    }

    // Skipping

    fun skipped(fromMs: Long, bySeconds: Double): Long = clamp(fromMs + (bySeconds * 1000).roundToLong())

    companion object {
        const val PREVIOUS_CHAPTER_GRACE_MS = 3_000L

        /**
         * With no chapters every part counts as one, so the chapter list and buttons still work.
         * [fallbackTitle] gets the zero-based index; the app passes its translated "Part N".
         */
        fun chaptersFromParts(
            parts: List<SpokenPart>,
            titles: List<String?>? = null,
            fallbackTitle: (Int) -> String = { "Part ${it + 1}" },
        ): List<SpokenChapter> {
            var start = 0L
            return parts.mapIndexed { index, part ->
                val title = titles?.getOrNull(index)?.takeIf { it.isNotBlank() } ?: fallbackTitle(index)
                SpokenChapter(title, start, start + part.durationMs).also { start += part.durationMs }
            }
        }
    }
}
