package audio.own.kit.audiobookshelf

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Repairs text that was UTF-8 once and got read as a single-byte encoding somewhere between
 * the file's tags and the server's database: "Ã¡" for "á", "Ä\u008d" for "č". Some servers
 * hold such titles and chapter names (seen on a 2.37 library tagged by old software); fixing
 * them at display time is cheaper than asking every owner to retag.
 *
 * Only text that is certainly broken is touched: it must contain one of the lead bytes UTF-8
 * uses (`Ã`, `Ä`, `Å`, `Â`, `Ø`, `Ð`, `Ñ`, `Þ`, `ß`…) and re-decode cleanly; otherwise the text
 * is returned as it came.
 */
object Mojibake {
    private val suspects: Set<Char> = setOf(
        'Ã', 'Ä', 'Å', 'Â', 'Ø', 'Ð', 'Ñ', 'Þ', 'ß', 'Ĺ', 'Ă', 'È', 'É', 'Ê', 'Ë', 'Ì', 'Í', 'Î', 'Ï',
    )

    // The misreading could have been Latin-1 (most servers) or Windows-1252/1250 (tags written
    // on Windows); each is tried in turn.
    private val encodings: List<Charset> = listOf("ISO-8859-1", "windows-1252", "windows-1250")
        .filter { Charset.isSupported(it) }
        .map { Charset.forName(it) }

    fun repair(text: String): String {
        val before = text.count { it in suspects }
        if (before == 0) return text
        for (charset in encodings) {
            val bytes = encodeStrict(text, charset) ?: continue
            val fixed = decodeUtf8Strict(bytes) ?: continue
            if (fixed == text) continue
            // Keep the first reading that leaves fewer suspicious characters than before.
            if (fixed.count { it in suspects } < before) return fixed
        }
        return text
    }

    @JvmName("repairOrNull")
    fun repair(text: String?): String? = text?.let { repair(it) }

    private fun encodeStrict(text: String, charset: Charset): ByteBuffer? = try {
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeUtf8Strict(bytes: ByteBuffer): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes)
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
