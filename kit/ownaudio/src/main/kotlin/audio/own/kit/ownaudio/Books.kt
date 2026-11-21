@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// Books: lists carry the book without its files, chapters and series; `GET /books/{id}` and the
// change feed's records carry all three.

@Serializable
data class Book(
    val id: String,
    val title: String,
    val credits: List<Credit> = emptyList(),
    val description: String? = null,
    /** Whole seconds on the server, so a multiple of 1000. */
    val durationMs: Long? = null,
    val isbn: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    /** `private` or `family`. */
    val visibility: String = "private",
    val ownerId: String = "",
    val source: String? = null,
    val path: String? = null,
    val tags: List<String>? = null,
    /** A signed link; see [OwnAudioImages.sized]. */
    val image: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** In playing order; only on the detail and in the feed. */
    val files: List<BookFile>? = null,
    /** Across the whole book, in order; empty when the server knows none. */
    val chapters: List<BookChapter>? = null,
    val series: List<SeriesEntry>? = null,
)

@Serializable
data class BookFile(
    val id: String,
    val position: Int,
    val title: String? = null,
    val relativePath: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val sha256: String? = null,
)

/** A chapter mark: where it starts in book time; it ends where the next one starts. */
@Serializable
data class BookChapter(
    val id: String,
    val position: Int,
    val title: String? = null,
    val startMs: Long,
    val fileId: String? = null,
)

/** A book's place in a series; [position] may be fractional (1.5). */
@Serializable
data class SeriesEntry(val seriesId: String, val name: String, val position: Double? = null)

@Serializable
data class Series(
    val id: String,
    val name: String,
    val description: String? = null,
    val visibility: String = "private",
    val ownerId: String = "",
    val books: List<SeriesBook> = emptyList(),
    val createdAt: Instant,
)

@Serializable
data class SeriesBook(val bookId: String, val position: Double? = null)

@Serializable
data class Collection(
    val id: String,
    val name: String,
    val description: String? = null,
    val visibility: String = "private",
    val ownerId: String = "",
    val bookCount: Long = 0,
    val image: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class CollectionEntry(val position: Int, val addedAt: Instant, val book: Book)

@Serializable
data class BookTag(val id: String, val name: String, val bookCount: Long = 0)

@Serializable
internal data class NewCollectionBody(val name: String, val description: String? = null, val visibility: String? = null, val bookIds: List<String> = emptyList())

@Serializable
internal data class BookIdsBody(val bookIds: List<String>)

/** @param sort `title` or `added` (newest first); [query] matches the title. */
suspend fun OwnAudioClient.books(
    contributorId: String? = null,
    query: String? = null,
    tag: String? = null,
    owner: String? = null,
    visibility: String? = null,
    sort: String? = null,
    include: List<String> = emptyList(),
    cursor: String? = null,
    limit: Int? = null,
): Page<Book> = getJson(
    "books",
    Query("contributor_id" to contributorId, "q" to query, "tag" to tag, "owner" to owner, "visibility" to visibility, "sort" to sort, "cursor" to cursor)
        .add("include", include).add("limit", limit),
)

/** With files, chapters, series and the credited contributors. */
suspend fun OwnAudioClient.book(id: String): WithIncluded<Book> {
    val obj = decodeObject(raw("GET", "books/$id", Query("include" to "contributors")))
    return WithIncluded(decodeElement(Book.serializer(), obj), obj["included"]?.let { decodeElement(Included.serializer(), it) })
}

suspend fun OwnAudioClient.authors(query: String? = null, cursor: String? = null, limit: Int? = null): Page<Contributor> =
    getJson("authors", Query("q" to query, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.narrators(query: String? = null, cursor: String? = null, limit: Int? = null): Page<Contributor> =
    getJson("narrators", Query("q" to query, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.hosts(query: String? = null, cursor: String? = null, limit: Int? = null): Page<Contributor> =
    getJson("hosts", Query("q" to query, "cursor" to cursor).add("limit", limit))

/** Shares one of the caller's own books with the household (`family`) or not (`private`). */
suspend fun OwnAudioClient.setBookVisibility(bookId: String, visibility: String): Visibility =
    send("PUT", "books/$bookId/visibility", Visibility(visibility))

suspend fun OwnAudioClient.bookTags(cursor: String? = null, limit: Int? = null): Page<BookTag> =
    getJson("book-tags", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.seriesList(cursor: String? = null, limit: Int? = null): Page<Series> =
    getJson("series", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.series(id: String): Series = getJson("series/$id")

suspend fun OwnAudioClient.collections(cursor: String? = null, limit: Int? = null): Page<Collection> =
    getJson("collections", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.collection(id: String): Collection = getJson("collections/$id")

suspend fun OwnAudioClient.collectionBooks(id: String, cursor: String? = null, limit: Int? = null): Page<CollectionEntry> =
    getJson("collections/$id/books", Query("cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.createCollection(name: String, description: String? = null, visibility: String? = null, bookIds: List<String> = emptyList()): Collection =
    send("POST", "collections", NewCollectionBody(name, description, visibility, bookIds))

suspend fun OwnAudioClient.addToCollection(id: String, bookIds: List<String>): Collection =
    send("POST", "collections/$id/books", BookIdsBody(bookIds))

suspend fun OwnAudioClient.removeFromCollection(id: String, bookId: String) = sendOnly("DELETE", "collections/$id/books/$bookId")

suspend fun OwnAudioClient.deleteCollection(id: String) = sendOnly("DELETE", "collections/$id")
