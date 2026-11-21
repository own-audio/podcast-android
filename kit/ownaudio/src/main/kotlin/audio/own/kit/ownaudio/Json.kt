package audio.own.kit.ownaudio

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject

/**
 * The API's conventions: snake_case names, unknown fields ignored (the API only ever adds), a
 * field the server leaves out or sends as null takes the model's default.
 */
val OwnAudioJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Request bodies with their object keys sorted, at every depth. The server matches an
 * `Idempotency-Key` retry on the body's bytes; a body whose keys came out in another order is
 * refused as "used for a different request".
 */
object CanonicalJson {
    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    fun encode(element: JsonElement): String = OwnAudioJson.encodeToString(JsonElement.serializer(), sorted(element))

    fun <T> encode(serializer: KSerializer<T>, value: T): String = encode(OwnAudioJson.encodeToJsonElement(serializer, value))
}

/** RFC 3339 times, with or without fractions of a second; written as UTC. */
object Rfc3339 : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audio.own.kit.ownaudio.Instant", PrimitiveKind.STRING)

    fun parse(text: String): Instant = try {
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        throw SerializationException("not an RFC 3339 time: $text", e)
    }

    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))
}

/**
 * A closed set the server may still grow: a value this version doesn't know decodes to
 * [unknown] instead of failing the whole answer.
 */
abstract class TolerantEnumSerializer<E : Enum<E>>(
    name: String,
    private val values: Array<E>,
    private val unknown: E,
    private val wire: (E) -> String,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E {
        val text = decoder.decodeString()
        return values.firstOrNull { it != unknown && wire(it) == text } ?: unknown
    }

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(wire(value))
}
