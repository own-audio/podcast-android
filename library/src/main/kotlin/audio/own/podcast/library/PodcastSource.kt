package audio.own.podcast.library

/** What a source can do beyond listing shows and playing episodes; the screens hide the rest. */
enum class Capability {
    /** Follow a show by its feed URL. */
    ADD_BY_URL,

    /** Stop following a show through the source (a server that only lists what it holds can't). */
    UNFOLLOW,

    /** Ask the server to fetch its feeds now, rather than waiting for its own schedule. */
    REFRESH_ON_SERVER,

    /** Search for shows through the source itself. */
    SEARCH,

    /** The source keeps episode files itself (Audiobookshelf, own.audio). */
    SERVER_DOWNLOADS,

    /** A household that shares shows. */
    FAMILY_SHARING,

    /** Positions follow the listener between devices. */
    SYNCS_PROGRESS,
}

sealed class SourceException(message: String) : Exception(message) {
    class Network(cause: Throwable? = null) : SourceException("network error") {
        init {
            cause?.let(::initCause)
        }
    }

    class NotFound : SourceException("not found")

    class NotAFeed : SourceException("not a feed")

    class Unsupported : SourceException("unsupported")

    class SignInRejected : SourceException("sign-in rejected")

    class Server(val reason: String) : SourceException(reason)
}

/**
 * One place shows come from: the device's own feeds, or a server. Every screen speaks to this.
 *
 * There is no queue here: Up Next is one app-wide list ([UpNext]), since no server keeps a podcast
 * queue.
 */
interface PodcastSource {
    val capabilities: Set<Capability>

    suspend fun shows(): List<Show>

    suspend fun episodes(showId: String): List<Episode>

    suspend fun episode(key: EpisodeKey): Episode

    /** Follows the show at [feedUrl]; returns it with its episodes fetched. */
    suspend fun follow(feedUrl: String): Show

    suspend fun unfollow(showId: String)

    suspend fun updateSettings(settings: ShowSettings, showId: String)

    /**
     * Asks every show (or one) for new episodes; returns those new to the listener. [force] says
     * the listener asked: an automatic refresh may answer from a source's own short-lived cache
     * instead of asking every feed again.
     */
    suspend fun refresh(showId: String? = null, force: Boolean = false): List<Episode>

    suspend fun progress(key: EpisodeKey): ListeningProgress?

    suspend fun allProgress(): Map<EpisodeKey, ListeningProgress>

    /** Returns what the source holds afterwards: a newer position from another device wins. */
    suspend fun saveProgress(progress: ListeningProgress, key: EpisodeKey): ListeningProgress

    suspend fun markPlayed(key: EpisodeKey, played: Boolean)

    /** New Episodes, newest first. */
    suspend fun inbox(limit: Int = Inbox.LIMIT): List<Episode>

    suspend fun dismissFromInbox(keys: List<EpisodeKey>)

    /** Where to play an episode from right now. */
    suspend fun playback(key: EpisodeKey): Playback
}

/** How to play one episode: a URL the player can open (a file on the source, or the publisher's enclosure). */
data class Playback(
    val url: String,
    /** The server's own copy rather than the publisher's. */
    val isServerFile: Boolean,
    /** An HLS playlist the server transcodes as it goes. */
    val isHls: Boolean = false,
    /**
     * A stream the server makes as it plays rather than the file itself: no length to fetch
     * pieces of by Range, so it is never kept ahead on the device.
     */
    val isTranscode: Boolean = false,
    /** When the source stops honouring [url] (a signed link, a token in the query); null: never. */
    val expiresAt: java.time.Instant? = null,
)
