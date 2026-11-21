package audio.own.player.core

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * What the player asks a source for: the audio of [mediaId] (the app's composite id), one file
 * of it ([fileId], for books split into files), at most [maxBitrateKbps] (null: the original).
 */
data class PlaybackLinkRequest(
    val mediaId: String,
    val fileId: String? = null,
    val maxBitrateKbps: Int? = null,
) {
    /** A lower-bitrate copy is wanted (mobile data with Saver on). */
    val wantSmaller: Boolean get() = maxBitrateKbps != null
}

/**
 * A link to fetch audio from, as a source handed it out. [url] needs no Authorization header:
 * it is fetched with the bare HTTP client. [expiresAtMs] is when the source stops honouring it
 * (null: never, as with Subsonic's token links). [contentType] is a hint only; the player sniffs.
 */
data class PlaybackLink(
    val url: String,
    val expiresAtMs: Long? = null,
    val contentType: String? = null,
)

/**
 * Finds the link for a [PlaybackLinkRequest]: one implementation per kind of source (own.audio
 * `POST /playback-info`, Subsonic `stream`, Jellyfin `universal`, Audiobookshelf).
 *
 * Asked when a data source opens, on a loader thread, never ahead of time: links expire, and
 * one stored in a `MediaItem` or the database would be stale by the time it is played.
 * Throwing fails that load; the player shows the error.
 */
fun interface PlaybackLinkResolver {
    suspend fun resolve(request: PlaybackLinkRequest): PlaybackLink
}

/**
 * The address a `MediaItem` carries in place of a stream URL: `ownaudio-item://item?id=…`. It
 * names what to play, not where it is, so nothing that expires is ever stored with the item.
 * [PlaybackDataSource] turns it into a real link when it opens.
 */
object PlaybackUris {
    const val SCHEME = "ownaudio-item"
    private const val AUTHORITY = "item"
    private const val ID = "id"
    private const val FILE = "file"
    private const val BITRATE = "kbps"

    /** Marks the cache entry of a lower-bitrate copy, which must never pass for the original. */
    const val REDUCED_SUFFIX = "~reduced"

    fun forItem(mediaId: String, fileId: String? = null, maxBitrateKbps: Int? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority(AUTHORITY).appendQueryParameter(ID, mediaId).apply {
            if (fileId != null) appendQueryParameter(FILE, fileId)
            if (maxBitrateKbps != null) appendQueryParameter(BITRATE, maxBitrateKbps.toString())
        }.build()

    fun forRequest(request: PlaybackLinkRequest): Uri = forItem(request.mediaId, request.fileId, request.maxBitrateKbps)

    /** The request a placeholder names; null for any other address (a local file, a plain URL). */
    fun parse(uri: Uri?): PlaybackLinkRequest? {
        if (uri == null || uri.scheme != SCHEME) return null
        val id = uri.getQueryParameter(ID) ?: return null
        return PlaybackLinkRequest(id, uri.getQueryParameter(FILE), uri.getQueryParameter(BITRATE)?.toIntOrNull())
    }

    /**
     * The listening cache's key: the media id, `#fileId` for one file of a book, and
     * [REDUCED_SUFFIX] for a lower-bitrate copy. Never the URL: its signature changes with every
     * link, and keying by it would cache the same song again on each play.
     */
    fun cacheKey(request: PlaybackLinkRequest, reduced: Boolean = request.wantSmaller): String = buildString {
        append(request.mediaId)
        if (request.fileId != null) append('#').append(request.fileId)
        if (reduced) append(REDUCED_SUFFIX)
    }
}

/**
 * Builds cache keys from the placeholder address (see [PlaybackUris.cacheKey]). While a smaller
 * stream is wanted, a fully cached original still wins: an original always replaces a reduced
 * copy, never the other way round.
 *
 * The choice is made per placeholder, which the player builds per queue entry, so it cannot
 * flip in the middle of a song: byte offsets of one encoding are meaningless in the other.
 */
@UnstableApi
class PlaybackCacheKeyFactory(private val cache: Cache?) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        dataSpec.key?.let { return it }
        val request = PlaybackUris.parse(dataSpec.uri) ?: return dataSpec.uri.toString()
        val original = PlaybackUris.cacheKey(request, reduced = false)
        if (!request.wantSmaller) return original
        if (cache != null && isFullyCached(cache, original)) return original
        return PlaybackUris.cacheKey(request, reduced = true)
    }

    companion object {
        /**
         * Whether every byte of [key] is in [cache]. Only a completed read records the length,
         * so "some bytes" (what a stream stopped half way leaves) is never taken for "all".
         */
        fun isFullyCached(cache: Cache, key: String): Boolean {
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            return length > 0 && cache.isCached(key, 0, length)
        }
    }
}

/**
 * The links handed out so far, reused until they expire. Media3 opens a data source for every
 * seek; asking the server each time would cost a round trip per scrub.
 *
 * Also a Media3 [ResolvingDataSource.Resolver], for code that wants the plain Media3 chain;
 * [PlaybackDataSource] uses [link] directly, since it also renews links and reroutes storage.
 */
@UnstableApi
class PlaybackLinks(
    private val resolver: PlaybackLinkResolver,
    private val now: () -> Long = System::currentTimeMillis,
    /** A link this close to expiry is renewed before use rather than failing half way through. */
    private val expiryMarginMs: Long = 60_000,
) : ResolvingDataSource.Resolver {
    private val links = HashMap<PlaybackLinkRequest, PlaybackLink>()

    /** The link for [request]; [renew] asks the source again even when one is held. Blocks. */
    @Throws(IOException::class)
    fun link(request: PlaybackLinkRequest, renew: Boolean = false): PlaybackLink {
        if (!renew) {
            synchronized(links) { links[request] }?.let { held -> if (!isExpired(held)) return held }
        }
        val fresh = try {
            runBlocking { resolver.resolve(request) }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("No playback link for ${request.mediaId}", error)
        }
        synchronized(links) { links[request] = fresh }
        return fresh
    }

    fun forget(request: PlaybackLinkRequest) {
        synchronized(links) { links.remove(request) }
    }

    private fun isExpired(link: PlaybackLink): Boolean {
        val expires = link.expiresAtMs ?: return false
        return now() >= expires - expiryMarginMs
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val request = PlaybackUris.parse(dataSpec.uri) ?: return dataSpec
        return dataSpec.withUri(Uri.parse(link(request).url))
    }
}
