package audio.own.player.spoken.chapters

/**
 * Chapters of an MP3 from its ID3v2 tag (versions 2.3 and 2.4): `CHAP` frames, each with a start
 * and end in milliseconds and a `TIT2` title inside, and optionally a top-level `CTOC` frame that
 * gives their order. Frame headers are read one by one so a large cover picture is skipped, not
 * downloaded.
 */
internal class Id3ChapterReader(private val reader: ByteRangeReader) {
    private class Chap(val id: String, val startMs: Long, val endMs: Long, val title: String?)

    private class Toc(val id: String, val isTopLevel: Boolean, val children: List<String>)

    suspend fun read(): List<FileChapter> {
        val header = ByteCursor(reader.read(0, 10))
        if (header.remaining < 10 || header.ascii(3) != "ID3") return emptyList()
        val major = header.u8()
        header.skip(1)
        val flags = header.u8()
        val tagSize = syncsafe(header.u32be())
        // ID3v2.2 has three-letter frames and no chapters; 2.3 with whole-tag unsynchronisation
        // would need every header decoded, and no chapter tool writes it.
        if (major !in 3..4) return emptyList()
        if (major == 3 && flags and 0x80 != 0) return emptyList()

        val tagEnd = 10L + tagSize
        var position = 10L
        if (flags and 0x40 != 0) {
            val ext = ByteCursor(reader.read(position, 4))
            val size = ext.u32be()
            // 2.3 counts the size without its own four bytes; 2.4 is syncsafe and includes them.
            position += if (major == 3) size + 4 else syncsafe(size)
        }

        val chaps = mutableListOf<Chap>()
        val tocs = mutableListOf<Toc>()
        while (position + 10 <= tagEnd) {
            val frame = ByteCursor(reader.read(position, 10))
            if (frame.remaining < 10) break
            val id = frame.ascii(4)
            if (id[0] == '\u0000') break // padding
            val rawSize = frame.u32be()
            val size = if (major == 4) syncsafe(rawSize) else rawSize
            val frameFlags = frame.u16be()
            if (size <= 0 || position + 10 + size > tagEnd) break
            if (id == "CHAP" || id == "CTOC") {
                val content = frameContent(reader.read(position + 10, size.toCount(FRAME_LIMIT)), major, frameFlags)
                if (content != null) {
                    if (id == "CHAP") chap(content, major)?.let { chaps += it } else toc(content)?.let { tocs += it }
                }
                if (chaps.size > MAX_CHAPTERS) malformed("too many chapters")
            }
            position += 10 + size
        }
        return ordered(chaps, tocs).map { FileChapter(it.startMs, it.endMs.takeIf { end -> end > it.startMs }, it.title) }
    }

    /** The top-level table of contents' order (nested tables flattened), else by start time. */
    private fun ordered(chaps: List<Chap>, tocs: List<Toc>): List<Chap> {
        val byId = chaps.associateBy { it.id }
        val tocById = tocs.associateBy { it.id }
        val top = tocs.firstOrNull { it.isTopLevel } ?: return chaps.sortedBy { it.startMs }
        val out = mutableListOf<Chap>()
        val seen = mutableSetOf<String>()
        fun walk(toc: Toc) {
            if (!seen.add(toc.id)) return
            for (child in toc.children) {
                byId[child]?.let { if (it !in out) out += it } ?: tocById[child]?.let(::walk)
            }
        }
        walk(top)
        return out.ifEmpty { chaps.sortedBy { it.startMs } }
    }

    private fun chap(content: ByteArray, major: Int): Chap? {
        val c = ByteCursor(content)
        val id = terminated(c)
        val start = c.u32be()
        val end = c.u32be()
        c.skip(8) // byte offsets, which describe the same marks less usefully
        var title: String? = null
        while (c.remaining >= 10) {
            val subId = c.ascii(4)
            val raw = c.u32be()
            val size = if (major == 4) syncsafe(raw) else raw
            val subFlags = c.u16be()
            if (size < 0 || size > c.remaining) break
            val body = c.bytes(size.toInt())
            if (subId == "TIT2" && title == null) title = frameContent(body, major, subFlags)?.let(::text)
        }
        return Chap(id, start, end, title)
    }

    private fun toc(content: ByteArray): Toc? {
        val c = ByteCursor(content)
        val id = terminated(c)
        val flags = c.u8()
        val count = c.u8()
        val children = List(count) { terminated(c) }
        return Toc(id, isTopLevel = flags and 0x02 != 0, children = children)
    }

    /** Undoes the per-frame transformations; null for the ones not worth supporting. */
    private fun frameContent(content: ByteArray, major: Int, flags: Int): ByteArray? {
        if (major == 3) {
            // Compressed or encrypted.
            return if (flags and 0x00C0 != 0) null else content
        }
        if (flags and 0x000C != 0) return null
        var body = if (flags and 0x0002 != 0) deunsynchronise(content) else content
        if (flags and 0x0001 != 0) body = if (body.size >= 4) body.copyOfRange(4, body.size) else return null
        return body
    }

    /** A text frame: an encoding byte, then the text, maybe several values split by NULs. */
    private fun text(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val payload = body.copyOfRange(1, body.size)
        val decoded = when (body[0].toInt()) {
            0 -> String(payload, Charsets.ISO_8859_1)
            1 -> String(payload, Charsets.UTF_16)
            2 -> String(payload, Charsets.UTF_16BE)
            3 -> String(payload, Charsets.UTF_8)
            else -> return null
        }
        return decoded.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun terminated(c: ByteCursor): String {
        val start = c.position
        while (c.remaining > 0 && c.bytes[c.position] != 0.toByte()) c.skip(1)
        val value = String(c.bytes, start, c.position - start, Charsets.ISO_8859_1)
        if (c.remaining > 0) c.skip(1)
        return value
    }

    private fun deunsynchronise(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            out.write(bytes[i].toInt())
            if (bytes[i] == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncsafe(value: Long): Long =
        (value and 0x7F) or ((value shr 8 and 0x7F) shl 7) or ((value shr 16 and 0x7F) shl 14) or ((value shr 24 and 0x7F) shl 21)

    private companion object {
        const val FRAME_LIMIT = 1 shl 20
    }
}
