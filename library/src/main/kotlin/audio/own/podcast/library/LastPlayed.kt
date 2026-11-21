@file:UseSerializers(InstantSerializer::class)

package audio.own.podcast.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * What was in the player when the app went away: shown again in the mini player, paused where it
 * was, so a relaunch (or the system ending the app in the background) doesn't lose it. Written on
 * every position report; Stop and the end of an episode clear it.
 */
@Serializable
data class LastPlayed(
    /** [SourceRef.id]. */
    val source: String,
    val episode: Episode,
    val show: Show? = null,
    val positionMs: Long,
    /** When the position was reached. */
    val at: Instant,
) {
    val id: String get() = SourcedEpisode.id(SourceRef.parse(source) ?: SourceRef.Phone, episode.key)

    /**
     * The position to store when the source holds an older one than this: positions go to a
     * source once a minute, so a kill in between left the newest here only. Null when the source
     * already has it.
     */
    fun missedBy(held: ListeningProgress?): ListeningProgress? {
        if (held != null && (!held.updatedAt.isBefore(at) || held.positionMs == positionMs)) return null
        return ListeningProgress(positionMs, played = false, updatedAt = at)
    }
}

/**
 * How often a position goes past memory: the player reports every 10 s of listening, but the
 * source (the device's store, a server) gets it every [INTERVAL_MS] while playing, and at once on
 * pause, seek, stop, another episode, the end and going to the background.
 */
class PositionThrottle(private val intervalMs: Long = INTERVAL_MS) {
    private var lastId: String? = null
    private var lastAtMs: Long? = null

    /** Whether this report is stored now; when it is, the clock starts again. */
    fun isDue(periodic: Boolean, id: String, played: Boolean, nowMs: Long): Boolean {
        val due = played || !periodic || id != lastId || lastAtMs?.let { nowMs - it >= intervalMs } ?: true
        if (due) {
            lastId = id
            lastAtMs = nowMs
        }
        return due
    }

    companion object {
        const val INTERVAL_MS = 60_000L
    }
}
