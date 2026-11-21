package audio.own.kit.audiobookshelf

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64

// The calls, grouped as the apps use them. Every list is scoped to one library, as the server
// has it.

// ---- The account

/**
 * The signed-in account with its progress and bookmarks, and the server's settings; what the
 * app asks at launch and as its connection check.
 */
suspend fun AudiobookshelfClient.authorize(): SignIn =
    call("POST", "api/authorize", SignInAnswer.serializer(), body = JsonObject(emptyMap())).signIn()

/**
 * The account again: every `mediaProgress` and `bookmarks` row. There is no change feed, so
 * this is how progress made elsewhere is noticed (every few minutes while playing, not more).
 */
suspend fun AudiobookshelfClient.me(): User =
    get("api/me", User.serializer()).copy(token = null, accessToken = null, refreshToken = null)

// ---- Libraries and items

/** Every library the account reaches, of any kind. */
suspend fun AudiobookshelfClient.libraries(): List<Library> =
    get("api/libraries", LibrariesAnswer.serializer()).libraries

/** Every library the account reaches, book libraries only. */
suspend fun AudiobookshelfClient.bookLibraries(): List<Library> =
    libraries().filter { it.mediaType == "book" }

enum class ItemSort(val wire: String) {
    TITLE("media.metadata.title"),
    AUTHOR("media.metadata.authorNameLF"),
    ADDED("addedAt"),
    DURATION("media.duration"),
    PUBLISHED_YEAR("media.metadata.publishedYear"),
    PROGRESS("progress"),

    /** Only with a series filter. */
    SEQUENCE("sequence"),
}

/**
 * `filter=<group>.<base64url(value)>`. URL-safe base64 without padding, because `+` in a query
 * is a space to the server and `/` and `=` would need escaping.
 */
sealed class ItemFilter {
    data class AuthorId(val id: String) : ItemFilter()
    data class NarratorName(val name: String) : ItemFilter()
    data class SeriesId(val id: String) : ItemFilter()
    data class Genre(val name: String) : ItemFilter()
    data class Tag(val name: String) : ItemFilter()
    data class ByProgress(val progress: ProgressState) : ItemFilter()

    enum class ProgressState(val wire: String) {
        FINISHED("finished"),
        IN_PROGRESS("in-progress"),
        NOT_STARTED("not-started"),
        NOT_FINISHED("not-finished"),
    }

    val value: String
        get() = when (this) {
            is AuthorId -> "authors.${encode(id)}"
            is NarratorName -> "narrators.${encode(name)}"
            is SeriesId -> "series.${encode(id)}"
            is Genre -> "genres.${encode(name)}"
            is Tag -> "tags.${encode(name)}"
            is ByProgress -> "progress.${encode(progress.wire)}"
        }

    companion object {
        fun encode(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    }
}

/** A page of a library's books. [collapseSeries] folds a series into one row. */
suspend fun AudiobookshelfClient.items(
    libraryId: String,
    page: Int = 0,
    limit: Int = 100,
    sort: ItemSort = ItemSort.TITLE,
    descending: Boolean = false,
    filter: ItemFilter? = null,
    collapseSeries: Boolean = false,
): Page<LibraryItem> = get(
    "api/libraries/$libraryId/items",
    Page.serializer(LibraryItem.serializer()),
    mapOf(
        "page" to page.toString(), "limit" to limit.toString(), "sort" to sort.wire, "desc" to if (descending) "1" else "0",
        "filter" to filter?.value, "collapseseries" to if (collapseSeries) "1" else null,
    ),
)

/** Every book of a library, page after page. */
suspend fun AudiobookshelfClient.allItems(
    libraryId: String,
    sort: ItemSort = ItemSort.TITLE,
    filter: ItemFilter? = null,
    pageSize: Int = 200,
): List<LibraryItem> {
    val all = mutableListOf<LibraryItem>()
    var page = 0
    while (true) {
        val p = items(libraryId, page = page, limit = pageSize, sort = sort, filter = filter)
        all += p.results
        if (!p.hasMore || p.results.isEmpty()) return all
        page++
    }
}

/** One book with its files, tracks, chapters and the caller's progress. */
suspend fun AudiobookshelfClient.item(id: String): LibraryItem =
    get("api/items/$id", LibraryItem.serializer(), mapOf("expanded" to "1", "include" to "progress"))

/** A page of a library's series, by name, each with its books. */
suspend fun AudiobookshelfClient.seriesPage(libraryId: String, page: Int = 0, limit: Int = 100): Page<Series> =
    get("api/libraries/$libraryId/series", Page.serializer(Series.serializer()), mapOf("page" to page.toString(), "limit" to limit.toString(), "sort" to "name"))

/** One series, without its books: those are [items] filtered by [ItemFilter.SeriesId], sorted by [ItemSort.SEQUENCE]. */
suspend fun AudiobookshelfClient.series(id: String): Series =
    get("api/series/$id", Series.serializer(), mapOf("include" to "progress"))

@Serializable
private class AuthorsAnswer(val authors: List<Author> = emptyList())

@Serializable
private class NarratorsAnswer(val narrators: List<Narrator> = emptyList())

suspend fun AudiobookshelfClient.authors(libraryId: String): List<Author> =
    get("api/libraries/$libraryId/authors", AuthorsAnswer.serializer()).authors

/** Narrators are keyed by name; filter books by one with [ItemFilter.NarratorName]. */
suspend fun AudiobookshelfClient.narrators(libraryId: String): List<Narrator> =
    get("api/libraries/$libraryId/narrators", NarratorsAnswer.serializer()).narrators

suspend fun AudiobookshelfClient.collections(libraryId: String): List<BookCollection> =
    get("api/libraries/$libraryId/collections", Page.serializer(BookCollection.serializer()), mapOf("limit" to "500")).results

/** The home screen's shelves, as the server composes them for this user. */
suspend fun AudiobookshelfClient.personalized(libraryId: String, limit: Int = 10): List<Shelf> =
    get("api/libraries/$libraryId/personalized", ListSerializer(Shelf.serializer()), mapOf("limit" to limit.toString()))

/** One library at a time, as the server has it. */
suspend fun AudiobookshelfClient.search(libraryId: String, query: String, limit: Int = 25): SearchAnswer =
    get("api/libraries/$libraryId/search", SearchAnswer.serializer(), mapOf("q" to query, "limit" to limit.toString()))

// ---- Progress and bookmarks

/** The position in one book, or `null` when there is none. */
suspend fun AudiobookshelfClient.progress(itemId: String): MediaProgress? = try {
    get("api/me/progress/$itemId", MediaProgress.serializer())
} catch (e: AudiobookshelfError.Http) {
    if (e.status == 404) null else throw e
}

/** A position to save, in seconds; [progress] is worked out from the two. */
@Serializable
data class ProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    val hideFromContinueListening: Boolean? = null,
    val libraryItemId: String? = null,
) {
    companion object {
        fun of(currentTime: Double, duration: Double, isFinished: Boolean, hideFromContinueListening: Boolean? = null) =
            ProgressUpdate(
                currentTime = currentTime,
                duration = duration,
                progress = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0,
                isFinished = isFinished,
                hideFromContinueListening = hideFromContinueListening,
            )
    }
}

/** Sets the position outside a play session (a mark-as-finished, a position moved by hand, a reset). */
suspend fun AudiobookshelfClient.updateProgress(itemId: String, update: ProgressUpdate) =
    send("PATCH", "api/me/progress/$itemId", body = AbsJson.encodeToJsonElement(ProgressUpdate.serializer(), update))

/** Several at once, each naming its `libraryItemId`. */
suspend fun AudiobookshelfClient.updateProgress(batch: List<Pair<String, ProgressUpdate>>) {
    val body = batch.map { (itemId, update) -> update.copy(libraryItemId = itemId) }
    send("PATCH", "api/me/progress/batch/update", body = AbsJson.encodeToJsonElement(ListSerializer(ProgressUpdate.serializer()), body))
}

/** Forgets the position; [progressId] is [MediaProgress.id], not the item's. */
suspend fun AudiobookshelfClient.deleteProgress(progressId: String) =
    send("DELETE", "api/me/progress/$progressId")

private fun bookmarkBody(time: Double, title: String) = buildJsonObject {
    put("time", time)
    put("title", title)
}

suspend fun AudiobookshelfClient.createBookmark(itemId: String, time: Double, title: String): Bookmark =
    call("POST", "api/me/item/$itemId/bookmark", Bookmark.serializer(), body = bookmarkBody(time, title))

/** Renames the bookmark at [time]. */
suspend fun AudiobookshelfClient.updateBookmark(itemId: String, time: Double, title: String): Bookmark =
    call("PATCH", "api/me/item/$itemId/bookmark", Bookmark.serializer(), body = bookmarkBody(time, title))

suspend fun AudiobookshelfClient.deleteBookmark(itemId: String, time: Double) =
    send("DELETE", "api/me/item/$itemId/bookmark/${bookmarkTimeInPath(time)}")

/** A bookmark's time as the server matches it in a path: whole seconds without `.0`. */
internal fun bookmarkTimeInPath(time: Double): String =
    if (time == Math.rint(time) && !time.isInfinite()) time.toLong().toString() else time.toString()

// ---- Play sessions

/** What ExoPlayer plays without the server transcoding. */
val DEFAULT_SUPPORTED_MIME_TYPES: List<String> = listOf(
    "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/x-m4b", "audio/aac", "audio/flac", "audio/x-flac",
    "audio/wav", "audio/x-wav", "audio/ogg", "audio/opus", "audio/webm",
)

internal fun playBody(device: DeviceInfo, supportedMimeTypes: List<String>, forceTranscode: Boolean) = buildJsonObject {
    put("deviceInfo", AbsJson.encodeToJsonElement(DeviceInfo.serializer(), device))
    put("forceDirectPlay", false)
    put("forceTranscode", forceTranscode)
    put("mediaPlayer", MEDIA_PLAYER)
    putJsonArray("supportedMimeTypes") { supportedMimeTypes.forEach { add(it) } }
}

/**
 * Opens a play session: the files to play (or one HLS playlist when the server transcodes,
 * [forceTranscode] for a data-saving mode) and the position to start at. Progress reported
 * through the session counts as listening in the server's statistics. A new session for the
 * same book closes the open one, so keep one per book.
 */
suspend fun AudiobookshelfClient.play(
    itemId: String,
    device: DeviceInfo,
    supportedMimeTypes: List<String> = DEFAULT_SUPPORTED_MIME_TYPES,
    forceTranscode: Boolean = false,
): PlaybackSession =
    call("POST", "api/items/$itemId/play", PlaybackSession.serializer(), body = playBody(device, supportedMimeTypes, forceTranscode))

private fun sessionReport(currentTime: Double, timeListened: Double, duration: Double) = buildJsonObject {
    put("currentTime", currentTime)
    put("duration", duration)
    put("timeListened", timeListened)
}

/**
 * Where playback is now and how many seconds were listened since the last report. A `404`
 * means the session is gone (closed, or replaced by another device's): send the position with
 * [updateProgress] instead.
 */
suspend fun AudiobookshelfClient.syncSession(id: String, currentTime: Double, timeListened: Double, duration: Double) =
    send("POST", "api/session/$id/sync", body = sessionReport(currentTime, timeListened, duration))

/** The last report; the session is over after this (its sync answers 404). */
suspend fun AudiobookshelfClient.closeSession(id: String, currentTime: Double, timeListened: Double, duration: Double) =
    send("POST", "api/session/$id/close", body = sessionReport(currentTime, timeListened, duration))

@Serializable
private class LocalSessionsAnswer(val results: List<LocalSessionResult> = emptyList())

/** Listening the server didn't see, one answer per session. */
suspend fun AudiobookshelfClient.sendLocalSessions(sessions: List<LocalSession>, device: DeviceInfo): List<LocalSessionResult> {
    val body = buildJsonObject {
        put("deviceInfo", AbsJson.encodeToJsonElement(DeviceInfo.serializer(), device))
        put("sessions", buildJsonArray { sessions.forEach { add(AbsJson.encodeToJsonElement(LocalSession.serializer(), it)) } })
    }
    return call("POST", "api/session/local-all", LocalSessionsAnswer.serializer(), body = body).results
}
