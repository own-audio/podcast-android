package audio.own.kit.audiobookshelf

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How long to wait on `429 Too Many Requests`. Audiobookshelf limits sign-ins and, behind
 * some proxies, all requests; the answer says how long to wait in `Retry-After`. A request
 * waits that long (or a doubling backoff from [firstDelayMillis] when the server doesn't say)
 * at most [maxRetries] times, and never longer than [maxDelayMillis] in one go: a server asking
 * for minutes gets [AudiobookshelfError.RateLimited] straight away, so nothing sits blocked or
 * loops tightly.
 */
class RateLimitPolicy(
    val maxRetries: Int = 2,
    val firstDelayMillis: Long = 2_000,
    val maxDelayMillis: Long = 30_000,
    internal val sleep: suspend (Long) -> Unit = { delay(it) },
)

/**
 * The HTTP side shared by every Audiobookshelf client: the app's one [OkHttpClient] (with its
 * timeouts, proxy and TLS settings), the User-Agent every request carries, the 429 policy and
 * where failures are logged.
 *
 * @param userAgent e.g. `own.audio Books/1.0.0 (Android 15; Pixel 8)`; sent on every request,
 *   because some edges in front of servers refuse requests without a proper one.
 */
class AudiobookshelfHttp(
    val client: OkHttpClient,
    val userAgent: String,
    val rateLimit: RateLimitPolicy = RateLimitPolicy(),
    val log: NetworkLog = NetworkLog.None,
) {
    internal fun request(): Request.Builder = Request.Builder()
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")

    /** The body of a 2xx answer; anything else is thrown as an [AudiobookshelfError]. */
    internal suspend fun data(request: Request): ByteArray {
        var attempt = 0
        while (true) {
            val answer = try {
                await(request)
            } catch (e: IOException) {
                log.error("${request.method} ${request.url.encodedPath} failed: ${e.javaClass.simpleName}")
                throw AudiobookshelfError.Network(e.javaClass.simpleName)
            }
            if (answer.code == 429) {
                val asked = retryAfterMillis(answer.headers)
                val backoff = minOf(rateLimit.firstDelayMillis shl attempt.coerceAtMost(20), rateLimit.maxDelayMillis)
                // The server's own figure wins; a "0" still waits a moment so retries never spin.
                val wait = asked?.coerceAtLeast(MIN_WAIT_MILLIS) ?: backoff
                if (attempt >= rateLimit.maxRetries || (asked != null && asked > rateLimit.maxDelayMillis)) {
                    log.error("${request.method} ${request.url.encodedPath}: HTTP 429, retry after ${asked ?: "?"} ms")
                    throw AudiobookshelfError.RateLimited(asked)
                }
                rateLimit.sleep(wait)
                attempt++
                continue
            }
            if (answer.code !in 200..299) {
                val text = answer.body.copyOf(minOf(answer.body.size, 300)).decodeToString()
                log.error("${request.method} ${request.url.encodedPath}: HTTP ${answer.code} $text")
                throw AudiobookshelfError.Http(answer.code, errorMessage(answer.body, text))
            }
            return answer.body
        }
    }

    private class Answer(val code: Int, val headers: Headers, val body: ByteArray)

    private suspend fun await(request: Request): Answer = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = try {
                    response.use { Answer(it.code, it.headers, it.body.bytes()) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(answer)
            }
        })
    }

    internal companion object {
        const val MIN_WAIT_MILLIS = 1_000L

        /** `Retry-After` as seconds or as an HTTP date, in milliseconds from now. */
        fun retryAfterMillis(headers: Headers, now: Long = System.currentTimeMillis()): Long? {
            val raw = headers["Retry-After"]?.trim() ?: return null
            raw.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            val date = headers.getDate("Retry-After") ?: return null
            return (date.time - now).coerceAtLeast(0)
        }

        /** The `{error}` of a JSON error body, else its text. */
        fun errorMessage(body: ByteArray, text: String): String? {
            val fromJson = runCatching {
                ((AbsJson.parseToJsonElement(body.decodeToString()) as? JsonObject)?.get("error") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            }.getOrNull()
            return fromJson ?: text.ifEmpty { null }
        }
    }
}
