package audio.own.player.spoken.chapters

import java.nio.charset.Charset

/** A file that doesn't parse as it claims to. The readers turn it into "no chapters". */
internal class MalformedFileException(message: String) : Exception(message)

internal fun malformed(message: String): Nothing = throw MalformedFileException(message)

/** Sequential reads over a byte array, bounds-checked so a lying size can't read past the end. */
internal class ByteCursor(val bytes: ByteArray, var position: Int = 0, val end: Int = bytes.size) {
    val remaining: Int get() = end - position

    private fun need(count: Int) {
        if (count < 0 || position + count > end) malformed("needs $count bytes at $position, has $remaining")
    }

    fun skip(count: Int) {
        need(count)
        position += count
    }

    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun u16be(): Int = (u8() shl 8) or u8()

    fun u24be(): Int = (u16be() shl 8) or u8()

    fun u32be(): Long = (u16be().toLong() shl 16) or u16be().toLong()

    fun u64be(): Long = (u32be() shl 32) or u32be()

    fun u32le(): Long {
        val b0 = u8().toLong()
        val b1 = u8().toLong()
        val b2 = u8().toLong()
        val b3 = u8().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun string(count: Int, charset: Charset = Charsets.UTF_8): String = String(bytes(count), charset)

    fun ascii(count: Int): String = string(count, Charsets.ISO_8859_1)
}

internal fun ByteArray.startsWith(prefix: String, at: Int = 0): Boolean {
    if (size < at + prefix.length) return false
    return prefix.indices.all { this[at + it] == prefix[it].code.toByte() }
}

/** An int count that a file gave as an unsigned number, refused when it can't be real. */
internal fun Long.toCount(limit: Int): Int {
    if (this < 0 || this > limit) malformed("count $this over $limit")
    return toInt()
}
