package audio.own.kit.audiobookshelf

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Podcast libraries. A show is a library item whose media is a podcast; its episodes are the
// ones the server holds (downloaded from the feed, or found in the folder). A show with a
// `feedUrl` has more episodes in its feed than on the server.

@Serializable
data class PodcastItem(
    val id: String,
    val libraryId: String,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val isMissing: Boolean = false,
    val media: PodcastMedia,
)

@Serializable
data class PodcastMedia(
    val metadata: PodcastMetadata,
    val coverPath: String? = null,
    val tags: List<String> = emptyList(),
    /** On lists; the detail carries [episodes]. */
    val numEpisodes: Int? = null,
    val episodes: List<PodcastEpisode>? = null,
    /** The server downloads new episodes by itself. */
    val autoDownloadEpisodes: Boolean = false,
    val maxEpisodesToKeep: Int? = null,
)

/** A show's metadata; the title and author are repaired ([Mojibake]) as they are read. */
@Serializable(with = PodcastMetadataSerializer::class)
data class PodcastMetadata(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    val imageUrl: String? = null,
    val itunesPageUrl: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    /** `episodic` or `serial`. */
    val type: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
private data class PodcastMetadataWire(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    val imageUrl: String? = null,
    val itunesPageUrl: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    val type: String? = null,
    val genres: List<String> = emptyList(),
)

internal object PodcastMetadataSerializer : KSerializer<PodcastMetadata> {
    private val wire = PodcastMetadataWire.serializer()
    override val descriptor = wire.descriptor

    override fun deserialize(decoder: Decoder): PodcastMetadata {
        val w = decoder.decodeSerializableValue(wire)
        return PodcastMetadata(
            title = Mojibake.repair(w.title ?: ""), author = Mojibake.repair(w.author), description = w.description,
            feedUrl = w.feedUrl, imageUrl = w.imageUrl, itunesPageUrl = w.itunesPageUrl, language = w.language,
            explicit = w.explicit, type = w.type, genres = w.genres,
        )
    }

    override fun serialize(encoder: Encoder, value: PodcastMetadata) = encoder.encodeSerializableValue(
        wire,
        PodcastMetadataWire(
            value.title, value.author, value.description, value.feedUrl, value.imageUrl, value.itunesPageUrl,
            value.language, value.explicit, value.type, value.genres,
        ),
    )
}

/** One episode the server holds; the title is repaired ([Mojibake]) as it is read. */
@Serializable(with = PodcastEpisodeSerializer::class)
data class PodcastEpisode(
    val id: String,
    val libraryItemId: String? = null,
    val title: String,
    val description: String? = null,
    /** Milliseconds since 1970; servers that scanned a folder often have only a year. */
    val publishedAt: Long? = null,
    /** Seconds. */
    val duration: Double = 0.0,
    val size: Long? = null,
    /** The feed's GUID, when the episode came from the feed. */
    val guid: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val episodeType: String? = null,
    val enclosure: Enclosure? = null,
    val audioFile: AudioFile? = null,
    val chapters: List<Chapter> = emptyList(),
    val addedAt: Long? = null,
) {
    @Serializable
    data class Enclosure(
        val url: String,
        val type: String? = null,
        val length: String? = null,
    )
}

@Serializable
private data class PodcastEpisodeWire(
    val id: String,
    val libraryItemId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val publishedAt: Long? = null,
    val duration: Double = 0.0,
    val size: Long? = null,
    val guid: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val season: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val episode: String? = null,
    val episodeType: String? = null,
    val enclosure: PodcastEpisode.Enclosure? = null,
    val audioFile: AudioFile? = null,
    val chapters: List<Chapter> = emptyList(),
    val addedAt: Long? = null,
)

internal object PodcastEpisodeSerializer : KSerializer<PodcastEpisode> {
    private val wire = PodcastEpisodeWire.serializer()
    override val descriptor = wire.descriptor

    override fun deserialize(decoder: Decoder): PodcastEpisode {
        val w = decoder.decodeSerializableValue(wire)
        return PodcastEpisode(
            id = w.id, libraryItemId = w.libraryItemId, title = Mojibake.repair(w.title ?: ""), description = w.description,
            publishedAt = w.publishedAt, duration = w.duration, size = w.size, guid = w.guid, season = w.season,
            episode = w.episode, episodeType = w.episodeType, enclosure = w.enclosure, audioFile = w.audioFile,
            chapters = w.chapters, addedAt = w.addedAt,
        )
    }

    override fun serialize(encoder: Encoder, value: PodcastEpisode) = encoder.encodeSerializableValue(
        wire,
        PodcastEpisodeWire(
            value.id, value.libraryItemId, value.title, value.description, value.publishedAt, value.duration, value.size,
            value.guid, value.season, value.episode, value.episodeType, value.enclosure, value.audioFile, value.chapters,
            value.addedAt,
        ),
    )
}

/** `GET /api/search/podcast`: the iTunes directory, asked through the server. */
@Serializable
data class PodcastSearchResult(
    val id: Long? = null,
    val title: String,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val cover: String? = null,
    val genres: List<String>? = null,
)

suspend fun AudiobookshelfClient.podcastLibraries(): List<Library> =
    libraries().filter { it.mediaType == "podcast" }

/** A page of shows, by title by default. */
suspend fun AudiobookshelfClient.podcasts(
    libraryId: String,
    page: Int = 0,
    limit: Int = 200,
    sort: String = "media.metadata.title",
    descending: Boolean = false,
): Page<PodcastItem> = get(
    "api/libraries/$libraryId/items",
    Page.serializer(PodcastItem.serializer()),
    mapOf("page" to page.toString(), "limit" to limit.toString(), "sort" to sort, "desc" to if (descending) "1" else "0", "minified" to "1"),
)

suspend fun AudiobookshelfClient.allPodcasts(libraryId: String): List<PodcastItem> {
    val out = mutableListOf<PodcastItem>()
    var page = 0
    while (true) {
        val answer = podcasts(libraryId, page = page)
        out += answer.results
        if (!answer.hasMore || answer.results.isEmpty()) return out
        page++
    }
}

/** A show with the episodes the server holds. */
suspend fun AudiobookshelfClient.podcast(id: String): PodcastItem =
    get("api/items/$id", PodcastItem.serializer(), mapOf("expanded" to "1"))

suspend fun AudiobookshelfClient.episodeProgress(itemId: String, episodeId: String): MediaProgress? = try {
    get("api/me/progress/$itemId/$episodeId", MediaProgress.serializer())
} catch (e: AudiobookshelfError.Http) {
    if (e.status == 404) null else throw e
}

suspend fun AudiobookshelfClient.updateEpisodeProgress(itemId: String, episodeId: String, update: ProgressUpdate) =
    send("PATCH", "api/me/progress/$itemId/$episodeId", body = AbsJson.encodeToJsonElement(ProgressUpdate.serializer(), update))

/** A play session for one episode, so the server counts the listening. */
suspend fun AudiobookshelfClient.playEpisode(
    itemId: String,
    episodeId: String,
    device: DeviceInfo,
    supportedMimeTypes: List<String> = DEFAULT_SUPPORTED_MIME_TYPES,
    forceTranscode: Boolean = false,
): PlaybackSession = call(
    "POST", "api/items/$itemId/play/$episodeId", PlaybackSession.serializer(),
    body = playBody(device, supportedMimeTypes, forceTranscode),
)

suspend fun AudiobookshelfClient.searchPodcasts(term: String): List<PodcastSearchResult> =
    get("api/search/podcast", ListSerializer(PodcastSearchResult.serializer()), mapOf("term" to term))

/** The folder path a new show gets: the folder plus the title without characters a file system refuses. */
internal fun podcastPath(folder: Library.Folder, title: String): String {
    val safe = title.replace(Regex("""[/\\:*?"<>|]"""), "").trim()
    return if (folder.fullPath.endsWith("/")) folder.fullPath + safe else folder.fullPath + "/" + safe
}

/**
 * Subscribes the server to a feed, into [folder] of the library. Needs an account that may
 * upload ([Permissions.upload]); the server makes the show's folder and, with [autoDownload],
 * fetches new episodes. There is deliberately no unsubscribe: on Audiobookshelf that deletes
 * the show for everyone on the server.
 */
suspend fun AudiobookshelfClient.subscribe(
    feedUrl: String,
    title: String,
    libraryId: String,
    folder: Library.Folder,
    autoDownload: Boolean = false,
): PodcastItem {
    val body = buildJsonObject {
        put("folderId", folder.id)
        put("libraryId", libraryId)
        putJsonObject("media") {
            put("autoDownloadEpisodes", autoDownload)
            putJsonObject("metadata") {
                put("feedUrl", feedUrl)
                put("title", title)
            }
        }
        put("path", podcastPath(folder, title))
    }
    return call("POST", "api/podcasts", PodcastItem.serializer(), body = body)
}

@Serializable
private class RecentEpisodes(val episodes: List<PodcastEpisode> = emptyList())

/**
 * The library's newest episodes across its shows, newest first: what New Episodes is built
 * from without opening every show.
 */
suspend fun AudiobookshelfClient.recentEpisodes(libraryId: String, limit: Int = 50): List<PodcastEpisode> =
    get("api/libraries/$libraryId/recent-episodes", RecentEpisodes.serializer(), mapOf("limit" to limit.toString(), "page" to "0")).episodes
