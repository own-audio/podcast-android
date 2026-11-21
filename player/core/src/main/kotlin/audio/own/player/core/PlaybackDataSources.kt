package audio.own.player.core

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import okhttp3.OkHttpClient

/** The listening cache: songs and episodes as they were streamed, kept until space runs out. */
@UnstableApi
object ListeningCache {
    const val DEFAULT_MAX_BYTES = 1L shl 30

    /**
     * Opens the cache in `cacheDir/listening`. Media3 allows one [SimpleCache] per folder per
     * process: open it once (in the Application or the service) and share it.
     */
    fun open(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES, dir: File = File(context.cacheDir, "listening")): SimpleCache =
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(context))
}

/**
 * The data source chain every player of the app uses:
 *
 * ```
 * DefaultDataSource          file:// and content:// (downloads) are read directly
 *   └ CacheDataSource         placeholders, keyed by media id (PlaybackCacheKeyFactory)
 *       └ PlaybackDataSource  link from the resolver, renewal, storage route
 *           └ OkHttpDataSource on the bare client
 * ```
 *
 * The cache sits above the resolver on purpose: a song in the cache plays without asking the
 * server for a link, so it plays with no network at all.
 */
@UnstableApi
class PlaybackDataSources(
    context: Context,
    resolver: PlaybackLinkResolver,
    /** The bare OkHttp client (no Authorization). */
    httpClient: OkHttpClient,
    /** The listening cache; null streams without caching. */
    val cache: Cache?,
    val proxyMemory: ProxyMemory = ProxyMemory(),
    userAgent: String? = null,
    now: () -> Long = System::currentTimeMillis,
) {
    val links = PlaybackLinks(resolver, now)

    /** Straight to the network: what downloads use (they write their own files). */
    val linkDataSourceFactory: DataSource.Factory =
        PlaybackDataSource.Factory(PlaybackDataSource.httpFactory(httpClient, userAgent), links, proxyMemory)

    val cacheKeyFactory = PlaybackCacheKeyFactory(cache)

    /** Through the listening cache, for prefetching (`CacheWriter`); null without a cache. */
    val cacheDataSourceFactory: CacheDataSource.Factory? = cache?.let {
        CacheDataSource.Factory()
            .setCache(it)
            .setUpstreamDataSourceFactory(linkDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            // A cache that fails to read (a full disk, a removed file) falls back to the network.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** What the player reads through. */
    val dataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(context, cacheDataSourceFactory ?: linkDataSourceFactory)

    fun mediaSourceFactory(): MediaSource.Factory = DefaultMediaSourceFactory(dataSourceFactory)

    /** Whether the whole of [request] is in the listening cache (a crossfade needs that). */
    fun isFullyCached(request: PlaybackLinkRequest): Boolean {
        val cache = cache ?: return false
        val key = cacheKeyFactory.buildCacheKey(DataSpec(PlaybackUris.forRequest(request)))
        return PlaybackCacheKeyFactory.isFullyCached(cache, key)
    }
}
