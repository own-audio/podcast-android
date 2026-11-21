@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// The account's settings and its listening statistics.

/** `GET /me/settings`: what follows the account from device to device. */
@Serializable
data class Settings(
    val playback: Playback = Playback(),
    val audiobook: Audiobook = Audiobook(),
    val eq: List<EqCurve> = emptyList(),
    /** Whether the household's admins may see this listener's statistics. */
    val statsVisibleToAdmin: Boolean = false,
    val updatedAt: Instant? = null,
) {
    @Serializable
    data class Playback(val speed: Double = 1.0, val skipIntroMs: Long = 0, val skipOutroMs: Long = 0)

    @Serializable
    data class Audiobook(val speed: Double = 1.0, val skipBackMs: Long = 30_000, val skipForwardMs: Long = 30_000)

    /** One device kind's equaliser. */
    @Serializable
    data class EqCurve(
        val deviceKind: String,
        val enabled: Boolean = false,
        val preampDb: Double = 0.0,
        val bandsDb: List<Double> = emptyList(),
        val presetName: String? = null,
        val updatedAt: Instant? = null,
    )
}

/** `PATCH /me/settings`: what is left `null` stays as it is. */
@Serializable
data class SettingsPatch(
    val playback: Playback? = null,
    val audiobook: Audiobook? = null,
    val eq: Eq? = null,
    val statsVisibleToAdmin: Boolean? = null,
) {
    @Serializable
    data class Playback(val speed: Double? = null, val skipIntroMs: Long? = null, val skipOutroMs: Long? = null)

    @Serializable
    data class Audiobook(val speed: Double? = null, val skipBackMs: Long? = null, val skipForwardMs: Long? = null)

    @Serializable
    data class Eq(
        val deviceKind: String? = null,
        val enabled: Boolean? = null,
        val preampDb: Double? = null,
        val bandsDb: List<Double>? = null,
        val presetName: String? = null,
    )
}

/** One listener's listening over a range (`GET /me/stats`). */
@Serializable
data class ListeningStats(
    val from: Instant,
    val to: Instant,
    /** `day`, `week` or `month`. */
    val group: String = "day",
    val listenedMs: Long = 0,
    val plays: Long = 0,
    val byKind: List<KindTotal> = emptyList(),
    val buckets: List<Bucket> = emptyList(),
    val top: List<TopItem> = emptyList(),
    /** Days in a row, up to today, with any listening. */
    val streakDays: Long = 0,
    val finishedBooks: Long = 0,
) {
    @Serializable
    data class KindTotal(
        /** `track`, `book` or `episode`. */
        val kind: String,
        val listenedMs: Long = 0,
        val plays: Long = 0,
    )

    @Serializable
    data class Bucket(
        /** The bucket's first day, `YYYY-MM-DD` in the listener's time. */
        val start: String,
        val listenedMs: Long = 0,
        val plays: Long = 0,
        val byKind: List<KindTotal> = emptyList(),
    )

    @Serializable
    data class TopItem(val kind: String, val id: String, val listenedMs: Long = 0, val plays: Long = 0)
}

/** One member in the household admin's overview (`GET /family/stats`); names from the members. */
@Serializable
data class MemberStats(
    val userId: String,
    val listenedMs: Long = 0,
    val plays: Long = 0,
    val byKind: List<ListeningStats.KindTotal> = emptyList(),
)

suspend fun OwnAudioClient.settings(): Settings = getJson("me/settings")

suspend fun OwnAudioClient.updateSettings(patch: SettingsPatch): Settings = send("PATCH", "me/settings", patch)

/** Lets the household's admins see this listener's statistics, or not. */
suspend fun OwnAudioClient.setStatsVisibleToAdmin(visible: Boolean): Settings =
    updateSettings(SettingsPatch(statsVisibleToAdmin = visible))

/**
 * @param kinds `track`, `book`, `episode`; all when empty.
 * @param group `day`, `week` (from Monday) or `month`, in the listener's time.
 */
suspend fun OwnAudioClient.listeningStats(
    from: Instant? = null,
    to: Instant? = null,
    group: String? = null,
    kinds: List<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): ListeningStats = getJson(
    "me/stats",
    Query("from" to from?.let(Rfc3339::format), "to" to to?.let(Rfc3339::format), "group" to group)
        .add("kind", kinds)
        .add("tz_offset_minutes", offsetMinutes(zone, to ?: clock.instant())),
)

/** The household admin's overview; `403` for anyone else. */
suspend fun OwnAudioClient.familyStats(from: Instant? = null, to: Instant? = null, kinds: List<String> = emptyList()): List<MemberStats> =
    getJson("family/stats", Query("from" to from?.let(Rfc3339::format), "to" to to?.let(Rfc3339::format)).add("kind", kinds))

internal fun offsetMinutes(zone: ZoneId, at: Instant): Int {
    val offset: ZoneOffset = zone.rules.getOffset(at)
    return offset.totalSeconds / 60
}
