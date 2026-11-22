package audio.own.podcast.library

import audio.own.kit.feed.FeedException
import audio.own.kit.feed.FeedFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.text.Collator
import java.time.Instant
import java.util.UUID

/** The listener's state over the device's feeds; keys are [EpisodeKey] texts. */
@Serializable
data class FeedLibraryState(
    val progress: Map<String, ListeningProgress> = emptyMap(),
    /** Episodes taken out of the inbox without being played. */
    val dismissed: Set<String> = emptySet(),
)

/**
 * Where [FeedLibrary] keeps its shows, one record per show so a large back catalogue need not be
 * held in memory, and its state. Every call is expected to be written when it returns.
 */
interface FeedLibraryStore {
    suspend fun records(): List<ShowRecord>

    suspend fun record(showId: String): ShowRecord?

    suspend fun put(record: ShowRecord)

    suspend fun delete(showId: String)

    suspend fun state(): FeedLibraryState

    suspend fun putState(state: FeedLibraryState)
}

/** Fetches a feed; [FeedFetcher.fetch] in the app, a fake in tests. */
fun interface FeedLoader {
    suspend fun fetch(url: String, validators: FeedFetcher.Validators?): FeedFetcher.Result
}

/**
 * The device's own library: the shows the listener follows by feed, their episodes, positions and
 * the inbox. With no server this *is* the library; with one, it is what the app falls back to
 * offline. Every change is in [store] before the call returns, so a kill never loses a follow or a
 * position.
 *
 * @param queued what is in Up Next, so a refresh keeps an episode the feed dropped while it waits there.
 */
class FeedLibrary(
    private val store: FeedLibraryStore,
    private val loader: FeedLoader,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val queued: suspend () -> Collection<EpisodeKey> = { emptyList() },
) : PodcastSource, GPodderLibrary {
    override val capabilities: Set<Capability> = setOf(Capability.ADD_BY_URL, Capability.UNFOLLOW)

    private val mutex = Mutex()

    // MARK: shows and episodes

    override suspend fun shows(): List<Show> {
        val collator = Collator.getInstance()
        return store.records().map { it.show }.sortedWith(compareBy(collator) { it.title })
    }

    /** Newest first, or oldest first for a serial show. */
    override suspend fun episodes(showId: String): List<Episode> {
        val record = store.record(showId) ?: throw SourceException.NotFound()
        return Inbox.ordered(record.episodes, record.show.isSerial)
    }

    override suspend fun episode(key: EpisodeKey): Episode =
        store.record(key.showId)?.episodes?.firstOrNull { it.id == key.episodeId } ?: throw SourceException.NotFound()

    override suspend fun follow(feedUrl: String): Show {
        store.records().firstOrNull { it.show.feedUrl == feedUrl }?.let { return it.show }
        val result = try {
            loader.fetch(feedUrl, null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: FeedException.NotAFeed) {
            throw SourceException.NotAFeed()
        } catch (_: FeedException.Malformed) {
            // Something answered, so the address is reachable; what it sent can't be read as a feed.
            throw SourceException.NotAFeed()
        } catch (error: Exception) {
            throw SourceException.Network(error)
        }
        if (result !is FeedFetcher.Result.Fetched) throw SourceException.Network()
        return mutex.withLock {
            // Two follows of the same feed may have raced past the check above.
            store.records().firstOrNull { it.show.feedUrl == feedUrl }?.let { return@withLock it.show }
            val record = FeedMerge.followed(newId(), result.document, result.validators, result.finalUrl, now())
            store.put(record)
            record.show
        }
    }

    override suspend fun unfollow(showId: String) = mutex.withLock {
        store.delete(showId)
        val prefix = "$showId|"
        val state = store.state()
        store.putState(
            state.copy(
                progress = state.progress.filterKeys { !it.startsWith(prefix) },
                dismissed = state.dismissed.filterTo(mutableSetOf()) { !it.startsWith(prefix) },
            ),
        )
    }

    override suspend fun updateSettings(settings: ShowSettings, showId: String) = mutex.withLock {
        val record = store.record(showId) ?: throw SourceException.NotFound()
        store.put(record.copy(show = record.show.copy(settings = settings)))
    }

    // MARK: refreshing

    /**
     * Conditional fetches, four at a time. New episodes are stamped with the time this device
     * first saw them, which is what puts them in the inbox. A failing feed is left as it was and
     * does not stop the others. Returns the new episodes; shows set to auto-queue get them into Up
     * Next through [AutoQueue].
     */
    override suspend fun refresh(showId: String?, force: Boolean): List<Episode> = coroutineScope {
        val targets = store.records().filter { (showId == null || it.show.id == showId) && it.show.feedUrl != null }
        val permits = Semaphore(PARALLEL_FETCHES)
        targets.map { target ->
            async {
                val result = permits.withPermit {
                    try {
                        loader.fetch(target.show.feedUrl!!, target.validators)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@async emptyList()
                merge(target.show.id, result)
            }
        }.awaitAll().flatten()
    }

    private suspend fun merge(showId: String, result: FeedFetcher.Result): List<Episode> {
        val queued = queued()
        return mutex.withLock {
            // Read again: the show may have been unfollowed or changed while its feed was fetched.
            val record = store.record(showId) ?: return@withLock emptyList()
            val keep = FeedMerge.keep(showId, queued, progressByKey(store.state()))
            val merged = FeedMerge.merge(record, result, now(), keep)
            store.put(merged.record)
            merged.fresh
        }
    }

    // MARK: the inbox

    override suspend fun inbox(limit: Int): List<Episode> {
        val state = store.state()
        val out = mutableListOf<Episode>()
        for (record in store.records()) {
            for (episode in record.episodes) {
                val key = episode.key.toString()
                if (Inbox.isInInbox(episode, record.show.settings, record.show.followedAt, record.latestAtFollow, state.progress[key], key in state.dismissed)) {
                    out += episode
                }
            }
        }
        return Inbox.newestFirst(out, limit)
    }

    override suspend fun dismissFromInbox(keys: List<EpisodeKey>) = mutex.withLock {
        val state = store.state()
        store.putState(state.copy(dismissed = state.dismissed + keys.map { it.toString() }))
    }

    // MARK: progress

    override suspend fun progress(key: EpisodeKey): ListeningProgress? = store.state().progress[key.toString()]

    override suspend fun allProgress(): Map<EpisodeKey, ListeningProgress> = progressByKey(store.state())

    /** The newer of what is kept and what comes in wins, so a late report from a stale player cannot move a position back. */
    override suspend fun saveProgress(progress: ListeningProgress, key: EpisodeKey): ListeningProgress = mutex.withLock {
        saveLocked(progress, key)
    }

    /** A position reached while playing; heard once it reaches the end. */
    suspend fun record(positionMs: Long, key: EpisodeKey, at: Instant? = null): ListeningProgress = mutex.withLock {
        val duration = store.record(key.showId)?.episodes?.firstOrNull { it.id == key.episodeId }?.durationMs
        val held = store.state().progress[key.toString()]
        // Starting a heard episode again from 0 keeps it heard until it is marked otherwise.
        val played = ListeningProgress.reachedEnd(positionMs, duration) || (held?.played == true && positionMs == 0L)
        saveLocked(ListeningProgress(positionMs, played, at ?: now()), key)
    }

    override suspend fun markPlayed(key: EpisodeKey, played: Boolean) = mutex.withLock {
        val state = store.state()
        val held = state.progress[key.toString()]
        val progress = ListeningProgress(if (played) held?.positionMs ?: 0 else 0, played, now())
        store.putState(state.copy(progress = state.progress + (key.toString() to progress)))
    }

    override suspend fun playback(key: EpisodeKey): Playback {
        val url = episode(key).audioUrl ?: throw SourceException.NotFound()
        return Playback(url, isServerFile = false)
    }

    private suspend fun saveLocked(progress: ListeningProgress, key: EpisodeKey): ListeningProgress {
        val state = store.state()
        val kept = state.progress[key.toString()]
        if (kept != null && kept.updatedAt.isAfter(progress.updatedAt)) return kept
        store.putState(state.copy(progress = state.progress + (key.toString() to progress)))
        return progress
    }

    private fun progressByKey(state: FeedLibraryState): Map<EpisodeKey, ListeningProgress> =
        state.progress.mapNotNull { (text, value) -> EpisodeKey.parse(text)?.let { it to value } }.toMap()

    companion object {
        const val PARALLEL_FETCHES = 4
    }
}
