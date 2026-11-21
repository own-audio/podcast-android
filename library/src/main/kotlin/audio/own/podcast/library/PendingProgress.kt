package audio.own.podcast.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Every position meant for a server is written here before it is sent, and removed once the
 * server has it. A dead server, a lost network or a killed app leaves it stored, to be sent when
 * the server is back. One entry per episode: a newer position replaces the older one, so a server
 * that was down for an hour gets one report, not hundreds.
 */
class PendingProgress(private val store: ValueStore<List<Entry>>) {
    @Serializable
    data class Entry(
        /** The episode as the app knows it: [SourcedEpisode.id]. */
        val id: String,
        val progress: ListeningProgress,
    )

    private val mutex = Mutex()

    /** Oldest first, so a server receives positions in the order they happened. */
    suspend fun all(): List<Entry> = mutex.withLock { read().sortedBy { it.progress.updatedAt } }

    suspend fun record(entry: Entry) = mutex.withLock {
        val entries = read().toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) {
            entries += entry
        } else if (!entries[index].progress.updatedAt.isAfter(entry.progress.updatedAt)) {
            entries[index] = entry
        }
        write(entries)
    }

    /** Once the server has it — unless a newer position was recorded since. */
    suspend fun sent(entry: Entry) = mutex.withLock {
        write(read().filterNot { it.id == entry.id && !it.progress.updatedAt.isAfter(entry.progress.updatedAt) })
    }

    /** Everything of a source (a removed server), by the id's prefix. */
    suspend fun removeAll(prefix: String) = mutex.withLock { write(read().filterNot { it.id.startsWith(prefix) }) }

    private suspend fun read(): List<Entry> = store.load() ?: emptyList()

    private suspend fun write(entries: List<Entry>) = store.save(entries.ifEmpty { null })
}
