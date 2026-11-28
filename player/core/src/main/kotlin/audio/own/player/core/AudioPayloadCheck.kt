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
        // Matroska/WebM (EBML): raw bytes, not text; 0xDF and 0xA3 are not ASCII.
        0 to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
    )

    /**
     * A body this small cannot be a track. The real failures were 182 bytes; a real audio file,
     * even a very short one, carries far more header than this on its own.
     */
    const val MINIMUM_PLAUSIBLE_BYTES = 1024

    /** How much of a file [fileExtension] needs: the magic numbers all sit in the first bytes. */
    const val PREFIX_BYTES = 64

    /**
     * How far into a body declared as audio [looksLikeAudio] looks for where the audio starts.
     * Real files carry junk before the first frame: an Audiobookshelf book had MP3s with 384 bytes
     * of it, which players skip but a check at byte 0 refused.
     */
    const val SCAN_BYTES = 16 * 1024

    private val rejectedTypes = listOf("application/json", "text/", "application/xml")

    private val audioExtensions = setOf(
        "mp3", "mp2", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "aif", "aiff", "mka", "webm",
    )

    /**
     * [contentType] is advisory: some servers send `application/octet-stream` for good audio, so
     * it can only ever reject, never approve on its own. When it, or [extension], says audio, the
     * start of the audio may also lie past some junk within [SCAN_BYTES]; there only a strict
     * signature counts (an MP3 frame needs a second one where its header says).
     */
    fun looksLikeAudio(contentType: String?, prefix: ByteArray, extension: String? = null): Boolean {
        if (isRejectedContentType(contentType)) return false
        // A JSON object or an XML/HTML document, whatever the declared type says.
        val first = prefix.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        if (first == '{'.code.toByte() || first == '<'.code.toByte() || first == '['.code.toByte()) return false
        if (signatures.any { (offset, bytes) -> startsWith(prefix, bytes, offset) }) return true
        if (!declaresAudio(contentType, extension)) return false
        return audioStart(prefix) != null
    }

    /** The server's type is `audio/…`, or the file's name ends in an audio extension. */
    fun declaresAudio(contentType: String?, extension: String?): Boolean =
        contentType?.trim()?.lowercase()?.startsWith("audio/") == true ||
            extension?.trim()?.lowercase()?.removePrefix(".") in audioExtensions

    /** Where a strictly recognised audio stream starts within [prefix], or null. */
    fun audioStart(prefix: ByteArray): Int? {
        val end = minOf(prefix.size, SCAN_BYTES)
        for (i in 0 until end) {
            if (strictStart(prefix, i)) return i
        }
        return null
    }

    private fun strictStart(b: ByteArray, i: Int): Boolean {
        fun at(k: Int) = b[i + k].toInt() and 0xFF
        fun has(n: Int) = b.size >= i + n
        fun text(t: String, k: Int = 0) = startsWith(b, t.toByteArray(Charsets.US_ASCII), i + k)
        return when (at(0)) {
            // ID3v2: version 2–4, the four size bytes synchsafe.
            'I'.code -> has(10) && text("ID3") && at(3) in 2..4 && at(4) != 0xFF && (6..9).all { at(it) < 0x80 }
            'f'.code -> has(5) && text("fLaC") && (at(4) and 0x7F) == 0 // STREAMINFO comes first
            'O'.code -> has(5) && text("OggS") && at(4) == 0
            'R'.code -> has(12) && text("RIFF") && text("WAVE", 8)
            0x1A -> startsWith(b, EBML, i)
            0xFF -> adtsAt(b, i) || mpegAt(b, i)
            else -> has(12) && text("ftyp", 4) && boxSizeOk(b, i) && (8..11).all { at(it) in 0x20..0x7E }
        }
    }

    private fun boxSizeOk(b: ByteArray, i: Int): Boolean {
        val size = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
        return size in 8..1024
    }

    /** An ADTS header with another one where its frame length points. */
    private fun adtsAt(b: ByteArray, i: Int): Boolean {
        val length = adtsLength(b, i) ?: return false
        return adtsLength(b, i + length) != null
    }

    private fun adtsLength(b: ByteArray, i: Int): Int? {
        if (b.size < i + 7) return null
        val b1 = b[i + 1].toInt() and 0xFF
        if (b[i] != 0xFF.toByte() || (b1 and 0xF6) != 0xF0) return null
        val b2 = b[i + 2].toInt() and 0xFF
        if (((b2 shr 2) and 0x0F) > 12) return null
        val length = ((b[i + 3].toInt() and 0x03) shl 11) or ((b[i + 4].toInt() and 0xFF) shl 3) or ((b[i + 5].toInt() and 0xFF) shr 5)
        return length.takeIf { it >= 7 }
    }

    /** An MPEG audio frame header with a second, matching one at the length the first gives. */
    private fun mpegAt(b: ByteArray, i: Int): Boolean {
        val first = mpegFrame(b, i) ?: return false
        val second = mpegFrame(b, i + first.length) ?: return false
        return second.version == first.version && second.layer == first.layer && second.sampleRate == first.sampleRate
    }

    private class MpegFrame(val version: Int, val layer: Int, val sampleRate: Int, val length: Int)

    private fun mpegFrame(b: ByteArray, i: Int): MpegFrame? {
        if (b.size < i + 4) return null
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        val b3 = b[i + 3].toInt() and 0xFF
        if (b[i] != 0xFF.toByte() || (b1 and 0xE0) != 0xE0) return null
        val version = (b1 shr 3) and 3 // 0: MPEG 2.5, 1: reserved, 2: MPEG 2, 3: MPEG 1
        val layer = (b1 shr 1) and 3 // 0: reserved, 1: III, 2: II, 3: I
        val bitrateIndex = (b2 shr 4) and 0x0F
        val rateIndex = (b2 shr 2) and 3
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3 || (b3 and 3) == 2) return null
        val padding = (b2 shr 1) and 1
        val sampleRate = SAMPLE_RATES[rateIndex] shr when (version) { 3 -> 0; 2 -> 1; else -> 2 }
        val table = when {
            version == 3 && layer == 3 -> V1_L1
            version == 3 && layer == 2 -> V1_L2
            version == 3 -> V1_L3
            layer == 3 -> V2_L1
            else -> V2_L23
        }
        val bitrate = table[bitrateIndex - 1] * 1000
        val length = when {
            layer == 3 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 1 && version != 3 -> 72 * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }
        return MpegFrame(version, layer, sampleRate, length).takeIf { length >= 4 }
    }

    private val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)
    private val V1_L1 = intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val V1_L2 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val V1_L3 = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val V2_L1 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val V2_L23 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /**
     * A declared type that is certainly not audio: JSON, text (HTML) or XML. The stream data
     * source refuses such answers before a byte reaches the listening cache, where an error page
     * would otherwise be replayed as the song from then on.
     */
    fun isRejectedContentType(contentType: String?): Boolean {
        val type = contentType?.lowercase() ?: return false
        return rejectedTypes.any { type.contains(it) }
    }

    /** The same question for a file on disk; reads only the head, so a big download stays cheap. */
    fun looksLikeAudio(contentType: String?, file: File, extension: String? = null): Boolean {
        if (!file.isFile || file.length() < MINIMUM_PLAUSIBLE_BYTES) return false
        val prefix = try {
            file.inputStream().use { input ->
                val buffer = ByteArray(SCAN_BYTES)
                var have = 0
                while (have < buffer.size) {
                    val read = input.read(buffer, have, buffer.size - have)
                    if (read <= 0) break
                    have += read
                }
                buffer.copyOf(have)
            }
        } catch (_: Exception) {
            return false
        }
        return looksLikeAudio(contentType, prefix, extension)
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
