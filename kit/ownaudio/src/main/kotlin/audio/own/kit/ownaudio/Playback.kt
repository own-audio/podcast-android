@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the device plays, for `POST /playback-info`. */
@Serializable
data class PlaybackCapabilities(
    /** Content types played as they are; empty for "anything". */
    val direct: List<String> = emptyList(),
    /** Formats the server may make instead, in order. */
    val transcode: List<String> = listOf("aac", "mp3"),
    val maxBitrateKbps: Int? = null,
)

@Serializable
data class PlaybackInfo(
    /** `direct` (the original) or `transcode`. */
    val decision: String,
    /** Signed: plays without a header until [expiresAt]. Never build one by hand. */
    val url: String,
    val expiresAt: Instant,
    val contentType: String? = null,
    val bitrateKbps: Int? = null,
    val reasons: List<String> = emptyList(),
    /** The original file's audio, when known. */
    val source: SourceAudio? = null,
) {
    @Serializable
    data class SourceAudio(
        val contentType: String,
        val codec: String? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
        val channels: Int? = null,
    )
}

@Serializable
internal data class PlaybackInfoBody(val kind: String, val id: String, val capabilities: PlaybackCapabilities, val startMs: Long? = null)

/**
 * How to play a `track`, a `book_file` or an `episode`: the original or a stream the server
 * makes, behind a signed link.
 */
suspend fun OwnAudioClient.playbackInfo(kind: String, id: String, capabilities: PlaybackCapabilities = PlaybackCapabilities(), startMs: Long? = null): PlaybackInfo =
    send("POST", "playback-info", PlaybackInfoBody(kind, id, capabilities, startMs))

/** Picture links from records. */
object OwnAudioImages {
    /** The sizes the server makes. */
    val SIZES = listOf(64, 128, 256, 512, 1024)

    /**
     * A record's signed `image` link at a size: the smallest the server makes that is at least
     * [pixels] on the longest edge. Signed links expire in a week or two: read the record again
     * rather than keeping the link.
     */
    fun sized(link: String, pixels: Int): String {
        val w = SIZES.firstOrNull { it >= pixels } ?: SIZES.last()
        val url = link.toHttpUrlOrNull() ?: return link + (if ('?' in link) "&" else "?") + "w=$w"
        return url.newBuilder().setQueryParameter("w", w.toString()).build().toString()
    }
}
