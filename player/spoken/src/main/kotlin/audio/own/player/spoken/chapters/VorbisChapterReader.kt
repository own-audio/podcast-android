package audio.own.player.spoken.chapters

/**
 * Chapters from Vorbis comments, in FLAC and in Ogg (Vorbis, Opus): `CHAPTER001=00:01:30.250`
 * and `CHAPTER001NAME=Title`, the convention many taggers and players share. Keys are
 * case-insensitive and the number may have any width.
 */
internal object VorbisChapterReader {
    suspend fun readFlac(reader: ByteRangeReader): List<FileChapter> {
        val magic = reader.read(0, 4)
        if (!magic.startsWith("fLaC")) return emptyList()
        var position = 4L
        var durationMs: Long? = null
        var comments: List<String>? = null
        val length = reader.length()
        while (position + 4 <= length) {
            val header = ByteCursor(reader.read(position, 4))
            val first = header.u8()
            val type = first and 0x7F
            val size = header.u24be()
            when (type) {
                STREAMINFO -> {
                    val info = ByteCursor(reader.read(position + 4, size))
                    info.skip(10)
                    // 20 bits of sample rate, 3 of channels, 5 of bit depth, 36 of sample count.
                    val packed = info.u64be()
                    val sampleRate = packed ushr 44
                    val samples = packed and 0xF_FFFF_FFFFL
                    if (sampleRate > 0 && samples > 0) durationMs = samples * 1000 / sampleRate
                }
                VORBIS_COMMENT -> {
                    if (size > COMMENT_LIMIT) return emptyList()
                    comments = comments(ByteCursor(reader.read(position + 4, size)))
                }
            }
            position += 4 + size
            if (first and 0x80 != 0 || comments != null && durationMs != null) break
        }
        return chapters(comments ?: return emptyList(), durationMs)
    }

    /**
     * Ogg: the comments are the stream's second packet, which may span pages; only the pages up
     * to its end are read. The end of the last chapter is left to the caller, because Ogg says
     * how long it is only in its last page.
     */
    suspend fun readOgg(reader: ByteRangeReader): List<FileChapter> {
        var position = 0L
        var serial: Long? = null
        val packet = java.io.ByteArrayOutputStream()
        var packetIndex = 0
        var read = 0L
        while (read < COMMENT_LIMIT) {
            val head = reader.read(position, 27)
            if (head.size < 27 || !head.startsWith("OggS")) return emptyList()
            val c = ByteCursor(head)
            c.skip(14)
            val pageSerial = c.u32le()
            c.skip(8)
            val segments = c.u8()
            val table = reader.read(position + 27, segments)
            if (table.size < segments) return emptyList()
            val bodySize = table.sumOf { it.toInt() and 0xFF }
            val body = reader.read(position + 27 + segments, bodySize)
            if (body.size < bodySize) return emptyList()
            position += 27 + segments + bodySize
            read += 27 + segments + bodySize
            // Pages of other streams (a multiplexed file) are not ours.
            if (serial == null) serial = pageSerial
            if (pageSerial != serial) continue
            var offset = 0
            for (lace in table) {
                val n = lace.toInt() and 0xFF
                packet.write(body, offset, n)
                offset += n
                if (n < 255) {
                    if (packetIndex == 1) return commentPacket(packet.toByteArray())
                    packetIndex++
                    packet.reset()
                }
            }
        }
        return emptyList()
    }

    private fun commentPacket(packet: ByteArray): List<FileChapter> {
        val skip = when {
            packet.startsWith("\u0003vorbis") -> 7
            packet.startsWith("OpusTags") -> 8
            else -> return emptyList()
        }
        return chapters(comments(ByteCursor(packet, skip)), durationMs = null)
    }

    /** The Vorbis comment structure: a vendor string, then a count of "KEY=value" strings. */
    private fun comments(c: ByteCursor): List<String> {
        c.skip(c.u32le().toCount(c.remaining))
        return List(c.u32le().toCount(c.remaining / 4)) { c.string(c.u32le().toCount(c.remaining)) }
    }

    fun chapters(comments: List<String>, durationMs: Long?): List<FileChapter> {
        val starts = sortedMapOf<Int, Long>()
        val names = mutableMapOf<Int, String>()
        for (comment in comments) {
            val key = comment.substringBefore('=', "").uppercase()
            val value = comment.substringAfter('=', "")
            val match = KEY.matchEntire(key) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (match.groupValues[2] == "NAME") {
                value.trim().takeIf { it.isNotEmpty() }?.let { names[number] = it }
            } else {
                parseTime(value)?.let { starts[number] = it }
            }
            if (starts.size > MAX_CHAPTERS) malformed("too many chapters")
        }
        val marks = starts.entries.sortedBy { it.value }
        return marks.mapIndexed { i, (number, start) ->
            FileChapter(start, marks.getOrNull(i + 1)?.value ?: durationMs, names[number])
        }
    }

    /** "HH:MM:SS.mmm"; the fraction is a decimal fraction of a second ("4.5" is 4500 ms). */
    fun parseTime(value: String): Long? {
        val match = TIME.matchEntire(value.trim()) ?: return null
        val (h, m, s, fraction) = match.destructured
        val ms = fraction.take(3).padEnd(3, '0').toLong()
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms
    }

    private val KEY = Regex("CHAPTER(\\d+)(NAME)?")
    private val TIME = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d*))?")
    private const val STREAMINFO = 0
    private const val VORBIS_COMMENT = 4
    private const val COMMENT_LIMIT = 16 shl 20
}
