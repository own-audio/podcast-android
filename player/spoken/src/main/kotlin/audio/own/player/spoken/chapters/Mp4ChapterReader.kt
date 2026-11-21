package audio.own.player.spoken.chapters

/**
 * Chapters of an MP4/M4B/M4A, which Media3 doesn't expose. Two places can hold them, and
 * taggers write either or both:
 *
 * - a QuickTime chapter track: a text track that the audio track names in `tref/chap`, one
 *   sample per chapter (what Apple's players read);
 * - a Nero `chpl` box in `moov/udta` (what ffmpeg and many taggers also write).
 *
 * The chapter track wins when both exist, because its samples carry real durations and titles
 * longer than `chpl`'s 255 bytes. Only `moov` and the chapter samples are read: `moov` can be at
 * either end of the file, and the audio data in between is skipped.
 */
internal class Mp4ChapterReader(private val reader: ByteRangeReader) {
    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val contentStart: Long get() = start + headerSize
        val end: Long get() = start + size
        val contentSize: Long get() = size - headerSize
    }

    private class Track(
        val id: Long,
        val chapterTrackIds: List<Long>,
        val timescale: Long,
        val stbl: Box?,
    )

    suspend fun read(): List<FileChapter> {
        val length = reader.length()
        val moov = children(0, length).firstOrNull { it.type == "moov" } ?: return emptyList()
        var movieTimescale = 0L
        var movieDuration = 0L
        val tracks = mutableListOf<Track>()
        var chpl: Box? = null
        for (box in children(moov.contentStart, moov.end)) {
            when (box.type) {
                "mvhd" -> {
                    val c = ByteCursor(content(box, limit = 256))
                    val version = c.u8()
                    c.skip(3)
                    if (version == 1) c.skip(16) else c.skip(8)
                    movieTimescale = c.u32be()
                    movieDuration = if (version == 1) c.u64be() else c.u32be()
                }
                "trak" -> tracks += track(box)
                "udta" -> chpl = children(box.contentStart, box.end).firstOrNull { it.type == "chpl" } ?: chpl
            }
        }
        val movieEndMs = if (movieTimescale > 0) movieDuration * 1000 / movieTimescale else null
        val referenced = tracks.flatMap { it.chapterTrackIds }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in referenced && it.stbl != null && it.timescale > 0 }
        if (chapterTrack != null) {
            val chapters = chapterTrackChapters(chapterTrack)
            if (chapters.isNotEmpty()) return chapters
        }
        return chpl?.let { neroChapters(it, movieEndMs) } ?: emptyList()
    }

    /** The `chpl` box alone, for tests of files that carry both. */
    suspend fun readNero(): List<FileChapter> {
        val moov = children(0, reader.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val udta = children(moov.contentStart, moov.end).firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = children(udta.contentStart, udta.end).firstOrNull { it.type == "chpl" } ?: return emptyList()
        return neroChapters(chpl, movieEndMs = null)
    }

    private suspend fun track(trak: Box): Track {
        var id = 0L
        var chapterIds = emptyList<Long>()
        var timescale = 0L
        var stbl: Box? = null
        for (box in children(trak.contentStart, trak.end)) {
            when (box.type) {
                "tkhd" -> {
                    val c = ByteCursor(content(box, limit = 256))
                    val version = c.u8()
                    c.skip(3)
                    c.skip(if (version == 1) 16 else 8)
                    id = c.u32be()
                }
                "tref" -> children(box.contentStart, box.end).firstOrNull { it.type == "chap" }?.let { chap ->
                    val c = ByteCursor(content(chap, limit = 4 * 64))
                    chapterIds = List(c.remaining / 4) { c.u32be() }
                }
                "mdia" -> for (inner in children(box.contentStart, box.end)) {
                    when (inner.type) {
                        "mdhd" -> {
                            val c = ByteCursor(content(inner, limit = 256))
                            val version = c.u8()
                            c.skip(3)
                            c.skip(if (version == 1) 16 else 8)
                            timescale = c.u32be()
                        }
                        "minf" -> stbl = children(inner.contentStart, inner.end).firstOrNull { it.type == "stbl" }
                    }
                }
            }
        }
        return Track(id, chapterIds, timescale, stbl)
    }

    private suspend fun chapterTrackChapters(track: Track): List<FileChapter> {
        val tables = children(track.stbl!!.contentStart, track.stbl.end).associateBy { it.type }

        // Sample times from stts: (count, delta) runs in the track's timescale.
        val stts = ByteCursor(content(tables["stts"] ?: return emptyList(), limit = TABLE_LIMIT))
        stts.skip(4)
        val starts = mutableListOf<Long>()
        val durations = mutableListOf<Long>()
        var time = 0L
        repeat(stts.u32be().toCount(MAX_CHAPTERS)) {
            val count = stts.u32be().toCount(MAX_CHAPTERS)
            val delta = stts.u32be()
            repeat(count) {
                if (starts.size >= MAX_CHAPTERS) malformed("too many chapter samples")
                starts += time
                durations += delta
                time += delta
            }
        }

        val stsz = ByteCursor(content(tables["stsz"] ?: return emptyList(), limit = TABLE_LIMIT))
        stsz.skip(4)
        val fixedSize = stsz.u32be()
        val sampleCount = minOf(stsz.u32be().toCount(MAX_CHAPTERS), starts.size)
        val sizes = List(sampleCount) { if (fixedSize != 0L) fixedSize else stsz.u32be() }

        val chunkOffsets = run {
            val co64 = tables["co64"]
            val box = co64 ?: tables["stco"] ?: return emptyList()
            val c = ByteCursor(content(box, limit = TABLE_LIMIT))
            c.skip(4)
            List(c.u32be().toCount(MAX_CHAPTERS)) { if (co64 != null) c.u64be() else c.u32be() }
        }

        // stsc: from chunk `first` on, each chunk holds `perChunk` samples, until the next entry.
        val stsc = ByteCursor(content(tables["stsc"] ?: return emptyList(), limit = TABLE_LIMIT))
        stsc.skip(4)
        val runs = List(stsc.u32be().toCount(MAX_CHAPTERS)) {
            val first = stsc.u32be()
            val perChunk = stsc.u32be()
            stsc.skip(4)
            first to perChunk
        }
        val offsets = ArrayList<Long>(sampleCount)
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val chunkNumber = chunkIndex + 1L
            val perChunk = runs.lastOrNull { it.first <= chunkNumber }?.second ?: break
            var offset = chunkOffset
            for (i in 0 until perChunk.toCount(MAX_CHAPTERS)) {
                if (offsets.size >= sampleCount) break
                offsets += offset
                offset += sizes[offsets.size - 1]
            }
            if (offsets.size >= sampleCount) break
        }
        if (offsets.isEmpty()) return emptyList()

        val texts = sampleTexts(offsets, sizes)
        return offsets.indices.map { i ->
            val startMs = starts[i] * 1000 / track.timescale
            val endMs = (starts[i] + durations[i]) * 1000 / track.timescale
            FileChapter(startMs, endMs.takeIf { durations[i] > 0 }, texts[i])
        }
    }

    /**
     * Each sample is a 16-bit length and the text, UTF-8 or UTF-16 with a byte-order mark. The
     * samples usually sit together, so one read covers them all when they're close.
     */
    private suspend fun sampleTexts(offsets: List<Long>, sizes: List<Long>): List<String?> {
        val low = offsets.min()
        val high = offsets.indices.maxOf { offsets[it] + minOf(sizes[it], SAMPLE_LIMIT) }
        val span = if (high - low <= SPAN_LIMIT) reader.read(low, (high - low).toInt()) else null
        return offsets.indices.map { i ->
            val size = minOf(sizes[i], SAMPLE_LIMIT).toInt()
            val bytes = span?.let {
                val from = (offsets[i] - low).toInt()
                if (from + size <= it.size) it.copyOfRange(from, from + size) else null
            } ?: reader.read(offsets[i], size)
            sampleText(bytes)
        }
    }

    private fun sampleText(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val c = ByteCursor(bytes)
        val length = minOf(c.u16be(), c.remaining)
        val text = c.bytes(length)
        val decoded = when {
            text.size >= 2 && text[0] == 0xFE.toByte() && text[1] == 0xFF.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16BE)
            text.size >= 2 && text[0] == 0xFF.toByte() && text[1] == 0xFE.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16LE)
            else -> String(text, Charsets.UTF_8)
        }
        return decoded.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * `chpl`: version, flags, four reserved bytes (version 1), a one-byte count, then for each
     * chapter a start in 100-nanosecond units, a one-byte title length and the title in UTF-8.
     */
    private suspend fun neroChapters(chpl: Box, movieEndMs: Long?): List<FileChapter> {
        val c = ByteCursor(content(chpl, limit = TABLE_LIMIT))
        val version = c.u8()
        c.skip(3)
        if (version == 1) c.skip(4)
        val count = c.u8()
        val marks = List(count) {
            val start = c.u64be() / 10_000
            val title = c.string(c.u8()).trim().takeIf { it.isNotEmpty() }
            start to title
        }.sortedBy { it.first }
        return marks.mapIndexed { i, (start, title) ->
            FileChapter(start, marks.getOrNull(i + 1)?.first ?: movieEndMs, title)
        }
    }

    /** The boxes between [start] and [end], reading only their headers. */
    private suspend fun children(start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var position = start
        while (position + 8 <= end) {
            val header = ByteCursor(reader.read(position, 16))
            if (header.remaining < 8) break
            var size = header.u32be()
            val type = header.ascii(4)
            var headerSize = 8
            when (size) {
                1L -> {
                    if (header.remaining < 8) break
                    size = header.u64be()
                    headerSize = 16
                }
                0L -> size = end - position
            }
            if (size < headerSize || position + size > end) break
            out += Box(type, position, headerSize, size)
            if (out.size > MAX_BOXES) malformed("too many boxes")
            position += size
        }
        return out
    }

    private suspend fun content(box: Box, limit: Int): ByteArray {
        if (box.contentSize > limit) malformed("${box.type} is ${box.contentSize} bytes")
        return reader.read(box.contentStart, box.contentSize.toInt())
    }

    private companion object {
        const val TABLE_LIMIT = 1 shl 20
        const val SAMPLE_LIMIT = 4096L
        const val SPAN_LIMIT = 1L shl 20
        const val MAX_BOXES = 4096
    }
}
