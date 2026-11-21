package audio.own.podcast.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The listener's queue across every source: what plays after the episode in the player, in order.
 * Kept on the device — no server has a podcast queue — with each episode and its show as they
 * were, so it lists and plays offline.
 */
class UpNext(private val store: ValueStore<List<Entry>>) {
    @Serializable
    data class Entry(
        /** The episode as the app knows it: [SourcedEpisode.id]. */
        val id: String,
        /** The source, as [SourceRef.id] names it. */
        val source: String,
        val episode: Episode,
        val show: Show? = null,
    )

    private val mutex = Mutex()
    private var cached: List<Entry>? = null

    suspend fun all(): List<Entry> = mutex.withLock { entries() }

    suspend fun contains(id: String): Boolean = mutex.withLock { entries().any { it.id == id } }

    /** First in line. */
    suspend fun playNext(entry: Entry) = change { list -> listOf(entry) + list.filter { it.id != entry.id } }

    /** Last in line; an episode already queued stays where it is. */
    suspend fun playLater(entry: Entry) = change { list -> if (list.any { it.id == entry.id }) list else list + entry }

    suspend fun remove(id: String) = change { list -> list.filter { it.id != id } }

    /** Everything of a source (a removed server, an unfollowed show). */
    suspend fun removeAll(matches: (Entry) -> Boolean) = change { list -> list.filterNot(matches) }

    /** As a list's drag does: [offsets] move to before the item at [destination] (counted before the move). */
    suspend fun move(offsets: Set<Int>, destination: Int) = change { list ->
        val moving = offsets.sorted().map { list[it] }
        val rest = list.filterIndexed { index, _ -> index !in offsets }.toMutableList()
        val before = offsets.count { it < destination }
        rest.addAll((destination - before).coerceIn(0, rest.size), moving)
        rest
    }

    /** The next to play after [current] (which leaves the queue if it was in it), taken out. */
    suspend fun takeNext(current: String?): Entry? = mutex.withLock {
        val list = entries().filter { current == null || it.id != current }
        val next = list.firstOrNull()
        write(list.drop(1))
        next
    }

    suspend fun clear() = change { emptyList() }

    private suspend fun change(work: (List<Entry>) -> List<Entry>) = mutex.withLock { write(work(entries())) }

    private suspend fun entries(): List<Entry> = cached ?: (store.load() ?: emptyList()).also { cached = it }

    private suspend fun write(entries: List<Entry>) {
        store.save(entries.ifEmpty { null })
        cached = entries
    }
}

/** What plays when an episode ends. Nobody is asked where to start: the newer position wins. */
object PlayOn {
    data class Next(val episode: SourcedEpisode, val show: Show?)

    /**
     * The next playable entry of Up Next (unplayable ones — offline and not downloaded, or from a
     * source that is gone — are taken out on the way); else, with Play New Episodes After Up Next,
     * the newest of [inbox] (newest first) that can play now and isn't played.
     */
    suspend fun next(
        finished: String,
        upNext: UpNext,
        inbox: List<SourcedEpisode>,
        continueWithNewEpisodes: Boolean,
        canPlay: (SourcedEpisode) -> Boolean,
        isPlayed: (SourcedEpisode) -> Boolean,
        shows: (SourcedEpisode) -> Show? = { null },
    ): Next? {
        while (true) {
            val queued = upNext.takeNext(finished) ?: break
            val source = SourceRef.parse(queued.source) ?: continue
            val candidate = SourcedEpisode(source, queued.episode)
            if (canPlay(candidate)) return Next(candidate, queued.show)
        }
        if (!continueWithNewEpisodes) return null
        val new = inbox.firstOrNull { it.id != finished && canPlay(it) && !isPlayed(it) } ?: return null
        return Next(new, shows(new))
    }
}

/**
 * Shows set to add their new episodes to Up Next: what arrived since the last look goes to the end,
 * oldest first. The first look only notes the time, so the back catalogue never floods in.
 */
object AutoQueue {
    data class Result(val lookedAt: Instant, val toQueue: List<SourcedEpisode>)

    /**
     * @param lastLook when this last ran; null the first time.
     * @param inbox New Episodes across every source, newest first.
     * @param autoQueueShows the [SourcedShow.id]s of the shows with [ShowSettings.autoQueue] on.
     */
    fun arrivals(lastLook: Instant?, now: Instant, inbox: List<SourcedEpisode>, autoQueueShows: Set<String>): Result {
        if (lastLook == null || autoQueueShows.isEmpty()) return Result(now, emptyList())
        val arrived = inbox.filter { "${it.source.id}/${it.episode.showId}" in autoQueueShows && it.episode.firstSeenAt.isAfter(lastLook) }
        return Result(now, arrived.reversed())
    }
}
