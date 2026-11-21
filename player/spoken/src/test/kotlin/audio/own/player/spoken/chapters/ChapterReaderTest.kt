package audio.own.player.spoken.chapters

import audio.own.player.spoken.SpokenPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ChapterReaderTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private suspend fun chaptersOf(bytes: ByteArray) = EmbeddedChapters.read(ByteArrayRangeReader(bytes))

    private val threeChapters = listOf(
        FileChapter(0, 2000, "One"),
        FileChapter(2000, 4000, "Twö"),
        FileChapter(4000, 6000, "Three"),
    )

    // MP4

    @Test
    fun `an M4B with moov first gives its chapter track`() = runTest {
        assertEquals(threeChapters, chaptersOf(fixture("chapters-faststart.m4b")))
    }

    @Test
    fun `an M4A with moov after the audio is read the same`() = runTest {
        assertEquals(threeChapters, chaptersOf(fixture("chapters-moov-last.m4a")))
    }

    @Test
    fun `the Nero chpl box alone gives the same marks, ended by the next start and the movie`() = runTest {
        val nero = Mp4ChapterReader(ByteArrayRangeReader(fixture("test-book.m4b"))).readNero()
        assertEquals(listOf("One", "Two", "Three"), nero.map { it.title })
        assertEquals(listOf(0L, 2000L, 4000L), nero.map { it.startMs })
        assertEquals(listOf(2000L, 4000L, null), nero.map { it.endMs })
    }

    @Test
    fun `without a chapter track reference the chpl box is used`() = runTest {
        // Renaming tref's chap entry hides the chapter track, as in files only Nero-style tools wrote.
        val bytes = fixture("test-book.m4b").replaceType("chap", "xxxx")
        val chapters = chaptersOf(bytes)
        assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
        assertEquals(6000L, chapters.last().endMs)
    }

    @Test
    fun `without chpl the chapter track alone is enough`() = runTest {
        val chapters = chaptersOf(fixture("test-book.m4b").replaceType("chpl", "free"))
        assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
        assertEquals(listOf(2000L, 4000L, 6000L), chapters.map { it.endMs })
    }

    @Test
    fun `iOS's book fixture moves to book time behind the part before it`() = runTest {
        val book = fixture("test-book.m4b")
        val tail = ByteArray(0) // a part with no chapters
        val parts = listOf(SpokenPart("p0", "mem:p0", 2000), SpokenPart("p1", "mem:p1", 6000))
        val chapters = EmbeddedChapters.read(parts, open = { ByteArrayRangeReader(if (it.id == "p1") book else tail) })
        assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
        assertEquals(listOf(2000L, 4000L, 6000L), chapters.map { it.startMs })
        assertEquals(8000L, chapters.last().endMs)
        assertTrue(EmbeddedChapters.read(parts.take(1), open = { ByteArrayRangeReader(tail) }).isEmpty())
    }

    // ID3

    @Test
    fun `ID3v2_3 and ID3v2_4 CHAP frames are read`() = runTest {
        assertEquals(threeChapters, chaptersOf(fixture("chapters-id3v23.mp3")))
        assertEquals(threeChapters, chaptersOf(fixture("chapters-id3v24.mp3")))
    }

    @Test
    fun `the top-level CTOC decides the order, a cover before it is skipped, titles decode`() = runTest {
        val tag = Id3Builder(major = 4)
            .frame("APIC", ByteArray(200_000) { 7 })
            .chap("c2", 5000, 9000, title = "Zweite", encoding = 1)
            .chap("c1", 0, 5000, title = "Première", encoding = 3)
            .chap("c3", 9000, 12000, title = null)
            .toc("toc", topLevel = true, children = listOf("c1", "sub"))
            .toc("sub", topLevel = false, children = listOf("c2", "c3"))
            .build()
        val reader = BlockCachingRangeReader(ByteArrayRangeReader(tag + ByteArray(1000)), blockSize = 1024)
        val chapters = EmbeddedChapters.read(reader)
        assertEquals(listOf("Première", "Zweite", null), chapters.map { it.title })
        assertEquals(listOf(0L, 5000L, 9000L), chapters.map { it.startMs })
        assertTrue("the picture was skipped, not read: ${reader.innerReads} reads", reader.innerReads < 10)
    }

    @Test
    fun `without a CTOC chapters go by start time`() = runTest {
        val tag = Id3Builder(major = 3).chap("b", 3000, 6000, "B").chap("a", 0, 3000, "A").build()
        assertEquals(listOf("A", "B"), chaptersOf(tag).map { it.title })
    }

    // Vorbis comments

    @Test
    fun `FLAC CHAPTER comments are read, any case, with a decimal fraction`() = runTest {
        val chapters = chaptersOf(fixture("chapters.flac"))
        assertEquals(listOf("One", "Twö", "Three"), chapters.map { it.title })
        assertEquals(listOf(0L, 2000L, 4500L), chapters.map { it.startMs })
        assertEquals("the last ends where STREAMINFO says the file does", 6000L, chapters.last().endMs)
    }

    @Test
    fun `Ogg Opus CHAPTER comments are read`() = runTest {
        val chapters = chaptersOf(fixture("chapters.opus"))
        assertEquals(listOf("One", "Twö", "Three"), chapters.map { it.title })
        assertEquals(listOf(0L, 2000L, 4000L), chapters.map { it.startMs })
        assertEquals(listOf(2000L, 4000L, null), chapters.map { it.endMs })
    }

    @Test
    fun `chapter times parse as hours, minutes, seconds and a fraction`() {
        assertEquals(5_025_250L, VorbisChapterReader.parseTime("01:23:45.250"))
        assertEquals(4_500L, VorbisChapterReader.parseTime("00:00:04.5"))
        assertEquals(61_000L, VorbisChapterReader.parseTime("0:01:01"))
        assertEquals(null, VorbisChapterReader.parseTime("soon"))
    }

    // Robustness

    @Test
    fun `truncated and random files give no chapters and never throw`() = runTest {
        val random = Random(42)
        for (name in listOf("chapters-faststart.m4b", "chapters-moov-last.m4a", "chapters-id3v23.mp3", "chapters.flac", "chapters.opus")) {
            val bytes = fixture(name)
            for (cut in listOf(4, 12, 40, 200, bytes.size / 2)) chaptersOf(bytes.copyOf(cut))
            repeat(20) {
                val damaged = bytes.copyOf()
                repeat(30) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
                chaptersOf(damaged)
            }
        }
        assertTrue(chaptersOf(ByteArray(0)).isEmpty())
        assertTrue(chaptersOf(Random(1).nextBytes(5000)).isEmpty())
    }

    @Test
    fun `a slow source runs into the time limit and plays without chapters`() = runTest {
        val slow = object : ByteRangeReader {
            override suspend fun length() = 1_000_000L
            override suspend fun read(offset: Long, length: Int): ByteArray {
                delay(1_000)
                return ByteArray(minOf(length, 16)).also { "ID3".toByteArray().copyInto(it) }
            }
        }
        val parts = List(20) { SpokenPart("p$it", "mem:$it", 1000) }
        val started = testScheduler.currentTime
        assertTrue(EmbeddedChapters.readWithinLimit(parts, open = { slow }).isEmpty())
        assertEquals(EmbeddedChapters.TIME_LIMIT_MS, testScheduler.currentTime - started)
    }

    @Test
    fun `the block cache answers header walks from memory`() = runTest {
        val bytes = fixture("chapters-moov-last.m4a")
        val reader = BlockCachingRangeReader(ByteArrayRangeReader(bytes), blockSize = 4096)
        assertEquals(threeChapters, EmbeddedChapters.read(reader))
        assertTrue("${reader.innerReads} reads", reader.innerReads <= 3)
        assertEquals(bytes.copyOfRange(4090, 4200).toList(), reader.read(4090, 110).toList())
        assertEquals(bytes.size - 7000, reader.read(7000, 100_000).size)
    }

    private fun ByteArray.replaceType(from: String, to: String): ByteArray {
        val out = copyOf()
        val needle = from.toByteArray()
        var replaced = 0
        for (i in 0..out.size - 4) {
            if ((0 until 4).all { out[i + it] == needle[it] }) {
                to.toByteArray().copyInto(out, i)
                replaced++
            }
        }
        check(replaced == 1) { "$from found $replaced times" }
        return out
    }
}

/** Builds small ID3v2 tags in code, for cases ffmpeg doesn't write (CTOC order, UTF-16). */
private class Id3Builder(private val major: Int) {
    private val frames = ByteArrayOutputStream()

    fun frame(id: String, body: ByteArray) = apply { frames.write(frameBytes(id, body)) }

    fun chap(id: String, startMs: Int, endMs: Int, title: String?, encoding: Int = 3) = apply {
        val body = ByteArrayOutputStream()
        body.write(id.toByteArray(Charsets.ISO_8859_1) + 0)
        body.write(int32(startMs) + int32(endMs) + int32(-1) + int32(-1))
        if (title != null) {
            val text = when (encoding) {
                1 -> title.toByteArray(Charsets.UTF_16) // with a byte-order mark
                else -> title.toByteArray(Charsets.UTF_8)
            }
            body.write(frameBytes("TIT2", byteArrayOf(encoding.toByte()) + text))
        }
        frame("CHAP", body.toByteArray())
    }

    fun toc(id: String, topLevel: Boolean, children: List<String>) = apply {
        val body = ByteArrayOutputStream()
        body.write(id.toByteArray(Charsets.ISO_8859_1) + 0)
        body.write(if (topLevel) 0x03 else 0x01)
        body.write(children.size)
        children.forEach { body.write(it.toByteArray(Charsets.ISO_8859_1) + 0) }
        frame("CTOC", body.toByteArray())
    }

    fun build(): ByteArray {
        val content = frames.toByteArray() + ByteArray(64) // padding
        return "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, 0) + syncsafe(content.size) + content
    }

    private fun frameBytes(id: String, body: ByteArray): ByteArray =
        id.toByteArray() + (if (major == 4) syncsafe(body.size) else int32(body.size)) + byteArrayOf(0, 0) + body

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun syncsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
}
