package audio.own.player.core

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Opens a placeholder address ([PlaybackUris]) by asking for its link and streaming that, with
 * the two recoveries a signed link needs:
 *
 * - **Expired link**: renewed before use once past `expiresAtMs`, and once more when the
 *   stream answers 403, 404 or 410. A second failure is the error; it never loops.
 * - **Blocked storage** ([StorageRoute]): when the redirect to cloud storage fails at the
 *   storage host, or a filtering proxy answers in its place, the same link is tried once with
 *   `route=proxy`; the way that worked is remembered in [proxyMemory].
 *
 * Any other address (a plain `https://` link) goes straight to [upstream].
 */
@UnstableApi
class PlaybackDataSource(
    private val upstream: HttpDataSource,
    private val links: PlaybackLinks,
    private val proxyMemory: ProxyMemory,
) : DataSource {
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val request = PlaybackUris.parse(dataSpec.uri)
            ?: return upstream.open(dataSpec).also { opened = true }
        var renew = false
        while (true) {
            val link = links.link(request, renew)
            try {
                return openRouted(dataSpec, link.url).also { opened = true }
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (renew || error.responseCode !in EXPIRED_LINK_STATUSES) throw error
                links.forget(request)
                renew = true
            }
        }
    }

    private fun openRouted(dataSpec: DataSpec, link: String): Long {
        val first = proxyMemory.route(link)
        try {
            return upstream.open(dataSpec.withUri(Uri.parse(first)))
        } catch (error: HttpDataSource.HttpDataSourceException) {
            val other = StorageRoute.alternate(first)
            if (other == null || !isBlocked(error, first)) throw error
            closeQuietly()
            // A second failure is thrown as it is: the other way was tried and failed too.
            val length = upstream.open(dataSpec.withUri(Uri.parse(other)))
            proxyMemory.worked(other)
            return length
        }
    }

    private fun isBlocked(error: Throwable, link: String): Boolean {
        for (cause in generateSequence(error) { it.cause }) {
            when (cause) {
                is StorageRoute.Failure -> return StorageRoute.isBlocked(cause, link)
                is BlockedAnswerException -> return StorageRoute.isBlocked(cause.status, cause.url, link)
            }
        }
        return false
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (!opened) return
        opened = false
        upstream.close()
    }

    private fun closeQuietly() {
        try {
            upstream.close()
        } catch (_: IOException) {
        }
    }

    @UnstableApi
    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val links: PlaybackLinks,
        private val proxyMemory: ProxyMemory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlaybackDataSource(upstream.createDataSource(), links, proxyMemory)
    }

    companion object {
        /** What an own.audio stream answers for a link that is expired, or for a file that moved. */
        val EXPIRED_LINK_STATUSES = setOf(403, 404, 410)

        /**
         * The HTTP data source for stream links: OkHttp on [client] made bare-link safe by
         * [StreamHttpClient], refusing answers that declare themselves JSON, text or XML.
         */
        fun httpFactory(client: OkHttpClient, userAgent: String? = null): HttpDataSource.Factory =
            OkHttpDataSource.Factory(StreamHttpClient.from(client))
                .setUserAgent(userAgent)
                .setContentTypePredicate { type -> !AudioPayloadCheck.isRejectedContentType(type) }
    }
}

/**
 * A storage host's filtering proxy answered in place of the file: what [StorageRoute.isBlocked]
 * needs to know, carried out of OkHttp, which says neither where an answer came from nor which
 * hop failed.
 */
class BlockedAnswerException(val status: Int, val url: String) : IOException("HTTP $status from $url")

/**
 * The OkHttp client for stream links, storage redirects and other people's hosts: no
 * Authorization header ever (a bearer token must not reach a storage or publisher host), and
 * redirects followed here rather than inside OkHttp, so a failure can say which hop it was at.
 */
object StreamHttpClient {
    private const val MAX_REDIRECTS = 10

    /**
     * [base] should be the app's bare client (its connection pool and timeouts are shared); its
     * own redirect handling is switched off for this interceptor's, which also follows
     * https → http and back: own.audio's local storage answers on plain http.
     */
    fun from(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(HopInterceptor())
            .build()

    private class HopInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            var request = original
            var hops = 0
            while (true) {
                val response = try {
                    chain.proceed(request)
                } catch (error: IOException) {
                    throw StorageRoute.Failure(request.url.toString(), error)
                }
                val location = if (response.isRedirect && hops < MAX_REDIRECTS) {
                    response.header("Location")?.let { request.url.resolve(it) }
                } else {
                    null
                }
                if (location != null) {
                    response.close()
                    hops += 1
                    request = request.newBuilder().url(location).removeHeader("Authorization").build()
                    continue
                }
                if (response.code in StorageRoute.blockingStatuses && !sameHost(request.url, original.url)) {
                    response.close()
                    throw BlockedAnswerException(response.code, request.url.toString())
                }
                return response
            }
        }

        private fun sameHost(a: HttpUrl, b: HttpUrl) = a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }
}
