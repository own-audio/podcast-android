package audio.own.kit.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches a feed politely: with the validators from the last fetch, so an unchanged feed costs a
 * 304 and a few hundred bytes; following redirects and reporting where the feed ended up; refusing
 * anything over [maxBytes], since a feed is text and a huge answer is a mistake.
 *
 * Give it the plain OkHttp client, not one that adds a server's token: feeds live on publishers'
 * hosts, which must never see own.audio credentials.
 */
class FeedFetcher(
    client: OkHttpClient,
    private val parser: FeedParser,
    /** `own.audio Podcasts/<version> (Android <release>; <model>)`; some publishers refuse requests without one. */
    private val userAgent: String = "own.audio Podcasts",
    val maxBytes: Long = 25L shl 20,
) {
    // No HTTP cache: the validators below are the cache, kept with the feed. No cookies: a
    // publisher has no reason to recognise the listener from one fetch to the next.
    private val feedClient = client.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val chaptersClient = feedClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    @Serializable
    data class Validators(
        val etag: String? = null,
        val lastModified: String? = null,
    )

    sealed interface Result {
        data object NotModified : Result

        /** [finalUrl] differs from the one asked for when the publisher redirected. */
        data class Fetched(val document: FeedDocument, val validators: Validators, val finalUrl: String) : Result
    }

    @Throws(FeedException::class)
    suspend fun fetch(url: String, validators: Validators? = null): Result {
        val target = url.toHttpUrlOrNull() ?: throw FeedException.Network(IllegalArgumentException("not an http(s) URL"))
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", userAgent)
            .header("Accept", ACCEPT)
            .apply {
                validators?.etag?.let { header("If-None-Match", it) }
                validators?.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        return feedClient.newCall(request).execute { response ->
            if (response.code == 304) return@execute Result.NotModified
            if (response.code !in 200..299) throw FeedException.Http(response.code)
            val bytes = readCapped(response)
            val document = parser.parse(bytes)
            val next = Validators(etag = response.header("ETag"), lastModified = response.header("Last-Modified"))
            // Without a redirect the URL is returned exactly as given: OkHttp's form may differ in
            // escaping, and the feed URL is what gPodder servers know the show by.
            val finalUrl = if (response.priorResponse != null) response.request.url.toString() else url
            Result.Fetched(document, next, finalUrl)
        }
    }

    /** A chapters document named by `podcast:chapters`. */
    @Throws(FeedException::class)
    suspend fun chapters(url: String): List<FeedChapter> {
        val target: HttpUrl = url.toHttpUrlOrNull() ?: throw FeedException.Network(IllegalArgumentException("not an http(s) URL"))
        val request = Request.Builder().url(target).header("User-Agent", userAgent).build()
        return chaptersClient.newCall(request).execute { response ->
            if (response.code !in 200..299) throw FeedException.Http(response.code)
            Chapters.parse(readCapped(response))
        }
    }

    private fun readCapped(response: Response): ByteArray {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) throw FeedException.TooLarge()
        val out = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 64 * 1024)
        val buffer = ByteArray(64 * 1024)
        try {
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > maxBytes) throw FeedException.TooLarge()
                    out.write(buffer, 0, read)
                }
            }
        } catch (error: IOException) {
            throw FeedException.Network(error)
        }
        return out.toByteArray()
    }

    private suspend fun <T> Call.execute(handle: (Response) -> T): T {
        val response = try {
            await()
        } catch (error: IOException) {
            throw FeedException.Network(error)
        }
        return withContext(Dispatchers.IO) { response.use(handle) }
    }

    companion object {
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
