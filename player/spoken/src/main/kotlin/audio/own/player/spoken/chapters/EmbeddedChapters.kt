package audio.own.player.spoken.chapters

import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.SpokenPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chapter marks read from the files themselves, for servers that know none. Works on a download
 * and, through [BlockCachingRangeReader] over HTTP ranges, on a stream.
 */
object EmbeddedChapters {
    /**
     * How long the player waits for file chapters before playing without them. The readers only
     * suspend in [ByteRangeReader.read], so a timeout around them cancels promptly.
     */
    const val TIME_LIMIT_MS = 6_000L

    /** The marks of one file, sniffed by its first bytes. Empty when it has none or won't parse. */
    suspend fun read(reader: ByteRangeReader): List<FileChapter> = try {
        val head = reader.read(0, 12)
        when {
            head.startsWith("ID3") -> Id3ChapterReader(reader).read()
            head.startsWith("fLaC") -> VorbisChapterReader.readFlac(reader)
            head.startsWith("OggS") -> VorbisChapterReader.readOgg(reader)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> Mp4ChapterReader(reader).read()
            else -> emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A file whose chapters don't parse still plays; it just has none.
        emptyList()
    }

    /**
     * The chapters of every part, moved to book time; empty when no part carries any. [open]
     * gives a reader for a part, or null to skip it. HLS parts are skipped: their segments carry
     * no chapter boxes.
     */
    suspend fun read(
        parts: List<SpokenPart>,
        open: suspend (SpokenPart) -> ByteRangeReader?,
        fallbackTitle: (Int) -> String = { "Chapter ${it + 1}" },
    ): List<SpokenChapter> {
        val out = mutableListOf<SpokenChapter>()
        var partStart = 0L
        for (part in parts) {
            val start = partStart
            partStart += part.durationMs
            if (part.isHls || part.isUnresolved) continue
            val reader = open(part) ?: continue
            val marks = read(reader)
                .filter { it.startMs >= 0 && (part.durationMs <= 0 || it.startMs < part.durationMs) }
                .sortedBy { it.startMs }
            for ((i, mark) in marks.withIndex()) {
                val partEnd = if (part.durationMs > 0) part.durationMs else Long.MAX_VALUE
                val end = (mark.endMs ?: marks.getOrNull(i + 1)?.startMs ?: part.durationMs)
                    .coerceIn(mark.startMs, partEnd)
                out += SpokenChapter(
                    title = mark.title ?: fallbackTitle(out.size),
                    startMs = start + mark.startMs,
                    endMs = start + end,
                )
            }
        }
        return out
    }

    /** [read] within [TIME_LIMIT_MS]; past it, no chapters rather than a late, partial list. */
    suspend fun readWithinLimit(
        parts: List<SpokenPart>,
        open: suspend (SpokenPart) -> ByteRangeReader?,
        fallbackTitle: (Int) -> String = { "Chapter ${it + 1}" },
    ): List<SpokenChapter> = withTimeoutOrNull(TIME_LIMIT_MS) { read(parts, open, fallbackTitle) } ?: emptyList()

    // An MP4 starts with ftyp; old QuickTime files may start straight with one of the others.
    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide")
}
