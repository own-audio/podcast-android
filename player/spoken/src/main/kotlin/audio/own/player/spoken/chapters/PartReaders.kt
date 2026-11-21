package audio.own.player.spoken.chapters

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import audio.own.player.core.PlaybackLinks
import audio.own.player.core.PlaybackUris
import audio.own.player.spoken.SpokenPart
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Opens a part for the chapter readers: a file on the phone directly, a stream through its link
 * (asked for as the player would) with Range requests on the bare client.
 */
@UnstableApi
class PartReaders(private val links: PlaybackLinks, private val bareClient: OkHttpClient) {
    suspend fun open(part: SpokenPart): ByteRangeReader? {
        if (part.isHls || part.isUnresolved) return null
        val uri = Uri.parse(part.uri)
        return when (uri.scheme) {
            "file" -> uri.path?.let { BlockCachingRangeReader(FileRangeReader(File(it))) }
            PlaybackUris.SCHEME -> {
                val request = PlaybackUris.parse(uri) ?: return null
                val link = withContext(Dispatchers.IO) { runCatching { links.link(request) }.getOrNull() } ?: return null
                BlockCachingRangeReader(HttpRangeReader(bareClient, link.url))
            }
            "http", "https" -> BlockCachingRangeReader(HttpRangeReader(bareClient, part.uri))
            else -> null
        }
    }
}
