@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// The records of own.audio's `/api/v2`. Field names follow the API (snake_case on the wire);
// durations and positions are milliseconds; ids are UUID strings. A field the server added
// later has a default, so an older server's answer still reads.

/** The access and refresh tokens of one signed-in account. Kept by the app between launches. */
@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    /** When the access token stops working; refreshed a minute before. */
    val expiresAt: Instant,
    val scope: String = "",
)

@Serializable
internal data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val scope: String = "",
    val tokenType: String = "Bearer",
) {
    fun tokens(now: Instant): Tokens = Tokens(accessToken, refreshToken, now.plusSeconds(expiresIn), scope)
}

/** What a listener's own state is about. */
@Serializable(with = ItemKind.Serializer::class)
enum class ItemKind(val wire: String) {
    TRACK("track"), ALBUM("album"), CONTRIBUTOR("contributor"), BOOK("book"), EPISODE("episode"),
    PODCAST("podcast"), PLAYLIST("playlist"), UNKNOWN("unknown");

    internal object Serializer : TolerantEnumSerializer<ItemKind>("ItemKind", entries.toTypedArray(), UNKNOWN, { it.wire })

    companion object {
        fun of(wire: String): ItemKind = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** A page of a list; [next] is absent on the last one. Pass it back as `cursor`. */
@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val included: Included? = null,
)

/** Records a list or record refers to, sent once (`include=`). */
@Serializable
data class Included(
    val contributors: List<Contributor> = emptyList(),
    val albums: List<Album> = emptyList(),
    val releaseGroups: List<ReleaseGroup> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val books: List<Book> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val contributorsById: Map<String, Contributor> get() = contributors.associateBy { it.id }
    val albumsById: Map<String, Album> get() = albums.associateBy { it.id }
    val genresById: Map<String, Genre> get() = genres.associateBy { it.id }
    val podcastsById: Map<String, Podcast> get() = podcasts.associateBy { it.id }

    operator fun plus(other: Included?): Included = if (other == null) this else Included(
        contributors = (contributors + other.contributors).distinctBy { it.id },
        albums = (albums + other.albums).distinctBy { it.id },
        releaseGroups = (releaseGroups + other.releaseGroups).distinctBy { it.id },
        genres = (genres + other.genres).distinctBy { it.id },
        podcasts = (podcasts + other.podcasts).distinctBy { it.id },
        episodes = (episodes + other.episodes).distinctBy { it.id },
        books = (books + other.books).distinctBy { it.id },
        tracks = (tracks + other.tracks).distinctBy { it.id },
    )
}

/** A record with the records it refers to (`GET /albums/{id}?include=…`). */
data class WithIncluded<T>(val record: T, val included: Included?)

@Serializable
data class ImageCredit(
    val author: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val source: String? = null,
    val sourceUrl: String? = null,
)

/** An artist, author, narrator or host. */
@Serializable
data class Contributor(
    val id: String,
    val name: String,
    val sortName: String? = null,
    /** `person`, `group`, … */
    val kind: String = "person",
    val musicbrainzArtistId: String? = null,
    /** A signed link; see [OwnAudioImages.sized]. */
    val image: String? = null,
    val imageCredit: ImageCredit? = null,
    val bio: String? = null,
    val roles: List<String>? = null,
)

/** Who did what on an item, in order. */
@Serializable
data class Credit(
    val contributorId: String,
    /** `artist`, `featured`, `author`, `narrator`, `host`… */
    val role: String,
    val position: Int = 0,
    val creditedAs: String? = null,
    /** What follows this name in the full credit line: "; ", " feat. ", or nothing. */
    val joinPhrase: String = "",
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val credits: List<Credit> = emptyList(),
    val releaseGroupId: String? = null,
    val releaseDate: String? = null,
    val country: String? = null,
    val label: String? = null,
    val barcode: String? = null,
    val isCompilation: Boolean = false,
    val musicbrainzReleaseId: String? = null,
    val trackCount: Long = 0,
    val durationMs: Long = 0,
    val discCount: Int = 1,
    val image: String? = null,
    val createdAt: Instant,
)

@Serializable
data class ReleaseGroup(
    val id: String,
    val title: String,
    /** `album`, `single`, `ep`, `live`, `compilation`, … */
    val kind: String,
    val firstReleaseDate: String? = null,
    val musicbrainzReleaseGroupId: String? = null,
    val albumIds: List<String>? = null,
)

@Serializable
data class Genre(val id: String, val name: String)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val albumId: String? = null,
    val recordingId: String? = null,
    val credits: List<Credit> = emptyList(),
    val genreIds: List<String> = emptyList(),
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    /** `private` or `family`. */
    val visibility: String = "private",
    val ownerId: String = "",
    val source: String? = null,
    val path: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val image: String? = null,
    /** Chapter marks in its file (a concert's songs); `GET /tracks/{id}` lists them. */
    val chapterCount: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Where a part of a track begins. */
@Serializable
data class TrackChapter(val position: Int, val title: String, val startMs: Long)

/** `GET /tracks/{id}`: the track, its chapters and what it refers to. */
data class TrackDetail(val track: Track, val chapters: List<TrackChapter>, val included: Included?)

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val musicbrainzRecordingId: String,
    val isrc: String? = null,
    val trackIds: List<String> = emptyList(),
)

@Serializable
data class Visibility(
    /** `private` or `family`. */
    val visibility: String,
)

/** One item's answer in a batch: `ok`, `duplicate`, `stale`, or an error code. */
@Serializable
data class ItemResult(val clientId: String, val status: String, val detail: String? = null)

@Serializable
internal data class ProblemBody(
    val status: Int? = null,
    val code: String? = null,
    /** OAuth's name for the code, on the token endpoint. */
    val error: String? = null,
    val detail: String? = null,
    val errorDescription: String? = null,
    val mfaToken: String? = null,
    val mfaMethods: List<String> = emptyList(),
    val feature: String? = null,
    val errors: List<FieldError> = emptyList(),
)
