@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.serializer

// Podcasts: the shows a household follows and their episodes, as the server keeps them, and the
// server's directory of shows nobody here follows yet.

@Serializable
data class Podcast(
    val id: String,
    val title: String,
    val credits: List<Credit> = emptyList(),
    /** Empty for a show whose episodes its owner uploads. */
    val feedUrl: String = "",
    val description: String? = null,
    val link: String? = null,
    val language: String? = null,
    val podcastGuid: String? = null,
    val categories: List<String> = emptyList(),
    /** A link to the show's artwork. */
    val image: String? = null,
    /** `private` or `family`. */
    val visibility: String = "private",
    val ownerId: String = "",
    /** `feed` or `upload`. */
    val source: String? = null,
    /** The server keeps a copy of each new episode. */
    val autoStore: Boolean = false,
    val lastRefreshedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

@Serializable
data class Episode(
    val id: String,
    val podcastId: String,
    val guid: String = "",
    val title: String,
    val description: String? = null,
    val publishedAt: Instant? = null,
    /** Often missing: the server has not read the file yet. */
    val durationMs: Long? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    /** The publisher's enclosure. */
    val audioUrl: String? = null,
    /** The server keeps a copy of the file. */
    val stored: Boolean = false,
    val path: String? = null,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val sha256: String? = null,
    val image: String? = null,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    val createdAt: Instant? = null,
)

/** How many episodes of a show the listener hasn't played. */
@Serializable
data class Unplayed(val podcastId: String, val unplayed: Long)

@Serializable
data class Transcript(
    /** `vtt`, `srt`, `json`, `text`… as the publisher sent it. */
    val format: String,
    val text: String? = null,
    val segments: List<Segment> = emptyList(),
) {
    @Serializable
    data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)
}

/** A show in the server's directory, before anyone here follows it. */
@Serializable
data class DirectoryShow(
    val title: String,
    val feedUrl: String,
    val link: String? = null,
    val description: String? = null,
    val author: String? = null,
    val image: String? = null,
    val language: String? = null,
    val episodeCount: Int? = null,
    /** Lowercased; may be empty. */
    val categories: List<String> = emptyList(),
    /** Set when the household already follows it. */
    val podcastId: String? = null,
)

/** A directory category; [activeCount] counts the shows that still publish. */
@Serializable
data class DirectoryCategory(val category: String, val feedCount: Long = 0, val activeCount: Long = 0)

@Serializable
data class DirectoryEpisode(
    val guid: String,
    val title: String,
    val description: String? = null,
    val publishedAt: Instant? = null,
    val durationMs: Long? = null,
    val audioUrl: String? = null,
    val image: String? = null,
)

/** A show's page before following it: the show and its latest episodes, read from the feed now. */
@Serializable
data class DirectoryPreview(val show: DirectoryShow, val episodes: List<DirectoryEpisode> = emptyList())

@Serializable
data class Queued(val queued: Long)

@Serializable
data class StoreAnswer(val episodes: Long, val estimatedBytes: Long)

@Serializable
internal data class FollowBody(val feedUrl: String, val visibility: String? = null)

@Serializable
internal data class PodcastPatchBody(val autoStore: Boolean? = null)

@Serializable
internal data class StoreBody(val latest: Long? = null, val preview: Boolean? = null)

// region The library's shows

/** @param sort `title`, `added` or `updated`. */
suspend fun OwnAudioClient.podcasts(
    query: String? = null,
    contributorId: String? = null,
    owner: String? = null,
    visibility: String? = null,
    sort: String? = null,
    include: List<String> = emptyList(),
    cursor: String? = null,
    limit: Int? = null,
): Page<Podcast> = getJson(
    "podcasts",
    Query("q" to query, "contributor_id" to contributorId, "owner" to owner, "visibility" to visibility, "sort" to sort, "cursor" to cursor)
        .add("include", include).add("limit", limit),
)

suspend fun OwnAudioClient.podcast(id: String): Podcast = getJson("podcasts/$id")

/** Newest first. */
suspend fun OwnAudioClient.episodes(podcastId: String, cursor: String? = null, limit: Int? = null): Page<Episode> =
    getJson("podcasts/$podcastId/episodes", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.episode(id: String): Episode = getJson("episodes/$id")

/** Follows a feed: fetched now, its episodes in the library at once, refreshed by the server from then on. */
suspend fun OwnAudioClient.follow(feedUrl: String, visibility: String? = null, idempotencyKey: String? = null): Podcast =
    send("POST", "podcasts", FollowBody(feedUrl, visibility), idempotencyKey = idempotencyKey)

/** Stops following: the show and its episodes leave the library. */
suspend fun OwnAudioClient.unfollow(podcastId: String) = sendOnly("DELETE", "podcasts/$podcastId")

/** Reads one show's feed again; how many were queued. */
suspend fun OwnAudioClient.refresh(podcastId: String): Queued =
    call("POST", "podcasts/$podcastId/refresh", Queued.serializer(), null, Unit.serializer())

/** Reads every followed show's feed again. */
suspend fun OwnAudioClient.refreshAllPodcasts(): Queued =
    call("POST", "podcasts/refresh", Queued.serializer(), null, Unit.serializer())

suspend fun OwnAudioClient.setAutoStore(podcastId: String, on: Boolean): Podcast = send("PATCH", "podcasts/$podcastId", PodcastPatchBody(on))

/** Keeps a copy of the latest [latest] episodes on the server; [preview] only says what it would take. */
suspend fun OwnAudioClient.storeEpisodes(podcastId: String, latest: Long? = null, preview: Boolean = false): StoreAnswer =
    send("POST", "podcasts/$podcastId/store", StoreBody(latest, preview))

/** Keeps a copy of one episode on the server, or lets it go. */
suspend fun OwnAudioClient.setStored(episodeId: String, stored: Boolean): Episode =
    call(if (stored) "PUT" else "DELETE", "episodes/$episodeId/stored", Episode.serializer(), null, Unit.serializer())

suspend fun OwnAudioClient.transcript(episodeId: String): Transcript = getJson("episodes/$episodeId/transcript")

suspend fun OwnAudioClient.setPodcastVisibility(podcastId: String, visibility: String): Visibility =
    send("PUT", "podcasts/$podcastId/visibility", Visibility(visibility))

/** How many episodes of each show the listener hasn't played. */
suspend fun OwnAudioClient.unplayed(cursor: String? = null, limit: Int? = 500): Page<Unplayed> =
    getJson("me/podcasts/unplayed", Query("cursor" to cursor).add("limit", limit))

// endregion

// region The directory (`modules.podcast_discovery` / `podcast_search`)

suspend fun OwnAudioClient.directoryCategories(): Page<DirectoryCategory> = getJson("podcasts/directory/categories")

suspend fun OwnAudioClient.directory(category: String, language: String? = null, cursor: String? = null, limit: Int? = null): Page<DirectoryShow> =
    getJson("podcasts/directory", Query("category" to category, "language" to language, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.directorySearch(query: String, language: String? = null, limit: Int? = null): Page<DirectoryShow> =
    getJson("podcasts/directory/search", Query("q" to query, "language" to language).add("limit", limit))

/** Shows like a followed one ([podcastId]) or a feed ([feedUrl]). */
suspend fun OwnAudioClient.directorySimilar(podcastId: String? = null, feedUrl: String? = null, limit: Int? = null): Page<DirectoryShow> =
    getJson("podcasts/directory/similar", Query("podcast_id" to podcastId, "feed_url" to feedUrl).add("limit", limit))

suspend fun OwnAudioClient.directoryPreview(feedUrl: String, limit: Int? = null): DirectoryPreview =
    getJson("podcasts/directory/preview", Query("feed_url" to feedUrl).add("limit", limit))

// endregion
