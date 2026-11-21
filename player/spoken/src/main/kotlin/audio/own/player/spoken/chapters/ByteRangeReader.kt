package audio.own.player.spoken.chapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to a file's bytes: a download on disk, a cached copy, or a stream link read with
 * HTTP Range requests. The chapter readers ask only for the few places they need (an M4B's
 * `moov`, an MP3's ID3 tag), never the whole file.
 */
interface ByteRangeReader {
    /** The file's size in bytes. */
    suspend fun length(): Long

    /** Up to [length] bytes from [offset]; fewer only at the end of the file. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

class ByteArrayRangeReader(private val bytes: ByteArray) : ByteRangeReader {
    override suspend fun length(): Long = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

class FileRangeReader(private val file: File) : ByteRangeReader {
    override suspend fun length(): Long = withContext(Dispatchers.IO) { file.length() }

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "r").use { raf ->
            if (offset < 0 || offset >= raf.length() || length <= 0) return@use ByteArray(0)
            val count = minOf(raf.length() - offset, length.toLong()).toInt()
            ByteArray(count).also {
                raf.seek(offset)
                raf.readFully(it)
            }
        }
    }
}

/**
 * Keeps the blocks already fetched, because the readers walk box and frame headers a few bytes
 * at a time: over HTTP every one of those would otherwise be a round trip. Reads larger than a
 * few blocks go straight through.
 */
class BlockCachingRangeReader(
    private val inner: ByteRangeReader,
    private val blockSize: Int = 64 * 1024,
    private val maxBlocks: Int = 64,
) : ByteRangeReader {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxBlocks
    }
    private var cachedLength: Long? = null

    /** How many reads reached the wrapped reader; tests use it to check the caching works. */
    var innerReads: Int = 0
        private set

    override suspend fun length(): Long = cachedLength ?: inner.length().also { cachedLength = it }

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0) return ByteArray(0)
        val first = offset / blockSize
        val last = (offset + length - 1) / blockSize
        if (last - first + 1 > MAX_CACHED_SPAN) {
            innerReads++
            return inner.read(offset, length)
        }
        var block = first
        while (block <= last) {
            if (blocks.containsKey(block)) {
                block++
                continue
            }
            // One request for each run of missing blocks.
            var runEnd = block
            while (runEnd + 1 <= last && !blocks.containsKey(runEnd + 1)) runEnd++
            innerReads++
            val bytes = inner.read(block * blockSize, ((runEnd - block + 1) * blockSize).toInt())
            for (b in block..runEnd) {
                val from = ((b - block) * blockSize).toInt()
                if (from >= bytes.size) break
                blocks[b] = bytes.copyOfRange(from, minOf(bytes.size, from + blockSize))
            }
            block = runEnd + 1
        }
        val out = java.io.ByteArrayOutputStream(length)
        for (b in first..last) {
            val data = blocks[b] ?: break
            val start = if (b == first) (offset - b * blockSize).toInt() else 0
            val end = if (b == last) minOf(data.size, (offset + length - b * blockSize).toInt()) else data.size
            if (start >= end) break
            out.write(data, start, end - start)
            if (data.size < blockSize) break
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_CACHED_SPAN = 8
    }
}
