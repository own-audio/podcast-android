package audio.own.podcast.library

import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfError
import audio.own.kit.audiobookshelf.DEFAULT_SUPPORTED_MIME_TYPES
import audio.own.kit.audiobookshelf.DeviceInfo
import audio.own.kit.audiobookshelf.Library
import audio.own.kit.audiobookshelf.PodcastEpisode
import audio.own.kit.audiobookshelf.PodcastItem
import audio.own.kit.audiobookshelf.PodcastSearchResult
import audio.own.kit.audiobookshelf.ProgressUpdate
import audio.own.kit.audiobookshelf.allPodcasts
import audio.own.kit.audiobookshelf.episodeProgress
import audio.own.kit.audiobookshelf.me
import audio.own.kit.audiobookshelf.playEpisode
import audio.own.kit.audiobookshelf.podcast
import audio.own.kit.audiobookshelf.podcastLibraries
import audio.own.kit.audiobookshelf.recentEpisodes
import audio.own.kit.audiobookshelf.searchPodcasts
import audio.own.kit.audiobookshelf.subscribe
import audio.own.kit.audiobookshelf.updateEpisodeProgress
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedValues
import audio.own.kit.feed.HtmlText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An Audiobookshelf server's podcast libraries. A show's episodes are the ones the server
 * holds; for a show with a feed, the feed's other episodes are listed too, played from the
 * publisher, their positions kept on the device (the server has nowhere to put them). New
 * Episodes comes from each library's recent episodes, one request per library, plus the
 * feed-only episodes of the feeds read this session.
 *
 * Subscribing needs an account that may upload; without that the app can browse, play and sync
 * positions, and adding shows is hidden. Unfollowing is always hidden: on Audiobookshelf it
 * deletes the show, and its files, for everyone on the server.
 *
 * @param feeds fetches a show's feed; [FeedFetcher.fetch] with the plain client (publishers must
 *   never see the server's token).
 * @param canDecode whether the device plays a content type itself; anything else is asked from
 *   the server as an HLS transcode.
 */
class AudiobookshelfPodcastSource(
    val client: AudiobookshelfClient,
    private val store: ListeningStore,
    private val feeds: FeedLoader,
    private val canAddShows: Boolean,
    private val device: DeviceInfo = DeviceInfo(
        deviceId = "own-audio-podcasts",
        clientName = "own.audio Podcasts",
        clientVersion = "1",
        manufacturer = "",
        model = "",
    ),
    private val canDecode: (String?) -> Boolean = PlayableAudio::isPlayable,
    private val now: () -> Instant = Instant::now,
    /** Waits until the server has written an HLS playlist's first segment. Replaced in tests. */
    private val awaitPlaylist: suspend (HttpUrl) -> Unit = { waitForPlaylist(client.http.client, it) },
) : PodcastSource {
    /** A feed read this session: validators for the next conditional fetch, the episodes only it has, when it was asked. */
    private data class FeedCache(val validators: FeedFetcher.Validators?, val feedOnly: List<Episode>, val fetchedAt: Instant)

    private val feedCache = ConcurrentHashMap<String, FeedCache>()
    private val items = ConcurrentHashMap<String, PodcastItem>()

    override val capabilities: Set<Capability> =
        setOf(Capability.SEARCH, Capability.SYNCS_PROGRESS, Capability.SERVER_DOWNLOADS) + if (canAddShows) setOf(Capability.ADD_BY_URL) else emptySet()

    // MARK: shows and episodes

    override suspend fun shows(): List<Show> {
        val out = mutableListOf<Show>()
        for (library in translating { client.podcastLibraries() }) {
            for (item in translating { client.allPodcasts(library.id) }) {
                if (item.isMissing) continue
                // A list entry has no episodes: keep a detail already read.
                items.putIfAbsent(item.id, item)
                out += show(item)
            }
        }
        val collator = Collator.getInstance()
        return out.sortedWith(compareBy(collator) { it.title })
    }

    override suspend fun episodes(showId: String): List<Episode> {
        val item = translating { client.podcast(showId) }
        items[showId] = item
        val serverEpisodes = item.media.episodes.orEmpty()
        val serial = item.media.metadata.type == "serial"
        val all = serverEpisodes.map { episode(it, showId) } + feedOnlyEpisodes(item, serverEpisodes)
        return Inbox.ordered(store.observe(all, showId, serial), serial)
    }

    override suspend fun episode(key: EpisodeKey): Episode {
        feedCache[key.showId]?.feedOnly?.firstOrNull { it.id == key.episodeId }?.let { return it }
        return episodes(key.showId).firstOrNull { it.id == key.episodeId } ?: throw SourceException.NotFound()
    }

    /**
     * The feed's episodes the server doesn't hold, matched by GUID, then enclosure URL, then
     * title. Read at most every ten minutes per show unless [force], conditionally; a feed that
     * fails leaves the server's episodes as they are.
     */
    private suspend fun feedOnlyEpisodes(item: PodcastItem, serverEpisodes: List<PodcastEpisode>, force: Boolean = false): List<Episode> {
        val feedUrl = FeedValues.url(item.media.metadata.feedUrl) ?: return emptyList()
        val cached = feedCache[item.id]
        if (!force && cached != null && Duration.between(cached.fetchedAt, now()) < FEED_REUSE) return cached.feedOnly
        val result = try {
            feeds.fetch(feedUrl, cached?.validators)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return cached?.feedOnly.orEmpty()
        }
        if (result !is FeedFetcher.Result.Fetched) {
            if (cached != null) feedCache[item.id] = cached.copy(fetchedAt = now())
            return cached?.feedOnly.orEmpty()
        }
        val guids = serverEpisodes.mapNotNullTo(HashSet()) { it.guid }
        val enclosures = serverEpisodes.mapNotNullTo(HashSet()) { it.enclosure?.url }
        val titles = serverEpisodes.mapTo(HashSet()) { it.title.lowercase() }
        val known = cached?.feedOnly?.associateBy { it.id }.orEmpty()
        val feedOnly = result.document.episodes
            .filter { feed ->
                val enclosure = feed.enclosure ?: return@filter false
                feed.id !in guids && enclosure.url !in enclosures && feed.title.lowercase() !in titles
            }
            .distinctBy { it.id }
            .map { feed ->
                val id = FEED_PREFIX + feed.id
                Episode.from(feed, item.id, known[id]?.firstSeenAt ?: now()).copy(id = id)
            }
        feedCache[item.id] = FeedCache(result.validators, feedOnly, now())
        return feedOnly
    }

    // MARK: following

    /** The account's podcast libraries, in the server's order. */
    suspend fun podcastLibraries(): List<Library> =
        translating { client.podcastLibraries() }.sortedBy { it.displayOrder ?: 0 }

    override suspend fun follow(feedUrl: String): Show = follow(feedUrl, libraryId = null)

    /** Into [libraryId]'s first folder; null: the first library that has a folder. */
    suspend fun follow(feedUrl: String, libraryId: String?): Show {
        if (!canAddShows) throw SourceException.Unsupported()
        shows().firstOrNull { it.feedUrl == feedUrl }?.let { return it }
        val library = translating { client.podcastLibraries() }
            .firstOrNull { (libraryId == null || it.id == libraryId) && !it.folders.isNullOrEmpty() }
            ?: throw SourceException.Unsupported()
        val folder = library.folders!!.first()
        val result = try {
            feeds.fetch(feedUrl, null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw SourceException.NotAFeed()
        }
        val title = (result as? FeedFetcher.Result.Fetched)?.document?.show?.title?.ifEmpty { null } ?: throw SourceException.NotAFeed()
        val item = translating { client.subscribe(feedUrl, title, library.id, folder) }
        items[item.id] = item
        return show(item)
    }

    /** Removing a show deletes it, and its files, on the server for everyone: never from a player. */
    override suspend fun unfollow(showId: String): Unit = throw SourceException.Unsupported()

    override suspend fun updateSettings(settings: ShowSettings, showId: String) = store.setSettings(settings, showId)

    /** The iTunes directory, asked through the server. */
    suspend fun search(term: String): List<PodcastSearchResult> = translating { client.searchPodcasts(term) }

    // MARK: refreshing

    /**
     * The feeds of one show, or of every show with a feed, read again, and the libraries' recent
     * episodes; returns the episodes first seen now. Without [force] a feed read in the last ten
     * minutes is kept as it was, so coming back to the app does not fetch every feed again.
     */
    override suspend fun refresh(showId: String?, force: Boolean): List<Episode> {
        val started = now()
        val targets: List<PodcastItem> = if (showId != null) {
            listOf(translating { client.podcast(showId) }.also { items[showId] = it })
        } else {
            shows()
            items.values.filter { it.media.metadata.feedUrl != null }
        }
        val fresh = LinkedHashMap<EpisodeKey, Episode>()
        for (item in targets) {
            // A list entry carries no episodes, so for the whole library only the feed is matched
            // against what the last detail read held.
            val serverEpisodes = item.media.episodes.orEmpty()
            val feedOnly = feedOnlyEpisodes(item, serverEpisodes, force)
            val all = serverEpisodes.map { episode(it, item.id) } + feedOnly
            store.observe(all, item.id, item.media.metadata.type == "serial")
                .filter { !it.firstSeenAt.isBefore(started) }
                .forEach { fresh[it.key] = it }
        }
        candidates().filter { !it.firstSeenAt.isBefore(started) }.forEach { fresh.putIfAbsent(it.key, it) }
        return fresh.values.toList()
    }

    // MARK: the inbox

    override suspend fun inbox(limit: Int): List<Episode> =
        Inbox.newestFirst(candidates().filter { store.isInInbox(it) }, limit)

    /** The libraries' recent episodes and the feed-only ones read this session, with their first sightings. */
    private suspend fun candidates(): List<Episode> {
        val out = mutableListOf<Episode>()
        for (library in translating { client.podcastLibraries() }) {
            val recent = translating { client.recentEpisodes(library.id, limit = RECENT_LIMIT) }
            for ((showId, episodes) in recent.groupBy { it.libraryItemId.orEmpty() }) {
                if (showId.isEmpty()) continue
                out += store.observe(episodes.map { episode(it, showId) }, showId)
            }
        }
        for ((showId, cache) in feedCache) out += store.observe(cache.feedOnly, showId)
        return out.distinctBy { it.key }
    }

    override suspend fun dismissFromInbox(keys: List<EpisodeKey>) = store.dismiss(keys)

    // MARK: progress

    /** The newer of the device's and the server's. */
    override suspend fun progress(key: EpisodeKey): ListeningProgress? {
        val local = store.progress(key)
        if (isFeedOnly(key)) return local
        val server = try {
            client.episodeProgress(key.showId, key.episodeId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return local
        val remote = ListeningProgress((server.currentTime * 1000).toLong(), server.isFinished, Instant.ofEpochMilli(server.lastUpdate))
        return if (local != null && !local.updatedAt.isBefore(remote.updatedAt)) local else remote
    }

    override suspend fun allProgress(): Map<EpisodeKey, ListeningProgress> {
        val out = store.allProgress().toMutableMap()
        for (entry in translating { client.me() }.mediaProgress) {
            val episodeId = entry.episodeId ?: continue
            val key = EpisodeKey(entry.libraryItemId, episodeId)
            val remote = ListeningProgress((entry.currentTime * 1000).toLong(), entry.isFinished, Instant.ofEpochMilli(entry.lastUpdate))
            val local = out[key]
            if (local != null && !local.updatedAt.isBefore(remote.updatedAt)) continue
            out[key] = remote
        }
        return out
    }

    /**
     * On the device first, then to the server for episodes it holds, in seconds. A send that
     * fails leaves the device's record, and the app sends it again later ([PendingProgress]).
     */
    override suspend fun saveProgress(progress: ListeningProgress, key: EpisodeKey): ListeningProgress {
        val kept = store.save(progress, key)
        if (kept != progress || isFeedOnly(key)) return kept
        val durationMs = knownEpisode(key)?.durationMs
            ?: try {
                episode(key).durationMs
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        val update = ProgressUpdate.of(progress.positionMs / 1000.0, (durationMs ?: 0) / 1000.0, progress.played)
        translating { client.updateEpisodeProgress(key.showId, key.episodeId, update) }
        return kept
    }

    override suspend fun markPlayed(key: EpisodeKey, played: Boolean) {
        val position = if (played) store.progress(key)?.positionMs ?: 0 else 0
        saveProgress(ListeningProgress(position, played, now()), key)
    }

    // MARK: playing

    /**
     * The server's file with the token in the URL, or a feed-only episode from the publisher.
     * Only a format the device cannot decode goes through the server's transcoder, as HLS.
     */
    override suspend fun playback(key: EpisodeKey): Playback {
        if (isFeedOnly(key)) {
            val url = episode(key).audioUrl ?: throw SourceException.NotFound()
            return Playback(url, isServerFile = false)
        }
        val known = items[key.showId]?.takeIf { it.media.episodes != null }
        val item = known ?: translating { client.podcast(key.showId) }.also { items[key.showId] = it }
        val file = item.media.episodes?.firstOrNull { it.id == key.episodeId }?.audioFile ?: throw SourceException.NotFound()
        val token = translating { client.freshToken() }
        if (!canDecode(file.mimeType)) {
            // The play session says what the device plays, so the server transcodes only this.
            val supported = DEFAULT_SUPPORTED_MIME_TYPES.filter(canDecode)
            val session = translating { client.playEpisode(key.showId, key.episodeId, device, supported) }
            val track = session.audioTracks.firstOrNull() ?: throw SourceException.NotFound()
            val url = client.mediaUrl(track.contentUrl, token)
            if (session.isHls) awaitPlaylist(url)
            return Playback(url.toString(), isServerFile = true, isHls = session.isHls, isTranscode = session.isHls)
        }
        val ino = file.ino ?: throw SourceException.NotFound()
        return Playback(client.fileUrl(key.showId, ino, token).toString(), isServerFile = true)
    }

    // MARK: mapping

    private fun knownEpisode(key: EpisodeKey): Episode? =
        items[key.showId]?.media?.episodes?.firstOrNull { it.id == key.episodeId }?.let { episode(it, key.showId) }

    private suspend fun show(item: PodcastItem): Show {
        val metadata = item.media.metadata
        return Show(
            id = item.id,
            // Audiobookshelf passes titles on as the feed (or YouTube) wrote them, entities and all.
            title = HtmlText.decodeEntities(metadata.title),
            author = metadata.author?.let(HtmlText::decodeEntities),
            description = metadata.description?.let(HtmlText::plain)?.ifEmpty { null },
            imageUrl = if (item.media.coverPath != null) client.coverUrl(item.id, width = 600).toString() else FeedValues.url(metadata.imageUrl),
            feedUrl = FeedValues.url(metadata.feedUrl),
            link = FeedValues.url(metadata.itunesPageUrl),
            categories = metadata.genres,
            isSerial = metadata.type == "serial",
            isExplicit = metadata.explicit,
            followedAt = store.firstSeen(item.id) ?: Instant.ofEpochMilli(item.addedAt),
            settings = store.settings(item.id),
            collection = item.libraryId,
        )
    }

    private fun episode(episode: PodcastEpisode, showId: String) = Episode(
        id = episode.id,
        showId = showId,
        title = HtmlText.decodeEntities(episode.title),
        description = episode.description?.let(HtmlText::plain)?.ifEmpty { null },
        publishedAt = episode.publishedAt?.let(Instant::ofEpochMilli),
        durationMs = if (episode.duration > 0) (episode.duration * 1000).toLong() else null,
        audioUrl = null,
        audioType = episode.audioFile?.mimeType,
        audioBytes = episode.size ?: episode.audioFile?.metadata?.size,
        season = episode.season?.toIntOrNull(),
        number = episode.episode?.toIntOrNull(),
        kind = episode.episodeType,
        chapters = episode.chapters.map { Chapter((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.title) },
        firstSeenAt = now(),
    )

    private suspend fun <T> translating(work: suspend () -> T): T = try {
        work()
    } catch (error: AudiobookshelfError) {
        throw when (error) {
            AudiobookshelfError.SignedOut, AudiobookshelfError.InvalidCredentials -> SourceException.SignInRejected()
            is AudiobookshelfError.Http -> when (error.status) {
                401 -> SourceException.SignInRejected()
                403 -> SourceException.Unsupported()
                404 -> SourceException.NotFound()
                else -> SourceException.Server(error.serverMessage ?: "HTTP ${error.status}")
            }
            is AudiobookshelfError.Network, AudiobookshelfError.Offline -> SourceException.Network(error)
            else -> SourceException.Server(error.message ?: "error")
        }
    }

    companion object {
        /** Feed-only episodes carry this before the feed's own id, so a key says which kind it is. */
        const val FEED_PREFIX = "feed:"

        /** How long an automatic refresh trusts a feed read before. */
        val FEED_REUSE: Duration = Duration.ofMinutes(10)

        const val RECENT_LIMIT = 100

        fun isFeedOnly(key: EpisodeKey) = key.episodeId.startsWith(FEED_PREFIX)

        /**
         * The server names an HLS playlist before its transcoder has written it, and lists every
         * segment at once while writing them one by one; a player asking too early gets a 404 and
         * gives up. So the playlist and its first segment are asked for every half second, for
         * up to 20 seconds, before playing.
         */
        suspend fun waitForPlaylist(http: OkHttpClient, url: HttpUrl, attempts: Int = 40, pauseMs: Long = 500) {
            val quick = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
            suspend fun fetch(target: HttpUrl): ByteArray? = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(target).header("Range", "bytes=0-1023").build()
                try {
                    quick.newCall(request).execute().use { if (it.code == 200 || it.code == 206) it.body.bytes() else null }
                } catch (_: IOException) {
                    null
                }
            }
            var segment: HttpUrl? = null
            repeat(attempts) {
                if (segment == null) {
                    val playlist = fetch(url)?.decodeToString()
                    if (playlist != null && playlist.startsWith("#EXTM3U")) {
                        val first = playlist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return
                        segment = url.resolve(first)
                    }
                }
                segment?.let { if (fetch(it) != null) return }
                delay(pauseMs)
            }
        }
    }
}
