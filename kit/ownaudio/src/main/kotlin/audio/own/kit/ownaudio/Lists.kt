@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Playlists, search, the listener's plays.

/** A playlist: its owner's, private or shared with the household. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    /** `private` or `family`. */
    val visibility: String = "private",
    val ownerId: String = "",
    /** Of the entries the caller can see. */
    val trackCount: Long = 0,
    val durationMs: Long = 0,
    val image: String? = null,
    /** Made by the server (a mix), not by a listener. */
    val generated: Boolean = false,
    /** A generated playlist the listener chose to keep. */
    val kept: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** One place in a playlist, with its own id: a track can be in a playlist twice. */
@Serializable
data class PlaylistEntry(val id: String, val position: Int, val addedAt: Instant, val track: Track)

@Serializable
data class SearchAnswer(
    val artists: List<Contributor> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val contributors: List<Contributor> = emptyList(),
    val books: List<Book> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val included: Included? = null,
)

/** How often and when the caller played a track or an album. */
@Serializable
data class Played(val kind: String, val id: String, val playCount: Long, val lastPlayedAt: Instant)

@Serializable
internal data class CreatePlaylistBody(
    val name: String,
    val description: String? = null,
    val visibility: String? = null,
    val trackIds: List<String> = emptyList(),
)

/** `PATCH /playlists/{id}`: what is left `null` stays as it is. */
@Serializable
data class PlaylistPatch(
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val kept: Boolean? = null,
)

@Serializable
internal data class TrackIdsBody(val trackIds: List<String>)

@Serializable
internal data class EntryIdsBody(val entryIds: List<String>)

suspend fun OwnAudioClient.playlists(cursor: String? = null, limit: Int? = 500): Page<Playlist> =
    getJson("playlists", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.playlist(id: String): Playlist = getJson("playlists/$id")

/** In order, each with its track; [include] as for tracks. */
suspend fun OwnAudioClient.playlistEntries(id: String, include: List<String> = emptyList(), cursor: String? = null, limit: Int? = 500): Page<PlaylistEntry> =
    getJson("playlists/$id/entries", Query("cursor" to cursor).add("include", include).add("limit", limit))

suspend fun OwnAudioClient.createPlaylist(
    name: String,
    description: String? = null,
    visibility: String? = null,
    trackIds: List<String> = emptyList(),
    idempotencyKey: String? = null,
): Playlist = send("POST", "playlists", CreatePlaylistBody(name, description, visibility, trackIds), idempotencyKey = idempotencyKey)

suspend fun OwnAudioClient.updatePlaylist(id: String, patch: PlaylistPatch): Playlist = send("PATCH", "playlists/$id", patch)

suspend fun OwnAudioClient.renamePlaylist(id: String, name: String): Playlist = updatePlaylist(id, PlaylistPatch(name = name))

suspend fun OwnAudioClient.setPlaylistVisibility(id: String, visibility: String): Playlist = updatePlaylist(id, PlaylistPatch(visibility = visibility))

suspend fun OwnAudioClient.deletePlaylist(id: String) = sendOnly("DELETE", "playlists/$id")

/** Appends in order; tracks the caller can't see are left out. */
suspend fun OwnAudioClient.addToPlaylist(id: String, trackIds: List<String>): Playlist =
    send("POST", "playlists/$id/entries", TrackIdsBody(trackIds))

suspend fun OwnAudioClient.removeFromPlaylist(id: String, entryId: String) = sendOnly("DELETE", "playlists/$id/entries/$entryId")

/** [entryIds] first, in that order; the others after them as they were. */
suspend fun OwnAudioClient.orderPlaylist(id: String, entryIds: List<String>) =
    sendOnly("PUT", "playlists/$id/entries/order", EntryIdsBody(entryIds))

/** @param types `artists`, `albums`, `tracks`, `books`, `podcasts`, `episodes`, `contributors`; all when empty. */
suspend fun OwnAudioClient.search(text: String, types: List<String> = emptyList(), limit: Int? = 20, include: List<String> = emptyList()): SearchAnswer =
    getJson("search", Query("q" to text).add("types", types).add("limit", limit).add("include", include))

/** The caller's own plays of `track`s or `album`s: [order] `recent` or `most` first. */
suspend fun OwnAudioClient.played(kind: ItemKind, order: String = "recent", limit: Int? = 100): Page<Played> =
    getJson("me/played", Query("kind" to kind.wire, "order" to order).add("limit", limit))
