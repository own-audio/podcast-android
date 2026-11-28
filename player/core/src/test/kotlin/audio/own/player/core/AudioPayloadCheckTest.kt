package audio.own.player.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Port of music-ios `AudioPayloadCheckTests` and the extension tests of `TrackFileCacheTests`.
 *
 * The real failure this guards: Navidrome 0.64.0 answered `stream.view` with HTTP 200 and a
 * 182-byte Subsonic error body after its id migration, and a download path that only checked the
 * status wrote that to disk as a track.
 */
class AudioPayloadCheckTest {
    @get:Rule val temp = TemporaryFolder()

    private val subsonicError = """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.64.0 (1072e9f7)","openSubsonic":true,"error":{"code":70,"message":"data not found"}}}""".toByteArray()

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun rejectsSubsonicErrorBody() {
        assertFalse(AudioPayloadCheck.looksLikeAudio("application/json", subsonicError))
        // Servers have been seen labelling it audio; the bytes still decide.
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", subsonicError))
        assertFalse(AudioPayloadCheck.looksLikeAudio(null, subsonicError))
    }

    @Test
    fun rejectsHTML() {
        val html = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>".toByteArray()
        assertFalse(AudioPayloadCheck.looksLikeAudio("text/html", html))
        assertFalse(AudioPayloadCheck.looksLikeAudio("application/octet-stream", html))
    }

    @Test
    fun rejectsXML() {
        assertFalse(AudioPayloadCheck.looksLikeAudio("application/xml; charset=utf-8", "ID3".toByteArray()))
        assertFalse(AudioPayloadCheck.looksLikeAudio(null, "<?xml version=\"1.0\"?><error/>".toByteArray()))
    }

    @Test
    fun acceptsAudio() {
        val mp3WithTag = "ID3".toByteArray() + bytes(0x04, 0x00, 0x00)
        val bareFrame = bytes(0xFF, 0xFB, 0x90, 0x44)
        val flac = "fLaC".toByteArray() + bytes(0, 0)
        val m4a = bytes(0x00, 0x00, 0x00, 0x20) + "ftypM4A ".toByteArray()
        val ogg = "OggS".toByteArray() + bytes(0, 2)
        val webm = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F)
        for (payload in listOf(mp3WithTag, bareFrame, flac, m4a, ogg, webm)) {
            assertTrue(payload.contentToString(), AudioPayloadCheck.looksLikeAudio("application/octet-stream", payload))
        }
    }

    @Test
    fun rejectsUnknownBytes() {
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", "hello".toByteArray()))
        assertFalse(AudioPayloadCheck.looksLikeAudio(null, ByteArray(0)))
    }

    @Test
    fun rejectsTooSmallFile() {
        val file = temp.newFile("small.mp3").apply { writeBytes("ID3".toByteArray() + ByteArray(100)) }
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", file))
    }

    @Test
    fun acceptsPlausibleFile() {
        val file = temp.newFile("plausible.mp3").apply { writeBytes("ID3".toByteArray() + ByteArray(4096)) }
        assertTrue(AudioPayloadCheck.looksLikeAudio("audio/mpeg", file))
        assertFalse("a missing file", AudioPayloadCheck.looksLikeAudio("audio/mpeg", File(temp.root, "none")))
    }

    @Test
    fun extensionFromMagic() {
        for ((name, expected) in listOf("test-tone.mp3" to "mp3", "test-tone.flac" to "flac", "test-tone.m4a" to "m4a")) {
            assertEquals(name, expected, AudioPayloadCheck.fileExtension(fixture(name)))
            assertTrue(name, AudioPayloadCheck.looksLikeAudio("application/octet-stream", fixture(name)))
        }
    }

    @Test
    fun adtsIsNotMP3() {
        // The first bytes of an own.audio transcode (`-c:a aac -f adts`), MPEG-4 and MPEG-2.
        assertEquals("aac", AudioPayloadCheck.fileExtension(bytes(0xFF, 0xF1, 0x50, 0x80, 0x23, 0x1F)))
        assertEquals("aac", AudioPayloadCheck.fileExtension(bytes(0xFF, 0xF9, 0x50, 0x80, 0x23, 0x1F)))
        // An MPEG-1 Layer III frame header stays MP3.
        assertEquals("mp3", AudioPayloadCheck.fileExtension(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals("aac", AudioPayloadCheck.fileExtension("audio/aac"))
    }

    @Test
    fun unknownBytes() {
        assertNull(AudioPayloadCheck.fileExtension("hello".toByteArray()))
        assertEquals("flac", AudioPayloadCheck.fileExtension("audio/x-flac"))
        assertNull(AudioPayloadCheck.fileExtension(null as String?))
    }

    // region Audio after junk bytes

    /** Junk like the 384 bytes found before the first frame of a real Audiobookshelf book's MP3s. */
    private val junk = ByteArray(384) { i -> ((i * 37 + 11) % 0x7F).toByte() }

    private fun withoutId3(mp3: ByteArray): ByteArray {
        val size = ((mp3[6].toInt() and 0x7F) shl 21) or ((mp3[7].toInt() and 0x7F) shl 14) or ((mp3[8].toInt() and 0x7F) shl 7) or (mp3[9].toInt() and 0x7F)
        return mp3.copyOfRange(10 + size, mp3.size)
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Two ADTS frames of 16 bytes each. */
    private val adts = (bytes(0xFF, 0xF1, 0x50, 0x80, 0x02, 0x1F, 0xFC) + ByteArray(9)).let { it + it }

    @Test
    fun junkBeforeMp3IsAcceptedWhenTheServerSaysAudio() {
        val mp3 = fixture("test-tone.mp3")
        assertTrue(AudioPayloadCheck.looksLikeAudio("audio/mpeg", junk + mp3))
        // Frame sync alone, the tag gone: two frames where the first header says.
        assertTrue(AudioPayloadCheck.looksLikeAudio("audio/mpeg", junk + withoutId3(mp3)))
        assertTrue("the file name says audio", AudioPayloadCheck.looksLikeAudio("application/octet-stream", junk + mp3, extension = "mp3"))
        assertEquals(384, AudioPayloadCheck.audioStart(junk + withoutId3(mp3)))
    }

    @Test
    fun junkIsNotScannedWithoutAnAudioDeclaration() {
        val mp3 = fixture("test-tone.mp3")
        assertFalse(AudioPayloadCheck.looksLikeAudio("application/octet-stream", junk + mp3))
        assertFalse(AudioPayloadCheck.looksLikeAudio(null, junk + mp3, extension = "json"))
    }

    @Test
    fun audioPastTheScanWindowIsRefused() {
        val far = ByteArray(AudioPayloadCheck.SCAN_BYTES) { 0x20 } + fixture("test-tone.mp3")
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", far))
    }

    @Test
    fun aLoneFrameSyncInJunkIsNotAudio() {
        // A valid-looking MPEG header with nothing where the second frame should be.
        val fake = junk + bytes(0xFF, 0xFB, 0x90, 0x00) + ByteArray(2000)
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", fake))
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", junk + "hello, this is not audio at all".toByteArray()))
    }

    @Test
    fun errorBodiesDeclaredAsAudioAreStillRefused() {
        val html = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>".toByteArray() + fixture("test-tone.mp3")
        val json = """{"error":"not found"}""".toByteArray() + fixture("test-tone.mp3")
        for (type in listOf("audio/mpeg", "text/html", "application/json")) {
            assertFalse(type, AudioPayloadCheck.looksLikeAudio(type, html, extension = "mp3"))
            assertFalse(type, AudioPayloadCheck.looksLikeAudio(type, json, extension = "mp3"))
        }
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", "\n  <html><body>gone</body></html>".toByteArray()))
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/mpeg", subsonicError))
    }

    @Test
    fun eachFormatIsFoundAfterJunk() {
        val formats = mapOf(
            "flac" to fixture("test-tone.flac"),
            "m4a" to fixture("test-tone.m4a"),
            "ogg" to "OggS".toByteArray() + bytes(0, 2) + ByteArray(40),
            "wav" to "RIFF".toByteArray() + bytes(0x24, 0, 0, 0) + "WAVEfmt ".toByteArray() + ByteArray(40),
            "mka" to hex("1A45DFA39F4286810142F7810142F2810442F381084282886D6174726F736B61"),
            "aac" to adts,
        )
        for ((name, payload) in formats) {
            assertTrue("$name at 0", AudioPayloadCheck.looksLikeAudio("application/octet-stream", payload))
            assertTrue("$name after junk", AudioPayloadCheck.looksLikeAudio("audio/$name", junk + payload))
            assertEquals(name, 384, AudioPayloadCheck.audioStart(junk + payload))
        }
    }

    @Test
    fun matroskaSignatureIsRawBytes() {
        // music-ios once encoded this signature as text; these four bytes are not ASCII.
        assertTrue(AudioPayloadCheck.looksLikeAudio(null, hex("1A45DFA3")))
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/webm", junk + "1A45DFA3".toByteArray()))
        assertFalse(AudioPayloadCheck.looksLikeAudio("audio/webm", junk + "\u001AE\u00DF\u00A3".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun junkBeforeAudioInAFile() {
        val file = temp.newFile("book.mp3").apply { writeBytes(junk + fixture("test-tone.mp3")) }
        assertTrue(AudioPayloadCheck.looksLikeAudio("audio/mpeg", file))
        assertTrue(AudioPayloadCheck.looksLikeAudio(null, file, extension = "mp3"))
        assertFalse(AudioPayloadCheck.looksLikeAudio(null, file))
    }

    // endregion
}
