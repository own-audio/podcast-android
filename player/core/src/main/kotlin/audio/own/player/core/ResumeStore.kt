package audio.own.player.core

/**
 * Remembers where listening stopped inside a container (an album, playlist, book, feed), so
 * pressing Play on it again picks up there rather than at the first item.
 *
 * Stores *which* item of the container was current, and the position in it. Subsonic servers
 * don't record that, and it works offline. Keyed by a container id the app chooses
 * ("album:…", "playlist:…", scoped by server).
 */
interface ResumeStore {
    data class Resume(val itemId: String, val positionMs: Long)

    fun saveResume(containerId: String, itemId: String, positionMs: Long)

    /** Null when this container has never been played: start at the first item, position 0. */
    fun loadResume(containerId: String): Resume?

    /**
     * Called when a container plays to its end: the next Play should start over rather than
     * resume onto the last second of the final item.
     */
    fun clearResume(containerId: String)
}

/** For tests and previews; the app keeps its own on disk behind the same interface. */
class InMemoryResumeStore : ResumeStore {
    private val entries = HashMap<String, ResumeStore.Resume>()

    @Synchronized
    override fun saveResume(containerId: String, itemId: String, positionMs: Long) {
        entries[containerId] = ResumeStore.Resume(itemId, positionMs)
    }

    @Synchronized
    override fun loadResume(containerId: String): ResumeStore.Resume? = entries[containerId]

    @Synchronized
    override fun clearResume(containerId: String) {
        entries.remove(containerId)
    }
}
