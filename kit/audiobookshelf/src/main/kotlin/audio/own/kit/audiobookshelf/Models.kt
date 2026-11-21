package audio.own.kit.audiobookshelf

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Audiobookshelf's records as a client sees them (2.x, shapes checked against 2.37.1). The API
// is camelCase; durations and positions are seconds as floating point; timestamps are
// milliseconds since 1970. Unknown fields are ignored, and fields whose type has varied between
// server versions (`ino`, `id`, `sequence`) decode from a number or a string.

/** `GET /status`: what a server is, before signing in. */
@Serializable
data class ServerStatus(
    val app: String,
    val serverVersion: String,
    val isInit: Boolean = false,
    val language: String? = null,
    /** `local`, `openid`. */
    val authMethods: List<String> = emptyList(),
    val authFormData: AuthForm? = null,
) {
    @Serializable
    data class AuthForm(
        val authLoginCustomMessage: String? = null,
        val authOpenIDButtonText: String? = null,
        val authOpenIDAutoLaunch: Boolean? = null,
    )

    val version: ServerVersion get() = ServerVersion(serverVersion)
}

/** `2.37.1` as something that compares; `2.26` and `2.26.0` are equal. */
class ServerVersion(text: String) : Comparable<ServerVersion> {
    val parts: List<Int> = text.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    override fun compareTo(other: ServerVersion): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val x = parts.getOrElse(i) { 0 }
            val y = other.parts.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ServerVersion && compareTo(other) == 0
    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()
    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** The first version with one-hour JWT access tokens and rotating refresh tokens. */
        val ROTATING_TOKENS = ServerVersion("2.26.0")
    }
}

/**
 * What `POST /login` and `POST /api/authorize` answer: the account with its progress and
 * bookmarks, the tokens, the server's settings.
 */
data class SignIn(
    val user: User,
    val tokens: Tokens,
    val defaultLibraryId: String?,
    val serverVersion: ServerVersion?,
)

@Serializable
internal data class SignInAnswer(
    val user: User,
    val userDefaultLibraryId: String? = null,
    val serverSettings: ServerSettings? = null,
) {
    @Serializable
    data class ServerSettings(val version: String? = null)

    fun signIn(): SignIn {
        val access = user.accessToken
        val legacy = user.token
        val tokens = when {
            !access.isNullOrEmpty() ->
                Tokens(access, user.refreshToken, Tokens.expiryOfJwt(access) ?: Tokens.NEVER)
            !legacy.isNullOrEmpty() -> Tokens(legacy, null, Tokens.NEVER)
            else -> throw AudiobookshelfError.Decoding
        }
        // The tokens live in [SignIn.tokens] only, so a user record that is kept or printed
        // carries none.
        val bare = user.copy(token = null, accessToken = null, refreshToken = null)
        return SignIn(bare, tokens, userDefaultLibraryId, serverSettings?.version?.let(::ServerVersion))
    }
}

@Serializable
data class User(
    val id: String,
    val username: String,
    /** `root`, `admin`, `user`, `guest`. */
    val type: String = "user",
    val email: String? = null,
    internal val token: String? = null,
    internal val accessToken: String? = null,
    internal val refreshToken: String? = null,
    val mediaProgress: List<MediaProgress> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val permissions: Permissions = Permissions(),
    /** Empty when the account reaches every library. */
    val librariesAccessible: List<String> = emptyList(),
    val lastSeen: Long? = null,
)

@Serializable
data class Permissions(
    val download: Boolean = true,
    val update: Boolean = false,
    val delete: Boolean = false,
    val upload: Boolean = false,
    val accessAllLibraries: Boolean = true,
    val accessExplicitContent: Boolean = true,
)

/** One user's position in one item; positions in seconds across the whole book. */
@Serializable
data class MediaProgress(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val duration: Double = 0.0,
    /** 0–1. */
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    /** Milliseconds since 1970. */
    val lastUpdate: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** A bookmark is keyed by its time: there is no id, and changing the time means delete + create. */
@Serializable
data class Bookmark(
    val libraryItemId: String,
    val time: Double,
    val title: String,
    val createdAt: Long = 0,
)

@Serializable
data class Library(
    val id: String,
    val name: String,
    /** `book` or `podcast`. */
    val mediaType: String,
    val displayOrder: Int? = null,
    val icon: String? = null,
    /** The folders a new item can go into (subscribing to a podcast names one). */
    val folders: List<Folder>? = null,
) {
    @Serializable
    data class Folder(val id: String, val fullPath: String)
}

@Serializable
internal data class LibrariesAnswer(val libraries: List<Library>)

/** A page of a library's items, series or collections. */
@Serializable
data class Page<T>(
    val results: List<T>,
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
) {
    val hasMore: Boolean get() = limit > 0 && (page + 1) * limit < total
}

/**
 * A library item: a book. Lists carry the strings (`authorName`, `seriesName`); the detail
 * (`expanded=1`) carries the linked records, files, tracks and chapters.
 */
@Serializable
data class LibraryItem(
    val id: String,
    @Serializable(with = FlexibleStringSerializer::class) val ino: String? = null,
    val libraryId: String,
    val path: String? = null,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val isMissing: Boolean = false,
    val isInvalid: Boolean = false,
    val mediaType: String = "book",
    val media: BookMedia,
    /** On a list filtered or sorted by a series. */
    @Serializable(with = FlexibleStringSerializer::class) val sequence: String? = null,
    /** On a detail asked for with `include=progress`. */
    val userMediaProgress: MediaProgress? = null,
)

@Serializable
data class BookMedia(
    val id: String? = null,
    val metadata: BookMetadata,
    val coverPath: String? = null,
    val tags: List<String> = emptyList(),
    val numTracks: Int? = null,
    val numChapters: Int? = null,
    /** Seconds. */
    val duration: Double = 0.0,
    val size: Long? = null,
    /** Only with `expanded=1`. */
    val chapters: List<Chapter>? = null,
    val tracks: List<AudioTrack>? = null,
    val audioFiles: List<AudioFile>? = null,
)

/**
 * The strings come on lists (`authorName`, `narratorName`, `seriesName` like "Name #1"); the
 * records (`authors`, `narrators`, `series`) on a detail.
 */
@Serializable
data class BookMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val authors: List<Author>? = null,
    val narrators: List<String>? = null,
    @Serializable(with = SeriesEntriesSerializer::class) val series: List<SeriesEntry>? = null,
    val genres: List<String> = emptyList(),
    @Serializable(with = FlexibleStringSerializer::class) val publishedYear: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    val abridged: Boolean = false,
)

@Serializable
data class Author(
    val id: String,
    val name: String,
    val description: String? = null,
    val imagePath: String? = null,
    val numBooks: Int? = null,
)

/** A book's place in a series; `sequence` is a string such as "1.5" or "1-3". */
@Serializable
data class SeriesEntry(
    val id: String,
    val name: String,
    @Serializable(with = FlexibleStringSerializer::class) val sequence: String? = null,
)

/**
 * From `GET /api/libraries/{id}/series`: the series with its books, each carrying `sequence`.
 * `GET /api/series/{id}` answers without `books`; those come from the items list filtered by
 * the series.
 */
@Serializable
data class Series(
    val id: String,
    val name: String,
    val nameIgnorePrefix: String? = null,
    val description: String? = null,
    val addedAt: Long? = null,
    val books: List<LibraryItem> = emptyList(),
)

/** Narrators have no ids on the server; `id` is a base64 form of the name, good for one call. */
@Serializable
data class Narrator(
    val id: String,
    val name: String,
    val numBooks: Int? = null,
)

/** A collection of books, made by an admin on the server. */
@Serializable
data class BookCollection(
    val id: String,
    val libraryId: String,
    val name: String,
    val description: String? = null,
    val books: List<LibraryItem> = emptyList(),
)

/** A chapter, in seconds across the whole book. */
@Serializable
data class Chapter(
    val id: Int,
    val start: Double,
    val end: Double,
    val title: String,
)

/** One playable file of a book, in order; `startOffset` is where it begins in book time. */
@Serializable
data class AudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    /** `/api/items/{id}/file/{ino}`, or an HLS playlist in a transcoding session. */
    val contentUrl: String,
    val mimeType: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val ino: String? = null,
    val codec: String? = null,
    val bitRate: Int? = null,
)

@Serializable
data class AudioFile(
    @Serializable(with = FlexibleStringSerializer::class) val ino: String? = null,
    val index: Int? = null,
    val duration: Double? = null,
    val mimeType: String? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class Metadata(
        val filename: String? = null,
        val ext: String? = null,
        val size: Long? = null,
    )
}

/**
 * A home-screen shelf from `GET /api/libraries/{id}/personalized`: `continue-listening`,
 * `recently-added`, `discover`, `listen-again`, `newest-authors`, `continue-series`…
 * Entities that don't fit the shelf's type are dropped rather than failing the whole answer.
 */
@Serializable(with = ShelfSerializer::class)
data class Shelf(
    val id: String,
    val label: String,
    /** `book`, `series`, `authors`, `episode`. */
    val type: String,
    val books: List<LibraryItem> = emptyList(),
    val series: List<Series> = emptyList(),
    val authors: List<Author> = emptyList(),
)

internal object ShelfSerializer : KSerializer<Shelf> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("audiobookshelf.Shelf")

    override fun deserialize(decoder: Decoder): Shelf {
        val json = decoder as JsonDecoder
        val o = json.decodeJsonElement() as? JsonObject ?: throw kotlinx.serialization.SerializationException("a shelf is an object")
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: throw kotlinx.serialization.SerializationException("a shelf has an id")
        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: ""
        val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "book"
        val entities = o["entities"]
        val j = json.json
        return when (type) {
            "series" -> Shelf(id, label, type, series = j.decodeOrNull(ListSerializer(Series.serializer()), entities).orEmpty())
            "authors" -> Shelf(id, label, type, authors = j.decodeOrNull(ListSerializer(Author.serializer()), entities).orEmpty())
            "book" -> Shelf(id, label, type, books = j.decodeOrNull(ListSerializer(LibraryItem.serializer()), entities).orEmpty())
            else -> Shelf(id, label, type)
        }
    }

    override fun serialize(encoder: Encoder, value: Shelf) =
        throw UnsupportedOperationException("shelves are only read")
}

/** `GET /api/libraries/{id}/search`. Sections that don't decode are empty. */
@Serializable(with = SearchAnswerSerializer::class)
data class SearchAnswer(
    val book: List<BookHit> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
    val authors: List<Author> = emptyList(),
    val narrators: List<NameHit> = emptyList(),
) {
    @Serializable
    data class BookHit(val libraryItem: LibraryItem, val matchKey: String? = null, val matchText: String? = null)

    @Serializable
    data class SeriesHit(val series: Series, val books: List<LibraryItem> = emptyList())

    @Serializable
    data class NameHit(val name: String, val numBooks: Int? = null)
}

internal object SearchAnswerSerializer : KSerializer<SearchAnswer> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("audiobookshelf.SearchAnswer")

    override fun deserialize(decoder: Decoder): SearchAnswer {
        val json = decoder as JsonDecoder
        val o = json.decodeJsonElement() as? JsonObject ?: throw kotlinx.serialization.SerializationException("a search answer is an object")
        val j = json.json
        // The books must decode (they are what was searched for); the other sections are extras.
        val book = o["book"]?.let { j.decodeFromJsonElement(ListSerializer(SearchAnswer.BookHit.serializer()), it) }.orEmpty()
        return SearchAnswer(
            book = book,
            series = j.decodeOrNull(ListSerializer(SearchAnswer.SeriesHit.serializer()), o["series"]).orEmpty(),
            authors = j.decodeOrNull(ListSerializer(Author.serializer()), o["authors"]).orEmpty(),
            narrators = j.decodeOrNull(ListSerializer(SearchAnswer.NameHit.serializer()), o["narrators"]).orEmpty(),
        )
    }

    override fun serialize(encoder: Encoder, value: SearchAnswer) =
        throw UnsupportedOperationException("search answers are only read")
}

/** What this device is, in play sessions. */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val clientName: String,
    val clientVersion: String,
    /** `Build.MANUFACTURER`. */
    val manufacturer: String,
    /** `Build.MODEL`. */
    val model: String,
    /** `Build.VERSION.SDK_INT`, as text. */
    val sdkVersion: String? = null,
)

/**
 * `POST /api/items/{id}/play`: how to play, and the session that progress reports go to. A new
 * session for the same book closes the open one, so an app keeps one per book.
 */
@Serializable
data class PlaybackSession(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    /** 0 direct play, 1 direct stream, 2 transcode (HLS), 3 local. */
    val playMethod: Int = 0,
    val duration: Double = 0.0,
    val currentTime: Double = 0.0,
    val startTime: Double = 0.0,
    val chapters: List<Chapter> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    val coverPath: String? = null,
    val serverVersion: String? = null,
    val date: String? = null,
    val dayOfWeek: String? = null,
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
) {
    val isHls: Boolean get() = playMethod == PLAY_METHOD_TRANSCODE

    companion object {
        const val PLAY_METHOD_DIRECT = 0
        const val PLAY_METHOD_TRANSCODE = 2
        const val PLAY_METHOD_LOCAL = 3
    }
}

/**
 * A session built on the phone for listening the server didn't see (offline or from a
 * download), sent with `POST /api/session/local-all`. The server moves the progress when the
 * session is newer than what it has. Build one with [LocalSession.of].
 */
@Serializable
data class LocalSession(
    val id: String,
    val userId: String,
    val libraryId: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaType: String = "book",
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    val duration: Double,
    val playMethod: Int = PlaybackSession.PLAY_METHOD_LOCAL,
    val mediaPlayer: String = MEDIA_PLAYER,
    val deviceInfo: DeviceInfo,
    /** `yyyy-MM-dd` of the start, in the listener's time zone. */
    val date: String,
    /** The English weekday of the start (`Thursday`), as the server's statistics expect. */
    val dayOfWeek: String,
    /** Seconds actually listened. */
    val timeListening: Double,
    val startTime: Double,
    val currentTime: Double,
    val startedAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun of(
            id: String,
            userId: String,
            libraryId: String,
            libraryItemId: String,
            displayTitle: String?,
            displayAuthor: String?,
            duration: Double,
            deviceInfo: DeviceInfo,
            timeListening: Double,
            startTime: Double,
            currentTime: Double,
            startedAt: Instant,
            updatedAt: Instant,
            episodeId: String? = null,
            mediaType: String = if (episodeId == null) "book" else "podcast",
            zone: ZoneId = ZoneId.systemDefault(),
        ): LocalSession {
            val local = startedAt.atZone(zone)
            return LocalSession(
                id = id, userId = userId, libraryId = libraryId, libraryItemId = libraryItemId,
                episodeId = episodeId, mediaType = mediaType,
                displayTitle = displayTitle, displayAuthor = displayAuthor, duration = duration,
                deviceInfo = deviceInfo,
                date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(local),
                dayOfWeek = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH).format(local),
                timeListening = timeListening, startTime = startTime, currentTime = currentTime,
                startedAt = startedAt.toEpochMilli(), updatedAt = updatedAt.toEpochMilli(),
            )
        }
    }
}

@Serializable
data class LocalSessionResult(
    val id: String,
    val success: Boolean = false,
    val progressSynced: Boolean? = null,
)

/** What the server is told plays the audio; it shows in its sessions list. */
internal const val MEDIA_PLAYER = "ExoPlayer"
