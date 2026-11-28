@file:UseSerializers(InstantSerializer::class)

package audio.own.podcast.library

import audio.own.kit.feed.FeedEpisode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * A show the listener follows. [id] is the app's own and never changes, though the feed may move
 * (`itunes:new-feed-url`) or the server rename it.
 */
@Serializable
data class Show(
    val id: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    /** Where its episodes come from; null for a show only a server knows. */
    val feedUrl: String? = null,
    val link: String? = null,
    val categories: List<String> = emptyList(),
    /** Heard oldest first. */
    val isSerial: Boolean = false,
    val isExplicit: Boolean = false,
    val followedAt: Instant = Instant.now(),
    val refreshedAt: Instant? = null,
    val settings: ShowSettings = ShowSettings(),
    /** The part of the source it lives in, where a source has parts: an Audiobookshelf library. */
    val collection: String? = null,
    /** The feed's `podcast:guid`: the same show under another address (a moved or personal feed). */
    val podcastGuid: String? = null,
)

/**
 * What the listener chose for one show. Kept on the device for every source, never sent to a
 * server. Every field has a default so settings saved by an older version still load.
 */
@Serializable
data class ShowSettings(
    /** New episodes appear in New Episodes. */
    val inInbox: Boolean = true,
    /** New episodes go to the end of Up Next as they arrive. */
    val autoQueue: Boolean = false,
    /** Seconds skipped at the start of every episode (an intro heard a hundred times); 0 is none. */
    val skipIntroSecs: Double = 0.0,
    /** null: the app's speed. */
    val speed: Float? = null,
    /** Keep the newest this many episodes downloaded; 0 is off. */
    val autoDownload: Int = 0,
    /** A downloaded episode goes once it is played. */
    val deleteWhenPlayed: Boolean = false,
    /** At most this many of the show's episodes on the device, the oldest going first; null is no limit. */
    val keepAtMost: Int? = null,
) {
    /** Whether any rule asks the downloads of this show to change by themselves. */
    val hasDownloadRules: Boolean get() = autoDownload > 0 || deleteWhenPlayed || keepAtMost != null

    companion object {
        val AUTO_DOWNLOAD_CHOICES = listOf(0, 1, 3, 5, 10)
        val KEEP_AT_MOST_CHOICES = listOf(null, 1, 3, 5, 10)
    }
}

@Serializable
data class Episode(
    /** The feed's GUID (or what stands in for it), unique within its show. */
    val id: String,
    val showId: String,
    val title: String,
    val description: String? = null,
    val publishedAt: Instant? = null,
    val durationMs: Long? = null,
    val audioUrl: String? = null,
    val audioType: String? = null,
    val audioBytes: Long? = null,
    val imageUrl: String? = null,
    val link: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    /** `full`, `trailer`, `bonus`. */
    val kind: String? = null,
    val chaptersUrl: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val transcripts: List<Transcript> = emptyList(),
    /** When this device first saw it: what decides whether it is new to the listener. */
    val firstSeenAt: Instant = Instant.now(),
) {
    /** The id the player and the downloads know it by: unique across shows. */
    val key: EpisodeKey get() = EpisodeKey(showId, id)

    companion object {
        /** The feed's episode as this library keeps it. */
        fun from(feed: FeedEpisode, showId: String, firstSeenAt: Instant) = Episode(
            id = feed.id,
            showId = showId,
            title = feed.title,
            description = feed.description,
            publishedAt = feed.publishedAt,
            durationMs = feed.durationMs,
            audioUrl = feed.enclosure?.url,
            audioType = feed.enclosure?.type,
            // Feeds often write 0 for an unknown length.
            audioBytes = feed.enclosure?.length?.takeIf { it > 0 },
            imageUrl = feed.imageUrl,
            link = feed.link,
            season = feed.season,
            number = feed.episode,
            kind = feed.episodeType,
            chaptersUrl = feed.chaptersUrl,
            chapters = feed.inlineChapters.map { Chapter(it.startMs, it.endMs, it.title, it.url, it.imageUrl, it.isInTableOfContents) },
            transcripts = feed.transcripts.map { Transcript(it.url, it.type, it.language) },
            firstSeenAt = firstSeenAt,
        )
    }
}

/** An episode named across shows: [showId] and the feed's id together. */
@Serializable
data class EpisodeKey(val showId: String, val episodeId: String) {
    /** `<showId>|<episodeId>`; show ids are the app's own and never hold a `|`. */
    override fun toString() = "$showId|$episodeId"

    companion object {
        /** The first `|` splits, so an episode id (often a URL) may hold more of them. */
        fun parse(text: String): EpisodeKey? {
            val bar = text.indexOf('|')
            if (bar < 0) return null
            return EpisodeKey(text.substring(0, bar), text.substring(bar + 1))
        }
    }
}

@Serializable
data class Chapter(
    val startMs: Long,
    val endMs: Long? = null,
    val title: String,
    val url: String? = null,
    val imageUrl: String? = null,
    val isInTableOfContents: Boolean = true,
)

@Serializable
data class Transcript(
    val url: String,
    val type: String,
    val language: String? = null,
)

/** Where the listener is in an episode, and whether it is heard. */
@Serializable
data class ListeningProgress(
    val positionMs: Long,
    val played: Boolean,
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        /**
         * The end of an episode is often an outro or an ad: within the last 30 seconds, or past
         * 98 %, it counts as heard.
         */
        fun reachedEnd(positionMs: Long, durationMs: Long?): Boolean {
            if (durationMs == null || durationMs <= 0) return false
            return durationMs - positionMs <= 30_000 || positionMs.toDouble() / durationMs.toDouble() >= 0.98
        }
    }
}

/** ISO-8601, so stored values stay readable and keep every digit an [Instant] has. */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audio.own.podcast.library.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
