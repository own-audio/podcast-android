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
}
