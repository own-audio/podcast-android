package audio.own.kit.ownaudio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

// The music catalogue. Lists are cursor-paged: pass `next` from the previous page as `cursor`
// until it is null; `limit` defaults to the server's 100 and goes up to 500.

/** Every page of a list, one after another. */
suspend fun <T> pageAll(page: suspend (cursor: String?) -> Page<T>): List<T> {
    val items = mutableListOf<T>()
    var cursor: String? = null
    do {
        val p = page(cursor)
        items += p.items
        cursor = p.next
    } while (cursor != null)
    return items
}

/** The pages of a list as they arrive. */
fun <T> pages(page: suspend (cursor: String?) -> Page<T>): Flow<Page<T>> = flow {
    var cursor: String? = null
    do {
        val p = page(cursor)
        emit(p)
        cursor = p.next
    } while (cursor != null)
}

suspend fun OwnAudioClient.artists(query: String? = null, cursor: String? = null, limit: Int? = null): Page<Contributor> =
    getJson("artists", Query("q" to query, "cursor" to cursor).add("limit", limit))

/** @param role `artist`, `author`, `narrator`, `host`… */
suspend fun OwnAudioClient.contributors(role: String? = null, query: String? = null, cursor: String? = null, limit: Int? = null): Page<Contributor> =
    getJson("contributors", Query("role" to role, "q" to query, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.contributor(id: String): Contributor = getJson("contributors/$id")

/**
 * @param kind a release kind; `live` for concerts.
 * @param sort `title` or `added` (newest first).
 * @param owner `me` for the caller's own.
 */
suspend fun OwnAudioClient.albums(
    contributorId: String? = null,
    genreId: String? = null,
    releaseGroupId: String? = null,
    kind: String? = null,
    query: String? = null,
    owner: String? = null,
    visibility: String? = null,
    sort: String? = null,
    include: List<String> = emptyList(),
    cursor: String? = null,
    limit: Int? = null,
): Page<Album> = getJson(
    "albums",
    Query(
        "contributor_id" to contributorId, "genre_id" to genreId, "release_group_id" to releaseGroupId, "kind" to kind,
        "q" to query, "owner" to owner, "visibility" to visibility, "sort" to sort, "cursor" to cursor,
    ).add("include", include).add("limit", limit),
)

suspend fun OwnAudioClient.album(id: String, include: List<String> = listOf("contributors")): WithIncluded<Album> {
    val obj = decodeObject(raw("GET", "albums/$id", Query().add("include", include)))
    return WithIncluded(decodeElement(Album.serializer(), obj), obj["included"]?.let { decodeElement(Included.serializer(), it) })
}

/** With [albumId], in disc and track order; otherwise [sort] `title` or `added`. */
suspend fun OwnAudioClient.tracks(
    albumId: String? = null,
    contributorId: String? = null,
    genreId: String? = null,
    recordingId: String? = null,
    query: String? = null,
    owner: String? = null,
    visibility: String? = null,
    sort: String? = null,
    include: List<String> = emptyList(),
    cursor: String? = null,
    limit: Int? = null,
): Page<Track> = getJson(
    "tracks",
    Query(
        "album_id" to albumId, "contributor_id" to contributorId, "genre_id" to genreId, "recording_id" to recordingId,
        "q" to query, "owner" to owner, "visibility" to visibility, "sort" to sort, "cursor" to cursor,
    ).add("include", include).add("limit", limit),
)

/** The track with its chapters (empty when its file has none) and what it refers to. */
suspend fun OwnAudioClient.track(id: String, include: List<String> = listOf("albums", "contributors")): TrackDetail {
    val obj = decodeObject(raw("GET", "tracks/$id", Query().add("include", include)))
    val chapters = (obj["chapters"] as? JsonArray)?.let { decodeElement(ListSerializer(TrackChapter.serializer()), it) }.orEmpty()
    return TrackDetail(
        track = decodeElement(Track.serializer(), obj),
        chapters = chapters,
        included = obj["included"]?.let { decodeElement(Included.serializer(), it) },
    )
}

suspend fun OwnAudioClient.genres(cursor: String? = null, limit: Int? = 500): Page<Genre> =
    getJson("genres", Query("cursor" to cursor).add("limit", limit))

/** A new draw on every call. */
suspend fun OwnAudioClient.randomTracks(genreId: String? = null, limit: Int? = 50, include: List<String> = emptyList()): Page<Track> =
    getJson("tracks/random", Query("genre_id" to genreId).add("limit", limit).add("include", include))

suspend fun OwnAudioClient.releaseGroup(id: String): ReleaseGroup = getJson("release-groups/$id")

suspend fun OwnAudioClient.recording(id: String): Recording = getJson("recordings/$id")

/** Lyrics of a track: as stored, and the lines with their times when synced. */
@Serializable
data class Lyrics(
    /** As stored; null when the track has none. */
    val text: String? = null,
    val synced: Boolean = false,
    val lines: List<Line> = emptyList(),
) {
    @Serializable
    data class Line(val startMs: Long? = null, val text: String)
}

suspend fun OwnAudioClient.lyrics(trackId: String): Lyrics = getJson("tracks/$trackId/lyrics")

/** Shares one of the caller's own tracks with the household (`family`) or not (`private`). */
suspend fun OwnAudioClient.setTrackVisibility(trackId: String, visibility: String): Visibility =
    send("PUT", "tracks/$trackId/visibility", Visibility(visibility))

@Serializable
internal data class ChapterInput(val title: String, val startMs: Long)

@Serializable
internal data class ChaptersBody(val chapters: List<ChapterInput>)

/** A track's chapters in order; empty when its file has none. */
suspend fun OwnAudioClient.trackChapters(id: String): List<TrackChapter> = track(id, include = emptyList()).chapters

/**
 * Replaces a track's chapters (its owner only); they then stay whatever its file says. An empty
 * list removes them. Answers the list as stored.
 */
suspend fun OwnAudioClient.setTrackChapters(id: String, chapters: List<Pair<String, Long>>): List<TrackChapter> =
    send("PUT", "tracks/$id/chapters", ChaptersBody(chapters.map { ChapterInput(it.first, it.second) }))
