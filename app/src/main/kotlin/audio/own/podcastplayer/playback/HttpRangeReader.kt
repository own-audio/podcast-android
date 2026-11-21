package audio.own.podcastplayer.playback

import audio.own.player.spoken.chapters.ByteRangeReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A stream's bytes by HTTP Range, for reading the chapters a file carries without downloading it.
 * Fetched with the plain client: the link is a publisher's or a signed one and needs no token.
 * A server that ignores Range is read only at the very start, never in full.
 */
class HttpRangeReader(private val http: OkHttpClient, private val url: String) : ByteRangeReader {
    private var size: Long? = null

    override suspend fun length(): Long = size ?: withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: throw IOException("no length for $url")
            total.also { size = it }
        }
    }

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        if (length <= 0 || offset < 0) return@withContext ByteArray(0)
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> response.body.bytes()
                200 -> {
                    if (offset > 0) throw IOException("no ranges at $url")
                    response.body.source().let { source ->
                        source.request(length.toLong())
                        val available = minOf(length.toLong(), source.buffer.size).toInt()
                        source.buffer.readByteArray(available.toLong())
                    }
                }
                416 -> ByteArray(0)
                else -> throw IOException("HTTP ${response.code} from $url")
            }
        }
    }
}
