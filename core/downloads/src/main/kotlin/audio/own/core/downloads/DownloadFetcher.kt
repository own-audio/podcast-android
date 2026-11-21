package audio.own.core.downloads

import audio.own.player.core.AudioPayloadCheck
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import audio.own.player.core.StorageRoute
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Why a download stopped. [permanent] ones are shown as failed; the others are tried again. */
class DownloadFailure(val code: String, val permanent: Boolean, cause: Throwable? = null) : Exception(code, cause) {
    companion object {
        const val NOT_AUDIO = "not_audio"
        const val NETWORK = "network"
        const val LINK = "link"
        const val STORAGE = "storage"
        fun http(status: Int) = "http_$status"
    }
}

/** The link's server refused it as expired (403 from its own host): ask for a new one. */
class LinkExpired : IOException("link expired")

/**
 * One file over HTTP into `<name>.part`: with the bare client (a stream link needs no header and
 * must never carry the account's token), redirects followed by hand so a failure is known by its
 * host, resumed with `Range` where a `.part` is left from a killed run, and sent the other way
 * round storage when the network blocks it ([ProxyMemory]).
 */
class DownloadFetcher(
    http: OkHttpClient,
    private val proxyMemory: ProxyMemory,
    private val userAgent: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** Progress at most this often: twice a second. */
    private val progressEveryMs: Long = 500,
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class Fetched(val contentType: String?, val bytes: Long, val resumedFrom: Long)

    private class Answer(val response: Response, val url: String)

    /**
     * Fetches [link] into [part], appending when the server honours the range. Throws
     * [LinkExpired], [DownloadFailure] or an [IOException] (network: try again later).
     */
    suspend fun fetch(link: PlaybackLink, part: File, onProgress: suspend (done: Long, total: Long?) -> Unit): Fetched = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        val have = if (part.isFile) part.length() else 0L
        val answer = proxyMemory.fetch(
            link.url,
            answer = { ProxyMemory.Answer(it.response.code, it.url) },
            release = { it.response.close() },
        ) { url -> open(url, have) }
        answer.response.use { response -> receive(response, answer.url, link, part, have, onProgress) }
    }

    private suspend fun receive(response: Response, url: String, link: PlaybackLink, part: File, have: Long, onProgress: suspend (Long, Long?) -> Unit): Fetched {
        val status = response.code
        when {
            status == 416 -> {
                // What is on disk doesn't fit this file any more: from the start next time.
                part.delete()
                throw IOException("range not satisfiable")
            }
            status == 403 && StorageRoute.sameHostPublic(url, link.url) -> throw LinkExpired()
            status == 408 || status == 429 || status >= 500 -> throw IOException("HTTP $status")
            status !in 200..299 -> throw DownloadFailure(DownloadFailure.http(status), permanent = true)
        }
        val type = response.header("Content-Type") ?: link.contentType
        if (AudioPayloadCheck.isRejectedContentType(response.header("Content-Type"))) {
            throw DownloadFailure(DownloadFailure.NOT_AUDIO, permanent = true)
        }
        val append = status == 206 && have > 0 && rangeStart(response) == have
        if (status == 206 && !append) {
            part.delete()
            throw IOException("unexpected range")
        }
        val start = if (append) have else 0L
        val total = response.body.contentLength().takeIf { it >= 0 }?.let { it + start }
        val source = response.body.source()
        var done = start
        var lastReport = 0L
        FileOutputStream(part, append).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                done += read
                val t = now()
                if (t - lastReport >= progressEveryMs) {
                    lastReport = t
                    onProgress(done, total)
                }
            }
        }
        if (total != null && done < total) throw IOException("cut short at $done of $total")
        onProgress(done, total)
        return Fetched(type, done, start)
    }

    /** One request, its redirects followed here; each failure carries the address it failed at. */
    private fun open(url: String, have: Long): Answer {
        var current = url
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder().url(current).apply {
                if (have > 0) header("Range", "bytes=$have-")
                userAgent?.let { header("User-Agent", it) }
            }.build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw StorageRoute.Failure(current, e)
            }
            if (!response.isRedirect) return Answer(response, current)
            val location = response.header("Location")
            response.close()
            current = location?.let { request.url.resolve(it)?.toString() } ?: throw IOException("redirect without a location")
        }
        throw IOException("too many redirects")
    }

    private fun rangeStart(response: Response): Long? =
        response.header("Content-Range")?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_REDIRECTS = 8
        private val RANGE = Regex("""bytes\s+(\d+)-""")

        /** The file's extension: from its bytes, else the content type, else the record's format, else mp3. */
        fun extension(prefix: ByteArray, contentType: String?, hint: String?): String =
            AudioPayloadCheck.fileExtension(prefix)
                ?: AudioPayloadCheck.fileExtension(contentType?.substringBefore(';')?.trim())
                ?: hint?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
                ?: "mp3"
    }
}

/** [StorageRoute.sameHost] is internal to :player:core; the same rule for the expired-link check. */
internal fun StorageRoute.sameHostPublic(a: String, b: String): Boolean {
    val ua = a.toHttpUrlOrNull() ?: return false
    val ub = b.toHttpUrlOrNull() ?: return false
    return ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
}
