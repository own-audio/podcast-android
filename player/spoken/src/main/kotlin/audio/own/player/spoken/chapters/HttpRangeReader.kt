package audio.own.player.spoken.chapters

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A file on a server read with HTTP Range requests, for the chapter readers. [client] must be the
 * bare one: stream links need no Authorization and must never carry it to a storage host. Wrap it
 * in [BlockCachingRangeReader].
 */
class HttpRangeReader(private val client: OkHttpClient, private val url: String) : ByteRangeReader {
    private var size: Long? = null

    override suspend fun length(): Long {
        size?.let { return it }
        // A one-byte range answers with the full size in Content-Range.
        fetch(0, 1)
        return size ?: throw IOException("No length for the file")
    }

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        size?.let { if (offset >= it) return ByteArray(0) }
        return fetch(offset, length)
    }

    private suspend fun fetch(offset: Long, length: Int): ByteArray {
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        val response = client.newCall(request).await()
        return response.use {
            when (it.code) {
                206 -> {
                    it.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { total -> size = total }
                    withContext(Dispatchers.IO) { it.body.bytes() }
                }
                // A server that ignores ranges sends everything: take the part asked for, once.
                200 -> {
                    val all = withContext(Dispatchers.IO) { it.body.bytes() }
                    size = all.size.toLong()
                    if (offset >= all.size) ByteArray(0) else all.copyOfRange(offset.toInt(), minOf(all.size.toLong(), offset + length).toInt())
                }
                416 -> ByteArray(0)
                else -> throw IOException("HTTP ${it.code}")
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
    }
}
