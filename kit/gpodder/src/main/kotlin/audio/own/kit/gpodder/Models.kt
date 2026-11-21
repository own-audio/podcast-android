package audio.own.kit.gpodder

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

sealed class GPodderException(message: String) : Exception(message) {
    /** Wrong name or password. */
    class Unauthorized : GPodderException("unauthorized")

    /**
     * Signed in, but this account may not do that (a read-only demo). Some servers (podfetch)
     * also answer wrong credentials with 403, which is why [GPodderClient.login] reads it as
     * [Unauthorized].
     */
    class Forbidden : GPodderException("forbidden")

    /**
     * Any other status. podfetch answers 409 to its admin account, which may not sync: the app
     * asks for a separate user there.
     */
    class Http(val status: Int) : GPodderException("HTTP $status")

    class Decoding(cause: Throwable? = null) : GPodderException("undecodable answer") {
        init {
            cause?.let(::initCause)
        }
    }

    class Network(cause: Throwable? = null) : GPodderException("network error") {
        init {
            cause?.let(::initCause)
        }
    }
}

/**
 * One episode action, as the gPodder API records them: a download, a play (with where it started,
 * where it stopped and how long the episode is), a delete, or new.
 */
@Serializable
data class EpisodeAction(
    /** The show's feed URL. */
    val podcast: String,
    /** The episode's enclosure URL — what the API identifies episodes by. */
    val episode: String,
    val action: Kind,
    /** When it happened, in UTC; sent whole seconds, without a zone. */
    @Serializable(with = GPodderTimestamp::class)
    val timestamp: Instant? = null,
    /** Seconds, `play` only. */
    @Serializable(with = LenientInt::class)
    val started: Int? = null,
    @Serializable(with = LenientInt::class)
    val position: Int? = null,
    @Serializable(with = LenientInt::class)
    val total: Int? = null,
    val device: String? = null,
    val guid: String? = null,
) {
    @Serializable(with = KindSerializer::class)
    enum class Kind(val wire: String) {
        DOWNLOAD("download"),
        PLAY("play"),
        DELETE("delete"),
        NEW("new"),

        /** An action this client doesn't know (some servers add their own); skipped, never fatal. */
        UNKNOWN("unknown"),
    }
}

@Serializable
data class SubscriptionChanges(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    /** Pass back as `since` next time. */
    val timestamp: Long,
)

@Serializable
data class EpisodeActions(
    val actions: List<EpisodeAction> = emptyList(),
    val timestamp: Long,
)

/** What an upload answers: the new `since`, and URLs the server cleaned up (old, new). */
@Serializable
data class UploadAnswer(
    val timestamp: Long,
    @SerialName("update_urls")
    val updateUrls: List<List<String>> = emptyList(),
)

internal object KindSerializer : KSerializer<EpisodeAction.Kind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audio.own.kit.gpodder.EpisodeAction.Kind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EpisodeAction.Kind) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): EpisodeAction.Kind {
        val wire = decoder.decodeString().lowercase()
        return EpisodeAction.Kind.entries.firstOrNull { it.wire == wire } ?: EpisodeAction.Kind.UNKNOWN
    }
}

/** The API's timestamps are UTC without a zone: "2026-10-17T08:00:00". */
internal object GPodderTimestamp : KSerializer<Instant> {
    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audio.own.kit.gpodder.Timestamp", PrimitiveKind.STRING)

    fun format(instant: Instant): String = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC).format(format)

    fun parse(text: String): Instant? = try {
        // Some servers add fractions or a zone; the first 19 characters are the API's form.
        LocalDateTime.parse(text.take(19), format).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        null
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return parse(text) ?: throw SerializationException("timestamp $text")
    }
}

/** Seconds as the API means them, also when a server writes them as `120.0`. */
internal object LenientInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audio.own.kit.gpodder.Seconds", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val element = decoder.decodeJsonElement()
        val number = (element as? JsonPrimitive)?.doubleOrNull ?: throw SerializationException("not a number: $element")
        return number.toInt()
    }
}
