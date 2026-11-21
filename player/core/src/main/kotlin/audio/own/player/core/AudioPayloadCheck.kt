package audio.own.player.core

import java.io.File

/**
 * Decides whether bytes fetched from a server are plausibly audio at all.
 *
 * Subsonic reports failures as **HTTP 200** with a JSON body
 * (`{"subsonic-response":{"status":"failed","error":{"code":70,…}}}`). A download that only
 * checks the status code writes that error text to disk as if it were a track, which is exactly
 * what happens after Navidrome renumbers its ids and starts answering "data not found".
 *
 * Phrased as "is this audio?" rather than "is this a Subsonic error?" on purpose: it is the same
 * question for every source, and it also catches an HTML error page from a reverse proxy.
 */
object AudioPayloadCheck {
    /**
     * Container and codec magic numbers, at their offsets. A list of what audio *is* rather than
     * what it isn't: a new error format nobody predicted should fail closed.
     */
    private val signatures: List<Pair<Int, ByteArray>> = listOf(
        0 to "ID3".toByteArray(),                 // MP3 with an ID3v2 tag
        0 to byteArrayOf(0xFF.toByte()),          // a bare MPEG frame (MP3/AAC-ADTS)
        0 to "fLaC".toByteArray(),
        0 to "OggS".toByteArray(),
        0 to "RIFF".toByteArray(),                // WAV
        0 to "FORM".toByteArray(),                // AIFF
        4 to "ftyp".toByteArray(),                // MP4/M4A/M4B
        0 to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), // Matroska/WebM (EBML)
    )

    /**
     * A body this small cannot be a track. The real failures were 182 bytes; a real audio file,
     * even a very short one, carries far more header than this on its own.
     */
    const val MINIMUM_PLAUSIBLE_BYTES = 1024

    /** How much of a file [looksLikeAudio] reads. */
    const val PREFIX_BYTES = 64

    private val rejectedTypes = listOf("application/json", "text/", "application/xml")

    /**
     * [contentType] is advisory: some servers send `application/octet-stream` for good audio, so
     * it can only ever reject, never approve on its own.
     */
    fun looksLikeAudio(contentType: String?, prefix: ByteArray): Boolean {
        val type = contentType?.lowercase()
        if (type != null && rejectedTypes.any { type.contains(it) }) return false
        // A JSON object or an XML/HTML document, whatever the declared type says.
        val first = prefix.firstOrNull()
        if (first == '{'.code.toByte() || first == '<'.code.toByte()) return false
        return signatures.any { (offset, bytes) -> startsWith(prefix, bytes, offset) }
    }

    /** The same question for a file on disk; reads only the head, so a big download stays cheap. */
    fun looksLikeAudio(contentType: String?, file: File): Boolean {
        if (!file.isFile || file.length() < MINIMUM_PLAUSIBLE_BYTES) return false
        val prefix = try {
            file.inputStream().use { input -> ByteArray(PREFIX_BYTES).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        } catch (_: Exception) {
            return false
        }
        return looksLikeAudio(contentType, prefix)
    }

    /**
     * The file extension the bytes call for, or null when they are not a format known here (then
     * ask [fileExtension] with the content type). The bytes win over the content type, which
     * servers get wrong; Subsonic stream URLs end in `stream.view` and say nothing.
     */
    fun fileExtension(prefix: ByteArray): String? {
        val b = prefix
        fun starts(text: String, offset: Int = 0) = startsWith(b, text.toByteArray(), offset)
        return when {
            starts("ftyp", 4) -> "m4a"
            starts("fLaC") -> "flac"
            starts("OggS") -> "ogg"
            starts("RIFF") -> "wav"
            starts("FORM") -> "aiff"
            // ADTS AAC (a server's transcode on mobile data) shares MP3's frame sync; its layer
            // bits are 00, which no MP3 frame has. Saved as .mp3 it would not open at all.
            b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xF6) == 0xF0 -> "aac"
            starts("ID3") -> "mp3"
            b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }

    fun fileExtension(mimeType: String?): String? = when (mimeType?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        else -> null
    }

    private fun startsWith(data: ByteArray, magic: ByteArray, offset: Int): Boolean {
        if (data.size < offset + magic.size) return false
        return magic.indices.all { data[offset + it] == magic[it] }
    }
}
