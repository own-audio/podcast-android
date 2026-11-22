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

    /**
     * The last part's length isn't known yet (a feed that leaves it out): positions in it aren't
     * held to [durationMs] until the player tells the real length ([withPartDuration]).
     */
    val isOpenEnded: Boolean get() = parts.isNotEmpty() && parts.last().durationMs <= 0
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

    fun clamp(positionMs: Long): Long = positionMs.coerceIn(0, upperMs)

    private val upperMs: Long get() = if (isOpenEnded) Long.MAX_VALUE else durationMs

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
        chapterAt(positionMs)?.let { chapter ->
            if (isOpenEnded && chapter.endMs <= chapter.startMs) return Long.MAX_VALUE
            return minOf(chapter.endMs, upperMs)
        }
        val located = locate(positionMs) ?: return durationMs
        if (isOpenEnded && located.index == parts.lastIndex) return Long.MAX_VALUE
        return partEnd(located.index)
    }

    // Lengths and chapters learned after loading

    /**
     * This timeline with part [index] [durationMs] long (the player's real length rather than the
     * stated one). The chapters after the part move with its end, the chapter that ended where
     * the book did ends at the new end, and none is left past it.
     */
    fun withPartDuration(index: Int, durationMs: Long): BookTimeline {
        if (index !in parts.indices || parts[index].durationMs == durationMs) return this
        val old = parts[index].durationMs
        val oldEnd = partEnd(index)
        val newEnd = starts[index] + durationMs
        val delta = durationMs - old
        val last = index == parts.lastIndex
        val newParts = parts.toMutableList().also { it[index] = it[index].copy(durationMs = durationMs) }
        val moved = chapters.mapIndexed { i, c ->
            when {
                // A length that wasn't known says nothing about where the chapters end, but the
                // last one runs to the end of the book.
                last && old <= 0 -> if (i == chapters.lastIndex) c.copy(endMs = maxOf(newEnd, c.startMs)) else c
                !last && c.startMs >= oldEnd -> c.copy(startMs = c.startMs + delta, endMs = c.endMs + delta)
                else -> c.copy(
                    startMs = minOf(c.startMs, newEnd),
                    endMs = if (c.endMs >= oldEnd) c.endMs + delta else minOf(c.endMs, newEnd),
                )
            }
        }
        val line = BookTimeline(newParts, emptyList())
        return BookTimeline(newParts, fitted(moved, line.durationMs, line.isOpenEnded))
    }

    /** This timeline with other chapters, fitted to its length. */
    fun withChapters(chapters: List<SpokenChapter>): BookTimeline =
        BookTimeline(parts, fitted(chapters, durationMs, isOpenEnded))

    /** This timeline with part [index] played from [part] (the same file elsewhere). */
    fun withPart(index: Int, part: SpokenPart): BookTimeline {
        if (index !in parts.indices) return this
        return BookTimeline(parts.toMutableList().also { it[index] = part }, chapters)
    }

    // Skipping

    fun skipped(fromMs: Long, bySeconds: Double): Long = clamp(fromMs + (bySeconds * 1000).roundToLong())

    companion object {
        const val PREVIOUS_CHAPTER_GRACE_MS = 3_000L

        /**
         * [chapters] sorted and held to a book [totalMs] long: none starts past the end (the first
         * always stays), an end that is missing (not after the start) is the next start or the
         * book's end, and none ends past the book's. An open-ended book holds nothing to its end.
         */
        fun fitted(chapters: List<SpokenChapter>, totalMs: Long, openEnded: Boolean = false): List<SpokenChapter> {
            val sorted = chapters.sortedBy { it.startMs }
            val kept = if (openEnded) sorted else sorted.filterIndexed { i, c -> i == 0 || c.startMs < totalMs }
            return kept.mapIndexed { i, c ->
                val bookEnd = if (openEnded) c.startMs else maxOf(totalMs, c.startMs)
                var end = c.endMs
                if (end <= c.startMs) end = kept.getOrNull(i + 1)?.startMs ?: bookEnd
                if (!openEnded) end = end.coerceAtMost(bookEnd)
                if (end == c.endMs) c else c.copy(endMs = end)
            }
        }

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
