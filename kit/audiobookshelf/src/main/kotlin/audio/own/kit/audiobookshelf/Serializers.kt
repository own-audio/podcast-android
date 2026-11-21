package audio.own.kit.audiobookshelf

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** How the server's JSON is read and request bodies written. */
internal val AbsJson = Json {
    ignoreUnknownKeys = true
    // Request bodies carry their fixed values (a local session's playMethod 3, its
    // mediaPlayer); absent optionals are still left out.
    encodeDefaults = true
    explicitNulls = false
    // A field the server sends as null where this side has a default takes the default.
    coerceInputValues = true
    isLenient = false
}

/**
 * An id, `ino`, sequence or year that one server version sends as a number and another as a
 * string (`"ino": 17088` on one, `"ino": "17088"` on the next). Read as a string either way; a
 * whole number loses its `.0`.
 */
internal object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("audiobookshelf.FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return flexibleString(json.decodeJsonElement())
            ?: throw SerializationException("neither a string nor a number")
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

internal fun flexibleString(element: JsonElement): String? {
    val p = element as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return p.content
    p.longOrNull?.let { return it.toString() }
    val d = p.doubleOrNull ?: return null
    return if (d == Math.rint(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}

/** A list filtered by a series carries that one series as an object, not an array. */
internal object SeriesEntriesSerializer :
    JsonTransformingSerializer<List<SeriesEntry>>(ListSerializer(SeriesEntry.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonArray(listOf(element)) else element
}

/** Decodes [element] with [serializer], or `null` when it does not fit; for fields that are best effort. */
internal fun <T> Json.decodeOrNull(serializer: KSerializer<T>, element: JsonElement?): T? {
    if (element == null || element is JsonNull) return null
    return try {
        decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
