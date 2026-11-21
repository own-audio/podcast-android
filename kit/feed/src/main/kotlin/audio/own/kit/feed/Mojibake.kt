package audio.own.kit.feed

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Repairs text that was UTF-8 once and got read as a single-byte encoding somewhere between the
 * file's tags and the publisher's database: "Ã¡" for "á", "Ä\u008d" for "č". Fixing it at read
 * time is cheaper than asking every publisher to fix their feed.
 *
 * Only text that is certainly broken is touched: it must contain one of the lead bytes UTF-8 uses
 * (`Ã`, `Ä`, `Å`, `Â`, `Ø`, `Ð`, `Ñ`, `Þ`, `ß`…) and re-decode cleanly; otherwise the text is
 * returned as it came.
 */
object Mojibake {
    private val suspects: Set<Char> = "ÃÄÅÂØÐÑÞßĹĂÈÉÊËÌÍÎÏ".toSet()

    // The misreading could have been Latin-1 (most servers) or Windows-1250/1252 (tags written on
    // Windows); each is tried, and the first that gives valid UTF-8 with fewer suspicious
    // characters than before wins.
    private val encodings: List<Charset> = listOf("ISO-8859-1", "windows-1252", "windows-1250")
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }

    fun repair(text: String): String {
        if (text.none { it in suspects }) return text
        val before = text.count { it in suspects }
        for (encoding in encodings) {
            val bytes = encodeStrictly(text, encoding) ?: continue
            val fixed = decodeUtf8Strictly(bytes) ?: continue
            if (fixed == text) continue
            if (fixed.count { it in suspects } < before) return fixed
        }
        return text
    }

    private fun encodeStrictly(text: String, charset: Charset): ByteArray? = try {
        val buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    internal fun decodeUtf8Strictly(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
