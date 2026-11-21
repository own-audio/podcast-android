package audio.own.player.spoken.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import audio.own.player.core.PlaybackDataSources
import audio.own.player.core.PlaybackUris
import audio.own.player.spoken.SpokenPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fills the listening cache ahead of the player with Media3's [CacheWriter]: a stretch of the
 * next part near the end of one, and on an unmetered network whole parts. One fetch at a time;
 * a new wish replaces an unfinished one. HLS and files on the phone are never fetched.
 *
 * Also measures "N min saved": how much of the book ahead of the playhead is on disk.
 */
@UnstableApi
class Prefetcher(private val dataSources: PlaybackDataSources, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var writer: CacheWriter? = null
    private var current: Pair<String, Long>? = null

    /** Keeps [part] from byte [fromMs] (as a share of its duration) for [spanMs], or to the end with null. */
    fun fetch(part: SpokenPart, fromMs: Long = 0, spanMs: Long? = null) {
        val cacheFactory = dataSources.cacheDataSourceFactory ?: return
        if (part.isHls || !part.uri.startsWith(PlaybackUris.SCHEME)) return
        val wish = part.uri to fromMs
        if (current == wish && job?.isActive == true) return
        cancel()
        current = wish
        job = scope.launch(Dispatchers.IO) {
            try {
                val bytesPerMs = bytesPerMs(part)
                val start = if (bytesPerMs != null) (fromMs * bytesPerMs).toLong() else 0L
                val length = if (spanMs != null) ((bytesPerMs ?: DEFAULT_BYTES_PER_MS) * spanMs).toLong() else C.LENGTH_UNSET.toLong()
                val spec = DataSpec.Builder().setUri(Uri.parse(part.uri)).setPosition(start).setLength(length).build()
                val source = cacheFactory.createDataSource() as CacheDataSource
                CacheWriter(source, spec, null, null).also { writer = it }.cache()
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                // A fetch that fails is tried again at the next wish; the player reads for itself.
            }
        }
    }

    fun cancel() {
        writer?.cancel()
        writer = null
        job?.cancel()
        job = null
        current = null
    }

    /**
     * Milliseconds of [part] on disk from [offsetMs] on, without a gap; null when unknown (the
     * part was never opened, so its size isn't known yet).
     */
    fun cachedAheadMs(part: SpokenPart, offsetMs: Long): Long? {
        val cache = dataSources.cache ?: return null
        if (part.isHls) return null
        if (part.uri.startsWith("file:")) return (part.durationMs - offsetMs).coerceAtLeast(0)
        val key = keyOf(part) ?: return null
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || part.durationMs <= 0) return null
        val bytesPerMs = length.toDouble() / part.durationMs
        val from = (offsetMs * bytesPerMs).toLong().coerceIn(0, length)
        val cached = cache.getCachedLength(key, from, length - from)
        if (cached <= 0) return 0
        return (cached / bytesPerMs).toLong()
    }

    /** Whether the whole of [part] is on disk. */
    fun isFullyCached(part: SpokenPart): Boolean {
        val request = PlaybackUris.parse(Uri.parse(part.uri)) ?: return part.uri.startsWith("file:")
        return dataSources.isFullyCached(request)
    }

    private fun keyOf(part: SpokenPart): String? {
        val request = PlaybackUris.parse(Uri.parse(part.uri)) ?: return null
        return dataSources.cacheKeyFactory.buildCacheKey(DataSpec(PlaybackUris.forRequest(request)))
    }

    private fun bytesPerMs(part: SpokenPart): Double? {
        val cache = dataSources.cache ?: return null
        val key = keyOf(part) ?: return null
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || part.durationMs <= 0) return null
        return length.toDouble() / part.durationMs
    }

    private companion object {
        // 128 kbps, for a part never opened: a fair guess for speech, too much rather than too little.
        const val DEFAULT_BYTES_PER_MS = 16.0
    }
}
