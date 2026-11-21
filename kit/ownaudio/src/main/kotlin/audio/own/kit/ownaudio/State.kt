@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// The listener's own state: favourites, ratings, feedback, positions, plays, bookmarks, what
// the device holds offline, the play queue.

@Serializable
data class Favourite(val kind: String, val id: String, val createdAt: Instant)

@Serializable
data class Rating(val kind: String, val id: String, val rating: Int, val updatedAt: Instant)

/** `dislike` or `ban`: play this less, or never. */
@Serializable
data class Feedback(val kind: String, val id: String, val value: String, val updatedAt: Instant)

@Serializable
data class Progress(
    val kind: String,
    val id: String,
    val positionMs: Long,
    val finished: Boolean = false,
    val updatedAt: Instant,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    /** Where each device is, with the current one marked; on `GET /me/progress/{kind}/{id}`. */
    val devices: List<DevicePosition>? = null,
) {
    @Serializable
    data class DevicePosition(
        val deviceId: String,
        val deviceName: String? = null,
        val deviceKind: String,
        val positionMs: Long,
        val finished: Boolean = false,
        val at: Instant,
        val current: Boolean = false,
    )
}

@Serializable
data class ProgressAnswer(
    val progress: Progress,
    /** False when a newer position was already stored. */
    val applied: Boolean,
)

/** A play, reported when it ended. [clientId] makes sending it twice harmless. */
@Serializable
data class Play(
    val clientId: String,
    val kind: ItemKind,
    val id: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val listenedMs: Long,
    val fileId: String? = null,
    val speed: Double? = null,
    val deviceKind: String? = "android",
    val endedReason: String? = null,
)

/** A play as the server keeps it (`GET /me/plays`). */
@Serializable
data class PlayRecord(
    val id: String,
    val clientId: String? = null,
    val kind: String,
    val itemId: String,
    val fileId: String? = null,
    val startedAt: Instant,
    val endedAt: Instant,
    val listenedMs: Long,
    val speed: Double? = null,
    val deviceKind: String = "",
    val endedReason: String? = null,
    val source: String = "",
)

@Serializable
data class Bookmark(
    val id: String,
    val kind: String,
    val itemId: String,
    val positionMs: Long,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    val label: String? = null,
    val hasAudioNote: Boolean = false,
    val createdAt: Instant,
)

/** Something a device keeps for offline listening. */
@Serializable
data class HoldingRef(val kind: String, val id: String)

@Serializable
data class Holding(val kind: String, val id: String, val since: Instant)

@Serializable
data class HoldingDevice(
    val deviceId: String,
    val deviceKind: String? = null,
    val deviceName: String? = null,
    val since: Instant,
    val current: Boolean = false,
)

/** The listener's play queue, shared between their devices. */
@Serializable
data class Queue(
    val items: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val revision: Long = 0,
    val deviceKind: String? = null,
    val deviceName: String? = null,
    val updatedAt: Instant? = null,
)

@Serializable
data class QueueItem(val kind: String, val id: String, val fileId: String? = null)

@Serializable
internal data class RatingBody(val rating: Int)

@Serializable
internal data class FeedbackBody(val value: String)

@Serializable
internal data class ProgressBody(
    val positionMs: Long? = null,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    val finished: Boolean = false,
    val at: Instant? = null,
)

@Serializable
internal data class PlaysBody(val plays: List<Play>)

@Serializable
internal data class BookmarkBody(
    val kind: ItemKind,
    val itemId: String,
    val positionMs: Long? = null,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    val label: String? = null,
)

@Serializable
internal data class BookmarkPatchBody(val label: String? = null, val positionMs: Long? = null, val fileId: String? = null, val filePositionMs: Long? = null)

@Serializable
internal data class HoldingsBody(val added: List<HoldingRef>? = null, val removed: List<HoldingRef>? = null, val items: List<HoldingRef>? = null)

@Serializable
internal data class QueueBody(val items: List<QueueItem>, val index: Int, val positionMs: Long)

// region Favourites, ratings, feedback

suspend fun OwnAudioClient.setFavourite(kind: ItemKind, id: String, on: Boolean) =
    sendOnly(if (on) "PUT" else "DELETE", "me/favourites/${kind.wire}/$id")

suspend fun OwnAudioClient.favourites(kind: ItemKind, cursor: String? = null, limit: Int? = 500): Page<Favourite> =
    getJson("me/favourites", Query("kind" to kind.wire, "cursor" to cursor).add("limit", limit))

/** 1 to 5; 0 takes the rating away. */
suspend fun OwnAudioClient.setRating(kind: ItemKind, id: String, rating: Int) =
    sendOnly("PUT", "me/ratings/${kind.wire}/$id", RatingBody(rating))

suspend fun OwnAudioClient.ratings(kind: ItemKind, cursor: String? = null, limit: Int? = 500): Page<Rating> =
    getJson("me/ratings", Query("kind" to kind.wire, "cursor" to cursor).add("limit", limit))

/** [value] `dislike` or `ban`; `null` takes it away. */
suspend fun OwnAudioClient.setFeedback(kind: ItemKind, id: String, value: String?) {
    if (value == null) sendOnly("DELETE", "me/feedback/${kind.wire}/$id") else sendOnly("PUT", "me/feedback/${kind.wire}/$id", FeedbackBody(value))
}

suspend fun OwnAudioClient.feedback(value: String? = null, cursor: String? = null, limit: Int? = 500): Page<Feedback> =
    getJson("me/feedback", Query("value" to value, "cursor" to cursor).add("limit", limit))

// endregion

// region Positions

/**
 * Every position of the [kinds] (one list), the most recently changed first: "continue
 * listening" with [unfinished]. `include = listOf("items")` embeds the records.
 */
suspend fun OwnAudioClient.progressList(
    kinds: List<ItemKind>,
    unfinished: Boolean? = null,
    include: List<String> = emptyList(),
    cursor: String? = null,
    limit: Int? = null,
): Page<Progress> = getJson(
    "me/progress",
    Query("kind" to kinds.joinToString(",") { it.wire }, "unfinished" to unfinished?.toString(), "cursor" to cursor).add("include", include).add("limit", limit),
)

/** With the position of every device; null when the item was never started. */
suspend fun OwnAudioClient.progress(kind: ItemKind, id: String): Progress? = try {
    getJson("me/progress/${kind.wire}/$id")
} catch (e: OwnAudioException) {
    if (e.httpStatus == 404) null else throw e
}

/** Where the listener is; [at] is when they were there, for a position saved offline. */
suspend fun OwnAudioClient.saveProgress(kind: ItemKind, id: String, positionMs: Long, finished: Boolean = false, at: Instant? = null): ProgressAnswer =
    send("PUT", "me/progress/${kind.wire}/$id", ProgressBody(positionMs = positionMs, finished = finished, at = at))

/** A position named by the file and the offset in it; the server works out the book time. */
suspend fun OwnAudioClient.saveProgress(
    kind: ItemKind,
    id: String,
    fileId: String,
    filePositionMs: Long,
    finished: Boolean = false,
    at: Instant? = null,
): ProgressAnswer = send("PUT", "me/progress/${kind.wire}/$id", ProgressBody(fileId = fileId, filePositionMs = filePositionMs, finished = finished, at = at))

/** Forgets the position on every device. */
suspend fun OwnAudioClient.deleteProgress(kind: ItemKind, id: String) = sendOnly("DELETE", "me/progress/${kind.wire}/$id")

// endregion

// region Plays

/** Plays, each answered on its own; sending one twice counts it once. */
suspend fun OwnAudioClient.reportPlays(plays: List<Play>): List<ItemResult> = send("POST", "me/plays", PlaysBody(plays))

/** The listener's plays, newest first. */
suspend fun OwnAudioClient.plays(cursor: String? = null, limit: Int? = null): Page<PlayRecord> =
    getJson("me/plays", Query("cursor" to cursor).add("limit", limit))

// endregion

// region Bookmarks

suspend fun OwnAudioClient.bookmarks(kind: ItemKind, itemId: String? = null, cursor: String? = null, limit: Int? = 500): Page<Bookmark> =
    getJson("me/bookmarks", Query("kind" to kind.wire, "item_id" to itemId, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.createBookmark(
    kind: ItemKind,
    itemId: String,
    positionMs: Long? = null,
    label: String? = null,
    fileId: String? = null,
    filePositionMs: Long? = null,
): Bookmark = send("POST", "me/bookmarks", BookmarkBody(kind, itemId, positionMs, fileId, filePositionMs, label))

/** A new label (empty clears it) and/or a new position; `null` keeps what is there. */
suspend fun OwnAudioClient.updateBookmark(id: String, label: String? = null, positionMs: Long? = null): Bookmark =
    send("PATCH", "me/bookmarks/$id", BookmarkPatchBody(label, positionMs))

suspend fun OwnAudioClient.deleteBookmark(id: String) = sendOnly("DELETE", "me/bookmarks/$id")

// endregion

// region Holdings and queue

/** What this device keeps offline: [added] and [removed], or the whole list as [items]. */
suspend fun OwnAudioClient.updateHoldings(added: List<HoldingRef>? = null, removed: List<HoldingRef>? = null, items: List<HoldingRef>? = null) =
    sendOnly("PUT", "me/holdings", HoldingsBody(added, removed, items))

suspend fun OwnAudioClient.holdings(cursor: String? = null, limit: Int? = null): Page<Holding> =
    getJson("me/holdings", Query("cursor" to cursor).add("limit", limit))

/** Which of the listener's devices keep an item. */
suspend fun OwnAudioClient.holders(kind: ItemKind, id: String): List<HoldingDevice> = getJson("me/holdings/${kind.wire}/$id")

suspend fun OwnAudioClient.queue(): Queue = getJson("me/queue")

suspend fun OwnAudioClient.saveQueue(items: List<QueueItem>, index: Int, positionMs: Long): Queue =
    send("PUT", "me/queue", QueueBody(items, index, positionMs))

// endregion
