@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response

// The change feed (snapshot, then changes), offline changes, live events.

/** One entry of `GET /sync/changes`: the record now, or its removal. Skip kinds you don't know. */
@Serializable
data class Change(
    /** `track`, `album`, …, `favourite.track`, `progress.book`, `bookmark`, `queue`, `settings`… */
    val kind: String,
    val id: String,
    /** `upsert` or `delete` (gone, or no longer visible to the caller). */
    val op: String,
    /** The record, still as JSON: decode it by [kind] with [decode]. */
    val record: JsonObject? = null,
) {
    fun <T> decode(serializer: KSerializer<T>): T? = record?.let { OwnAudioJson.decodeFromJsonElement(serializer, it) }
}

@Serializable
data class ChangesAnswer(
    val changes: List<Change> = emptyList(),
    val cursor: String,
    val hasMore: Boolean = false,
    /** Drop the copy and take a new snapshot. */
    val reset: Boolean = false,
)

/** One line of `GET /sync/snapshot`. */
sealed interface SnapshotLine {
    data class Begin(val cursor: String) : SnapshotLine
    data class Record(val kind: String, val id: String, val record: JsonObject) : SnapshotLine
    /** With `fields=ids`: a record named, not sent. */
    data class Id(val kind: String, val id: String) : SnapshotLine
    /** Same cursor as [Begin]: read `GET /sync/changes` from it. */
    data class End(val cursor: String) : SnapshotLine

    companion object {
        /** A line of the snapshot; `null` for a line of a type this version doesn't know. */
        fun parse(line: String): SnapshotLine? {
            val obj = OwnAudioJson.parseToJsonElement(line) as? JsonObject ?: return null
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (text("type")) {
                "begin" -> text("cursor")?.let(::Begin)
                "end" -> text("cursor")?.let(::End)
                "record" -> {
                    val kind = text("kind") ?: return null
                    val id = text("id") ?: return null
                    val record = obj["record"] as? JsonObject ?: return null
                    Record(kind, id, record)
                }
                "id" -> {
                    val kind = text("kind") ?: return null
                    Id(kind, text("id") ?: return null)
                }
                else -> null
            }
        }
    }
}

/**
 * One change made offline, for `POST /sync/push`. [clientId] comes back in its result and makes
 * a change sent twice count once. Build them with the companion's functions.
 */
@Serializable
data class OfflineChange(
    val clientId: String,
    /** `favourite`, `unfavourite`, `rate`, `progress`, `play`, `feedback` or `unfeedback`. */
    val op: String,
    val kind: ItemKind,
    val id: String,
    /** When it happened, by the device's clock (the server never lets it be in the future). */
    val at: Instant? = null,
    val rating: Int? = null,
    val value: String? = null,
    val positionMs: Long? = null,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    val finished: Boolean? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val listenedMs: Long? = null,
    val speed: Double? = null,
    val deviceKind: String? = null,
    val endedReason: String? = null,
) {
    companion object {
        fun favourite(kind: ItemKind, id: String, on: Boolean, at: Instant, clientId: String) =
            OfflineChange(clientId, if (on) "favourite" else "unfavourite", kind, id, at)

        /** 1 to 5; 0 takes the rating away. */
        fun rating(kind: ItemKind, id: String, rating: Int, at: Instant, clientId: String) =
            OfflineChange(clientId, "rate", kind, id, at, rating = rating)

        /** `dislike` or `ban`; `null` takes it away. */
        fun feedback(kind: ItemKind, id: String, value: String?, at: Instant, clientId: String) =
            OfflineChange(clientId, if (value == null) "unfeedback" else "feedback", kind, id, at, value = value)

        /** A position reached offline; [at] decides against a position from another device. */
        fun progress(kind: ItemKind, id: String, positionMs: Long, finished: Boolean, at: Instant, clientId: String) =
            OfflineChange(clientId, "progress", kind, id, at, positionMs = positionMs, finished = finished)

        fun progress(kind: ItemKind, id: String, fileId: String, filePositionMs: Long, finished: Boolean, at: Instant, clientId: String) =
            OfflineChange(clientId, "progress", kind, id, at, fileId = fileId, filePositionMs = filePositionMs, finished = finished)

        fun play(play: Play) = OfflineChange(
            clientId = play.clientId, op = "play", kind = play.kind, id = play.id, at = play.endedAt,
            fileId = play.fileId, startedAt = play.startedAt, endedAt = play.endedAt, listenedMs = play.listenedMs,
            speed = play.speed, deviceKind = play.deviceKind, endedReason = play.endedReason,
        )
    }
}

@Serializable
internal data class PushBody(val changes: List<OfflineChange>)

/** An event of `GET /events`. */
data class LiveEvent(
    /** `library`: something the account sees changed, read the change feed. `identify`, `narration`… */
    val type: String,
    /** For resuming with `Last-Event-ID`. */
    val id: String?,
    val data: String,
) {
    /** The data as JSON, or `null` when it isn't. */
    val json: JsonElement? get() = runCatching { OwnAudioJson.parseToJsonElement(data) }.getOrNull()
}

/**
 * Reads a `text/event-stream` (the HTML standard's rules, without `retry`): fields until a
 * blank line, then the event. Fed bytes, so `\n`, `\r\n` and `\r` all end a line and the blank
 * line that ends an event is never lost.
 */
class EventStreamParser {
    private val line = java.io.ByteArrayOutputStream()
    private var lastWasCR = false
    private var type = ""
    private var id: String? = null
    private val data = mutableListOf<String>()

    fun feed(byte: Byte): LiveEvent? = when (byte.toInt()) {
        0x0A if lastWasCR -> {
            lastWasCR = false
            null
        }
        0x0A, 0x0D -> {
            lastWasCR = byte.toInt() == 0x0D
            val text = line.toString(Charsets.UTF_8)
            line.reset()
            feed(text)
        }
        else -> {
            lastWasCR = false
            line.write(byte.toInt())
            null
        }
    }

    fun feed(bytes: ByteArray): List<LiveEvent> = bytes.toList().mapNotNull { feed(it) }

    fun feed(line: String): LiveEvent? {
        if (line.isEmpty()) {
            val event = if (data.isEmpty()) null else LiveEvent(type.ifEmpty { "message" }, id, data.joinToString("\n"))
            type = ""
            data.clear()
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> type = value
            "data" -> data += value
            "id" -> id = value
        }
        return null
    }
}

/** What changed since [cursor]; with none, a cursor for "from now on". */
suspend fun OwnAudioClient.changes(cursor: String?, kinds: List<String> = emptyList(), limit: Int? = 500): ChangesAnswer =
    getJson("sync/changes", Query("cursor" to cursor).add("kinds", kinds).add("limit", limit))

/**
 * What the device changed while offline, in the order it happened; up to 500 at a time. Each is
 * answered on its own, in order: `ok`, `stale` or `duplicate` are done, anything else is an
 * error code.
 */
suspend fun OwnAudioClient.push(changes: List<OfflineChange>): List<ItemResult> = send("POST", "sync/push", PushBody(changes))

/**
 * Everything the account can see, line by line. [kinds] limits it; `fields = "ids"` names
 * records without sending them. A body cut short (no `end` line) fails with
 * [OwnAudioException.SnapshotTruncated]: resume with its `after` and `cursor`.
 */
fun OwnAudioClient.snapshot(
    kinds: List<String> = emptyList(),
    fields: String? = null,
    after: String? = null,
    cursor: String? = null,
): Flow<SnapshotLine> = channelFlow {
    val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val query = Query("fields" to fields, "after" to after, "cursor" to cursor).add("kinds", kinds)
    val (call, response) = openStream(client) { token ->
        base(url("sync/snapshot", query), accept = "application/x-ndjson").header("Authorization", "Bearer $token").get().build()
    }
    var begin: String? = cursor
    var last: String? = after
    var ended = false
    readCancellable(call, response) {
        val source = response.body.source()
        try {
            while (true) {
                val text = source.readUtf8Line() ?: break
                if (text.isBlank()) continue
                val line = runCatching { SnapshotLine.parse(text) }.getOrNull() ?: continue
                when (line) {
                    is SnapshotLine.Begin -> begin = line.cursor
                    is SnapshotLine.Record -> last = "${line.kind}:${line.id}"
                    is SnapshotLine.Id -> last = "${line.kind}:${line.id}"
                    is SnapshotLine.End -> ended = true
                }
                send(line)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw OwnAudioException.SnapshotTruncated(last, begin).apply { initCause(e) }
        }
    }
    if (!ended) throw OwnAudioException.SnapshotTruncated(last, begin)
}

/**
 * Live updates until the connection ends: a `library` event whenever the account's library or
 * its own state changed, which says to read [changes]. Resume with the last event's id. The
 * server sends a comment every 30 seconds, so [silenceMs] without a byte means the connection is
 * dead ([OwnAudioException.Network]). [onOpen] runs once the server has accepted the stream.
 */
fun OwnAudioClient.events(lastEventId: String? = null, silenceMs: Long = 75_000, onOpen: (() -> Unit)? = null): Flow<LiveEvent> = channelFlow {
    val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(silenceMs, TimeUnit.MILLISECONDS).build()
    val (call, response) = openStream(client) { token ->
        base(url("events"), accept = "text/event-stream").header("Authorization", "Bearer $token")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .get().build()
    }
    onOpen?.invoke()
    readCancellable(call, response) {
        val source = response.body.source()
        val parser = EventStreamParser()
        try {
            while (!source.exhausted()) {
                parser.feed(source.readByte())?.let { send(it) }
            }
        } catch (e: IOException) {
            // Cancelling the collector cancels the call, which ends the read with an IOException.
            currentCoroutineContext().ensureActive()
            throw OwnAudioException.Network(e)
        }
    }
}

internal data class OpenedStream(val call: Call, val response: Response)

internal suspend fun OwnAudioClient.openStream(client: OkHttpClient, build: (String) -> okhttp3.Request): OpenedStream {
    val session = tokens ?: throw OwnAudioException.SignedOut
    if (isOffline()) throw OwnAudioException.Offline
    val token = session.accessToken(::refreshTokens)
    val first = client.newCall(build(token))
    val response = first.await()
    if (response.code != 401) return OpenedStream(first, streamChecked(response))
    response.close()
    val fresh = session.refresh(token, ::refreshTokens)
    val second = client.newCall(build(fresh.accessToken))
    return OpenedStream(second, streamChecked(second.await()))
}

private fun OwnAudioClient.streamChecked(response: Response): Response {
    if (response.isSuccessful) return response
    response.use { throw problem(it, runCatching { it.body.bytes() }.getOrDefault(ByteArray(0))) }
}

/**
 * Reads a streaming body on the IO dispatcher. A blocking read doesn't notice cancellation, so
 * cancelling the collector cancels the call, which ends the read.
 */
internal suspend fun readCancellable(call: Call, response: Response, block: suspend () -> Unit) = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        response.use { withContext(Dispatchers.IO) { block() } }
    } finally {
        watcher.cancel()
    }
}
