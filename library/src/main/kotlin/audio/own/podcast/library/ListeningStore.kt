@file:UseSerializers(InstantSerializer::class)

package audio.own.podcast.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/** Keys are [EpisodeKey] texts or show ids. */
@Serializable
data class ListeningState(
    val progress: Map<String, ListeningProgress> = emptyMap(),
    val dismissed: Set<String> = emptySet(),
    val episodeFirstSeen: Map<String, Instant> = emptyMap(),
    val showFirstSeen: Map<String, Instant> = emptyMap(),
    val latestAtFirstSight: Map<String, String> = emptyMap(),
    val settings: Map<String, ShowSettings> = emptyMap(),
)

/**
 * First sightings, as a pure step: what has not been seen before is stamped [now]. The first time
 * a show is seen, its newest episode is remembered as the one old episode New Episodes shows for
 * it (none for a serial show, as on the device's feeds).
 */
object FirstSighting {
    data class Result(val state: ListeningState, val episodes: List<Episode>, val changed: Boolean)

    fun observe(state: ListeningState, episodes: List<Episode>, showId: String, now: Instant, serial: Boolean = false): Result {
        var changed = false
        val showFirstSeen = state.showFirstSeen.toMutableMap()
        val latest = state.latestAtFirstSight.toMutableMap()
        if (showId !in showFirstSeen) {
            showFirstSeen[showId] = now
            Inbox.latestAtFollow(episodes, serial)?.let { latest[showId] = it }
            changed = true
        }
        val firstSeen = state.episodeFirstSeen.toMutableMap()
        val out = episodes.map { episode ->
            val key = episode.key.toString()
            val seen = firstSeen[key]
            if (seen != null) {
                episode.copy(firstSeenAt = seen)
            } else {
                firstSeen[key] = now
                changed = true
                episode.copy(firstSeenAt = now)
            }
        }
        val next = state.copy(episodeFirstSeen = firstSeen, showFirstSeen = showFirstSeen, latestAtFirstSight = latest)
        return Result(next, out, changed)
    }
}

/**
 * What a server source keeps on the device for itself: when each show and episode was first seen
 * (what makes an episode new to the listener), the inbox's dismissals, the listener's own settings
 * per show, and positions — written here first, then sent, and the only record for episodes the
 * server does not hold. The same inbox rules as [FeedLibrary].
 */
class ListeningStore(
    private val store: ValueStore<ListeningState>,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private var cached: ListeningState? = null

    /** Stamps what has not been seen before and returns the episodes with their first-seen time. */
    suspend fun observe(episodes: List<Episode>, showId: String, serial: Boolean = false): List<Episode> = mutex.withLock {
        val result = FirstSighting.observe(state(), episodes, showId, now(), serial)
        if (result.changed) write(result.state)
        result.episodes
    }

    suspend fun firstSeen(showId: String): Instant? = mutex.withLock { state().showFirstSeen[showId] }

    /** New to the listener, unplayed, not dismissed, from a show that wants the inbox. */
    suspend fun isInInbox(episode: Episode): Boolean = mutex.withLock {
        val state = state()
        val key = episode.key.toString()
        Inbox.isInInbox(
            episode,
            state.settings[episode.showId] ?: ShowSettings(),
            state.showFirstSeen[episode.showId],
            state.latestAtFirstSight[episode.showId],
            state.progress[key],
            key in state.dismissed,
        )
    }

    suspend fun dismiss(keys: List<EpisodeKey>) = mutex.withLock {
        val state = state()
        write(state.copy(dismissed = state.dismissed + keys.map { it.toString() }))
    }

    suspend fun settings(showId: String): ShowSettings = mutex.withLock { state().settings[showId] ?: ShowSettings() }

    suspend fun setSettings(settings: ShowSettings, showId: String) = mutex.withLock {
        val state = state()
        write(state.copy(settings = state.settings + (showId to settings)))
    }

    suspend fun progress(key: EpisodeKey): ListeningProgress? = mutex.withLock { state().progress[key.toString()] }

    suspend fun allProgress(): Map<EpisodeKey, ListeningProgress> = mutex.withLock {
        state().progress.mapNotNull { (text, value) -> EpisodeKey.parse(text)?.let { it to value } }.toMap()
    }

    /** The newer wins. */
    suspend fun save(progress: ListeningProgress, key: EpisodeKey): ListeningProgress = mutex.withLock {
        val state = state()
        val kept = state.progress[key.toString()]
        if (kept != null && kept.updatedAt.isAfter(progress.updatedAt)) return@withLock kept
        write(state.copy(progress = state.progress + (key.toString() to progress)))
        progress
    }

    /** Everything of a show the listener no longer follows. */
    suspend fun forget(showId: String) = mutex.withLock {
        val prefix = "$showId|"
        val state = state()
        write(
            ListeningState(
                progress = state.progress.filterKeys { !it.startsWith(prefix) },
                dismissed = state.dismissed.filterTo(mutableSetOf()) { !it.startsWith(prefix) },
                episodeFirstSeen = state.episodeFirstSeen.filterKeys { !it.startsWith(prefix) },
                showFirstSeen = state.showFirstSeen - showId,
                latestAtFirstSight = state.latestAtFirstSight - showId,
                settings = state.settings - showId,
            ),
        )
    }

    private suspend fun state(): ListeningState = cached ?: (store.load() ?: ListeningState()).also { cached = it }

    private suspend fun write(state: ListeningState) {
        store.save(state)
        cached = state
    }
}
