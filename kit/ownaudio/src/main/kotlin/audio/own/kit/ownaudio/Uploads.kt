@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `POST /uploads`: a signed link to put one file straight into storage, then named by its
 * [uploadId] in a create (`POST /books`, `/tracks`) or a picture (`PUT /tracks/{id}/image`).
 */
@Serializable
data class UploadLink(
    val uploadId: String,
    val url: String,
    val method: String = "PUT",
    /** Part of the signature: the PUT sends exactly these. */
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Instant,
)

/** Kinds of `POST /uploads`. */
object UploadKinds {
    const val TRACK = "track"
    const val TRACK_COVER = "track_cover"
    const val BOOK_FILE = "book_file"
    const val BOOK_COVER = "book_cover"
    const val COMPANION = "companion"
    const val EPISODE = "episode"
    const val EPISODE_TRANSCRIPT = "episode_transcript"
    const val PODCAST_COVER = "podcast_cover"
    const val EPISODE_IMAGE = "episode_image"
}

/** `POST /books`: a book from uploaded files, in the order given. */
@Serializable
data class NewBook(
    val title: String? = null,
    /** Several with `;` between them. */
    val author: String? = null,
    val narrator: String? = null,
    val description: String? = null,
    /** `private` (the default) or `family`. */
    val visibility: String? = null,
    val coverUploadId: String? = null,
    val path: String? = null,
    val files: List<File>,
) {
    @Serializable
    data class File(
        val uploadId: String,
        /** Sets the files' order and names them: `01 - Chapter one.mp3`. */
        val relativePath: String? = null,
        val title: String? = null,
    )
}

/** `POST /tracks`: a song from an uploaded file; what is left out is read from its tags. */
@Serializable
data class NewTrack(
    val uploadId: String,
    val filename: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val releaseKind: String? = null,
    val visibility: String? = null,
    val path: String? = null,
)

@Serializable
internal data class UploadBody(val kind: String, val filename: String, val contentType: String, val sizeBytes: Long)

@Serializable
internal data class ImageBody(val uploadId: String)

/** A link for one file; [sizeBytes] must be exact, the server refuses anything else. */
suspend fun OwnAudioClient.createUpload(kind: String, filename: String, contentType: String, sizeBytes: Long): UploadLink =
    send("POST", "uploads", UploadBody(kind, filename, contentType, sizeBytes))

/**
 * Puts the bytes where [link] says, with exactly its headers and no token: the link is signed
 * for storage, not for the API. Large files take as long as they take.
 */
suspend fun OwnAudioClient.putUpload(link: UploadLink, body: RequestBody) {
    if (isOffline()) throw OwnAudioException.Offline
    val request = Request.Builder().url(link.url).header("User-Agent", userAgent)
        .apply { link.headers.forEach { (k, v) -> header(k, v) } }
        .method(link.method, body)
        .build()
    val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(5, TimeUnit.MINUTES).build()
    val response = client.newCall(request).await()
    response.use { if (!it.isSuccessful) throw OwnAudioException.Http(it.code) }
}

suspend fun OwnAudioClient.putUpload(link: UploadLink, file: File) =
    putUpload(link, file.asRequestBody(link.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()))

suspend fun OwnAudioClient.putUpload(link: UploadLink, bytes: ByteArray) =
    putUpload(link, bytes.toRequestBody(link.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()))

/** The book once every file is in storage. [idempotencyKey] makes a retry answer the same book. */
suspend fun OwnAudioClient.createBook(book: NewBook, idempotencyKey: String): Book = send("POST", "books", book, idempotencyKey = idempotencyKey)

suspend fun OwnAudioClient.createTrack(track: NewTrack, idempotencyKey: String): Track = send("POST", "tracks", track, idempotencyKey = idempotencyKey)

// region Pictures chosen by the listener: upload with kind `track_cover`/`book_cover`, then name it.

/** One of the caller's own tracks; an album's cover is its tracks'. */
suspend fun OwnAudioClient.setTrackImage(trackId: String, uploadId: String): Track = send("PUT", "tracks/$trackId/image", ImageBody(uploadId))

suspend fun OwnAudioClient.setPlaylistImage(playlistId: String, uploadId: String): Playlist = send("PUT", "playlists/$playlistId/image", ImageBody(uploadId))

/** The caller's picture for an artist, kept: the automatic lookup never replaces it. */
suspend fun OwnAudioClient.setContributorImage(contributorId: String, uploadId: String): Contributor =
    send("PUT", "contributors/$contributorId/image", ImageBody(uploadId))

suspend fun OwnAudioClient.setBookImage(bookId: String, uploadId: String): Book = send("PUT", "books/$bookId/image", ImageBody(uploadId))

suspend fun OwnAudioClient.setMyImage(uploadId: String): Me = send("PUT", "me/image", ImageBody(uploadId))

suspend fun OwnAudioClient.setFamilyImage(uploadId: String): Family = send("PUT", "family/image", ImageBody(uploadId))

suspend fun OwnAudioClient.setMemberImage(userId: String, uploadId: String): Member = send("PUT", "family/members/$userId/image", ImageBody(uploadId))

/** Takes a chosen picture away: `tracks/{id}`, `playlists/{id}`, `contributors/{id}`, `books/{id}`, `me`, `family`. */
suspend fun OwnAudioClient.deleteImage(owner: String) = sendOnly("DELETE", "${owner.trim('/')}/image")

// endregion
