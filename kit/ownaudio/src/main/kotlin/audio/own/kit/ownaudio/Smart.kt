@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Smart playlists: a saved rule, worked out for whoever plays it.

/** What a smart playlist holds: the server's rule, field for field. What is left out is not a condition. */
@Serializable
data class SmartRule(
    val filters: Filters = Filters(),
    val limit: Limit = Limit.Count(50),
    val arc: Arc = Arc.NONE,
    val sequence: Sequence = Sequence(),
    val sort: Sort = Sort.WEIGHTED_RANDOM,
) {
    @Serializable
    data class Names(val include: List<String> = emptyList(), val exclude: List<String> = emptyList())

    @Serializable
    data class Filters(
        /** Inclusive, beats per minute. */
        val bpm: List<Double>? = null,
        /** Inclusive, 0 to 1, relative to this library. */
        val energy: List<Double>? = null,
        /** Genre names, any case. */
        val genres: Names? = null,
        /** Artist names, any case. */
        val artists: Names? = null,
        /** Only favourites. */
        val starred: Boolean? = null,
        val minRating: Int? = null,
        /** Not played by the listener in this many days. */
        val notPlayedDays: Int? = null,
        /** Added in the last this many days. */
        val addedDays: Int? = null,
        /** Played by others in the household, never by the listener. */
        val playedByOthersNotMe: Boolean? = null,
    )

    @Serializable(with = Limit.Serializer::class)
    sealed interface Limit {
        data class Count(val n: Int) : Limit
        data class Minutes(val minutes: Int) : Limit

        object Serializer : KSerializer<Limit> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SmartRule.Limit")

            override fun deserialize(decoder: Decoder): Limit {
                val obj = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject
                    ?: throw SerializationException("a limit is a JSON object")
                return when (obj["kind"]?.jsonPrimitive?.content) {
                    "duration" -> Minutes(obj["minutes"]?.jsonPrimitive?.intOrNull ?: 60)
                    else -> Count(obj["n"]?.jsonPrimitive?.intOrNull ?: 50)
                }
            }

            override fun serialize(encoder: Encoder, value: Limit) {
                val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
                json.encodeJsonElement(
                    when (value) {
                        is Count -> buildJsonObject { put("kind", "count"); put("n", value.n) }
                        is Minutes -> buildJsonObject { put("kind", "duration"); put("minutes", value.minutes) }
                    },
                )
            }
        }
    }

    /** The energy over the playlist. */
    @Serializable(with = Arc.Serializer::class)
    enum class Arc(val wire: String) {
        NONE("none"), WARMUP_SUSTAIN_COOLDOWN("warmup-sustain-cooldown"), BUILD("build"), UNWIND("unwind"), UNKNOWN("unknown");

        internal object Serializer : TolerantEnumSerializer<Arc>("SmartRule.Arc", entries.toTypedArray(), UNKNOWN, { it.wire })
    }

    @Serializable(with = Sort.Serializer::class)
    enum class Sort(val wire: String) {
        WEIGHTED_RANDOM("weighted-random"), WEIGHT("weight"), LEAST_RECENTLY_PLAYED("least-recently-played"), RANDOM("random"), UNKNOWN("unknown");

        internal object Serializer : TolerantEnumSerializer<Sort>("SmartRule.Sort", entries.toTypedArray(), UNKNOWN, { it.wire })
    }

    @Serializable
    data class Sequence(val maxPerArtist: Int = 2, val noConsecutiveArtist: Boolean = true)
}

@Serializable
data class SmartPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val rule: SmartRule = SmartRule(),
    /** `dynamic`, or `frozen` once made into an ordinary playlist. */
    val mode: String = "dynamic",
    /** `private` or `family`. */
    val visibility: String = "private",
    val ownerId: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class SmartPreset(val slug: String, val name: String, val rule: SmartRule)

/** What a rule holds now, in the order to play it. */
@Serializable
data class ResolvedRule(
    val items: List<Track> = emptyList(),
    val durationMs: Long = 0,
    /** How many tracks matched before sequencing. */
    val candidates: Int = 0,
    val included: Included? = null,
)

@Serializable
internal data class RuleBody(val rule: SmartRule)

@Serializable
internal data class SmartBody(val name: String, val rule: SmartRule, val visibility: String? = null, val description: String? = null)

@Serializable
internal data class SmartPatchBody(val name: String? = null, val rule: SmartRule? = null, val visibility: String? = null, val description: String? = null)

@Serializable
internal data class FreezeBody(val name: String? = null, val visibility: String? = null, val markFrozen: Boolean? = null)

@Serializable
internal data class IntentBody(val text: String)

@Serializable
internal data class IntentAnswer(val rule: SmartRule)

suspend fun OwnAudioClient.smartPlaylists(cursor: String? = null): Page<SmartPlaylist> =
    getJson("smart-playlists", Query("cursor" to cursor))

suspend fun OwnAudioClient.smartPlaylist(id: String): SmartPlaylist = getJson("smart-playlists/$id")

suspend fun OwnAudioClient.smartPresets(): List<SmartPreset> = getJson("smart-playlists/presets")

/** A rule tried without saving it. */
suspend fun OwnAudioClient.resolve(rule: SmartRule, include: List<String> = emptyList()): ResolvedRule =
    send("POST", "smart-playlists/resolve", RuleBody(rule), Query().add("include", include))

/** A saved one, for the caller; a new draw each time. */
suspend fun OwnAudioClient.resolveSmartPlaylist(id: String, include: List<String> = emptyList()): ResolvedRule =
    call("POST", "smart-playlists/$id/resolve", ResolvedRule.serializer(), null, Unit.serializer(), Query().add("include", include))

/** A rule from words ("calm jazz for an hour"), for the listener to look at before saving. */
suspend fun OwnAudioClient.smartIntent(text: String): SmartRule = send<IntentAnswer, IntentBody>("POST", "smart-playlists/intent", IntentBody(text)).rule

suspend fun OwnAudioClient.createSmartPlaylist(name: String, rule: SmartRule, visibility: String = "private", description: String? = null): SmartPlaylist =
    send("POST", "smart-playlists", SmartBody(name, rule, visibility, description))

suspend fun OwnAudioClient.updateSmartPlaylist(
    id: String,
    name: String? = null,
    rule: SmartRule? = null,
    visibility: String? = null,
    description: String? = null,
): SmartPlaylist = send("PATCH", "smart-playlists/$id", SmartPatchBody(name, rule, visibility, description))

suspend fun OwnAudioClient.deleteSmartPlaylist(id: String) = sendOnly("DELETE", "smart-playlists/$id")

/** Made into an ordinary playlist with what the rule holds now. */
suspend fun OwnAudioClient.freezeSmartPlaylist(id: String, name: String? = null, visibility: String? = null): Playlist =
    send("POST", "smart-playlists/$id/freeze", FreezeBody(name, visibility))
