@file:UseSerializers(InstantSerializer::class)

package audio.own.podcast.library

import audio.own.kit.feed.FeedValues
import audio.own.kit.feed.HtmlText
import audio.own.kit.ownaudio.Change
import audio.own.kit.ownaudio.DirectoryCategory
import audio.own.kit.ownaudio.DirectoryPreview
import audio.own.kit.ownaudio.DirectoryShow
import audio.own.kit.ownaudio.ItemKind
import audio.own.kit.ownaudio.OfflineChange
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.kit.ownaudio.Page
import audio.own.kit.ownaudio.PlaybackCapabilities
import audio.own.kit.ownaudio.Podcast
import audio.own.kit.ownaudio.SnapshotLine
import audio.own.kit.ownaudio.changes
import audio.own.kit.ownaudio.directory
import audio.own.kit.ownaudio.directoryCategories
import audio.own.kit.ownaudio.directoryPreview
import audio.own.kit.ownaudio.directorySearch
import audio.own.kit.ownaudio.directorySimilar
import audio.own.kit.ownaudio.events
import audio.own.kit.ownaudio.follow
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.httpStatus
import audio.own.kit.ownaudio.pageAll
import audio.own.kit.ownaudio.playbackInfo
import audio.own.kit.ownaudio.push
import audio.own.kit.ownaudio.refresh
import audio.own.kit.ownaudio.refreshAllPodcasts
import audio.own.kit.ownaudio.saveProgress
import audio.own.kit.ownaudio.setPodcastVisibility
import audio.own.kit.ownaudio.snapshot
import audio.own.kit.ownaudio.unfollow
import audio.own.kit.ownaudio.unplayed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import audio.own.kit.ownaudio.Episode as ServerEpisode
import audio.own.kit.ownaudio.Progress as ServerProgress
import audio.own.kit.ownaudio.episode as fetchEpisode
import audio.own.kit.ownaudio.progress as fetchProgress

/**
 * What the device holds of an own.audio server's podcasts: the shows, their episodes and the
 * listener's positions, as the change feed last left them, and the cursor to read on from.
 * Kept whole for now; it moves into Room with the rest of the catalogue.
 */
@Serializable
data class OwnAudioCatalogue(
    val cursor: String? = null,
    val podcasts: Map<String, Podcast> = emptyMap(),
    val episodes: Map<String, ServerEpisode> = emptyMap(),
    /** By episode id. */
    val progress: Map<String, ServerProgress> = emptyMap(),
    val syncedAt: Instant? = null,
)

/**
 * An own.audio server's podcasts (`/api/v2`): the shows the household follows, their episodes
 * as the server keeps them, positions that follow the listener, and streams the server signs.
 *
 * Reading comes from the change feed, not from lists: a snapshot of `podcast`, `episode` and
 * `progress` once, then `/sync/changes` from its cursor, so opening New Episodes costs one small
 * request however many shows there are. New Episodes follows the same first-sighting rules as
 * the other sources ([ListeningStore]).
 *
 * Shows can be followed, unfollowed and refreshed on the server. Per-show settings stay on the
 * device.
 *
 * @param modules the server's modules that are on (`Discovery.has`), for the directory.
 * @param newKey makes `Idempotency-Key`s for follows.
 * @param sleep waits between bulk follows; replaced in tests.
 */
class OwnAudioPodcastSource(
    client: OwnAudioClient,
    private val store: ListeningStore,
    private val catalogueStore: ValueStore<OwnAudioCatalogue>,
    modules: Set<String> = emptySet(),
    private val now: () -> Instant = Instant::now,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : PodcastSource {
    /**
     * The client at the address in use. The app swaps it when the address or the sign-in changes,
     * so one source (and one sync of its catalogue) serves the server for the life of the process.
     */
    @Volatile
    var client: OwnAudioClient = client

    /** The server's modules that are on; learned with the address, after the source is made. */
    @Volatile
    var modules: Set<String> = modules

    private val canBrowse get() = MODULE_DISCOVERY in modules
    private val canSearch get() = MODULE_SEARCH in modules

    override val capabilities: Set<Capability>
        get() = setOf(
            Capability.ADD_BY_URL,
            Capability.UNFOLLOW,
            Capability.REFRESH_ON_SERVER,
            Capability.SYNCS_PROGRESS,
            Capability.SERVER_DOWNLOADS,
            Capability.FAMILY_SHARING,
        ) + if (canSearch) setOf(Capability.SEARCH) else emptySet()

    private val directoryInstance by lazy { Directory() }

    /** The server's directory for Discover, or null when it has neither module. */
    val directory: Directory? get() = if (canBrowse || canSearch) directoryInstance else null

    // MARK: the catalogue on the device

    private val stateLock = Mutex()
    private var cached: OwnAudioCatalogue? = null

    private suspend fun catalogue(): OwnAudioCatalogue = stateLock.withLock { readLocked() }

    private suspend fun readLocked(): OwnAudioCatalogue = cached ?: (catalogueStore.load() ?: OwnAudioCatalogue()).also { cached = it }

    /** Read, changed and written in one step, so a follow and a sync never overwrite each other's work. */
    private suspend fun update(transform: (OwnAudioCatalogue) -> OwnAudioCatalogue): OwnAudioCatalogue = stateLock.withLock {
        val next = transform(readLocked())
        catalogueStore.save(next)
        cached = next
        next
    }

    /** What the server page shows under "Library on This Device". */
    data class Status(val shows: Int, val episodes: Int, val syncedAt: Instant?)

    suspend fun status(): Status = catalogue().let { Status(it.podcasts.size, it.episodes.size, it.syncedAt) }

    // MARK: syncing

    private val syncLock = Mutex()
    private val asked = AtomicLong()
    private var covered = 0L

    /**
     * Brings the catalogue up to date: a snapshot the first time (or when the server says the
     * cursor is too old), then the changes since. Without [force], a catalogue read in the last
     * ten minutes is taken as it is. A sync asked for while one runs runs once more after it;
     * several asked meanwhile share that one run. Returns the episodes first seen in this sync.
     */
    suspend fun sync(force: Boolean = false): List<Episode> {
        val ticket = asked.incrementAndGet()
        return syncLock.withLock {
            if (covered >= ticket) return@withLock emptyList()
            val held = catalogue()
            val syncedAt = held.syncedAt
            if (!force && held.cursor != null && syncedAt != null && Duration.between(syncedAt, now()) < MAX_AGE) return@withLock emptyList()
            val before = covered
            covered = asked.get()
            try {
                translating { runSync() }
            } catch (error: Throwable) {
                // Those who asked meanwhile get a sync of their own, not this failure's silence.
                covered = before
                throw error
            }
        }
    }

    private suspend fun runSync(): List<Episode> {
        val started = now()
        val touched = HashSet<String>()
        var resets = 0
        if (catalogue().cursor == null) {
            touched += takeSnapshot().podcasts.keys
        }
        while (true) {
            val cursor = catalogue().cursor
            val answer = try {
                client.changes(cursor, CHANGE_KINDS, limit = PAGE)
            } catch (error: OwnAudioException) {
                if (!isExpiredCursor(error) || resets++ >= MAX_RESETS) throw error
                touched += takeSnapshot().podcasts.keys
                continue
            }
            if (answer.reset) {
                if (resets++ >= MAX_RESETS) throw OwnAudioException.Http(410)
                touched += takeSnapshot().podcasts.keys
                continue
            }
            val gone = mutableListOf<String>()
            // The changes and the cursor after them are written together: a kill between pages
            // reads the same page again, never skips one.
            update { held -> apply(held, answer.changes, touched, gone).copy(cursor = answer.cursor) }
            gone.forEach { store.forget(it) }
            if (!answer.hasMore) break
        }
        val held = update { it.copy(syncedAt = now()) }
        val fresh = mutableListOf<Episode>()
        for (showId in touched) {
            if (showId !in held.podcasts) continue
            // A show seen for the first time brings its back catalogue, which is not news.
            val seenBefore = store.firstSeen(showId) != null
            val observed = observe(held, showId)
            if (seenBefore) fresh += observed.filter { !it.firstSeenAt.isBefore(started) }
        }
        return fresh
    }

    /**
     * The whole catalogue again, resumed where a cut-short body stopped. Nothing is written until
     * its `end` line has arrived, so a failure leaves the catalogue as it was.
     */
    private suspend fun takeSnapshot(): OwnAudioCatalogue {
        val podcasts = HashMap<String, Podcast>()
        val episodes = HashMap<String, ServerEpisode>()
        val progress = HashMap<String, ServerProgress>()
        var after: String? = null
        var cursor: String? = null
        var attempts = 0
        while (true) {
            try {
                client.snapshot(SNAPSHOT_KINDS, after = after, cursor = cursor).collect { line ->
                    when (line) {
                        is SnapshotLine.Begin -> cursor = line.cursor
                        is SnapshotLine.End -> cursor = line.cursor
                        is SnapshotLine.Record -> record(line.kind, line.record, podcasts, episodes, progress)
                        is SnapshotLine.Id -> Unit
                    }
                }
                break
            } catch (error: OwnAudioException.SnapshotTruncated) {
                if (++attempts > MAX_RESUMES) throw OwnAudioException.Network(error)
                after = error.after
                cursor = error.cursor ?: cursor
            }
        }
        val fresh = OwnAudioCatalogue(cursor, podcasts, episodes, progress)
        // Shows the snapshot no longer holds were unfollowed elsewhere while the cursor was stale.
        val before = catalogue().podcasts.keys
        update { fresh }
        (before - podcasts.keys).forEach { store.forget(it) }
        return fresh
    }

    private fun record(
        kind: String,
        record: JsonObject,
        podcasts: MutableMap<String, Podcast>,
        episodes: MutableMap<String, ServerEpisode>,
        progress: MutableMap<String, ServerProgress>,
    ) {
        // A record this version cannot read is skipped rather than failing the whole sync.
        runCatching {
            when {
                kind == "podcast" -> OwnAudioJson.decodeFromJsonElement(Podcast.serializer(), record).let { podcasts[it.id] = it }
                kind == "episode" -> OwnAudioJson.decodeFromJsonElement(ServerEpisode.serializer(), record).let { episodes[it.id] = it }
                isEpisodeProgress(kind, record) -> OwnAudioJson.decodeFromJsonElement(ServerProgress.serializer(), record).let { progress[it.id] = it }
            }
        }
    }

    private fun apply(held: OwnAudioCatalogue, changes: List<Change>, touched: MutableSet<String>, gone: MutableList<String>): OwnAudioCatalogue {
        val podcasts = held.podcasts.toMutableMap()
        val episodes = held.episodes.toMutableMap()
        val progress = held.progress.toMutableMap()
        for (change in changes) {
            val upsert = change.op == "upsert" && change.record != null
            when {
                change.kind == "podcast" -> if (upsert) {
                    record(change.kind, change.record!!, podcasts, episodes, progress)
                    touched += change.id
                } else if (change.op == "delete") {
                    podcasts.remove(change.id)
                    episodes.values.removeAll { it.podcastId == change.id }
                    gone += change.id
                }
                change.kind == "episode" -> if (upsert) {
                    record(change.kind, change.record!!, podcasts, episodes, progress)
                    episodes[change.id]?.let { touched += it.podcastId }
                } else if (change.op == "delete") {
                    episodes.remove(change.id)
                }
                change.kind == "progress.episode" -> if (upsert) {
                    record(change.kind, change.record!!, podcasts, episodes, progress)
                } else if (change.op == "delete") {
                    progress.remove(change.id)
                }
            }
        }
        return held.copy(podcasts = podcasts, episodes = episodes, progress = progress)
    }

    /**
     * A show's episodes with their first sightings. A show is only sighted once it has episodes:
     * sighting an empty one (a follow whose feed the server is still reading) would make its
     * whole back catalogue "new" when it arrives.
     */
    private suspend fun observe(held: OwnAudioCatalogue, showId: String): List<Episode> {
        val episodes = held.episodes.values.filter { it.podcastId == showId }.map(::episode)
        if (episodes.isEmpty()) return emptyList()
        return store.observe(episodes, showId)
    }

    /** The catalogue, synced when it is old; the one on the device when the server can't be reached. */
    private suspend fun current(): OwnAudioCatalogue {
        try {
            sync()
        } catch (error: SourceException.Network) {
            if (catalogue().cursor == null) throw error
        }
        return catalogue()
    }

    /**
     * Live updates while the app is in the foreground: each `library` event reads the change
     * feed (two seconds after the last of a burst). Reconnects after a lost connection, waiting
     * 2 s and doubling to 2 min; ends when the account is signed out. Cancel it to stop.
     * [onChange] runs after each sync it made, so the screens can read their lists again.
     */
    suspend fun followLiveUpdates(
        debounceMs: Long = 2_000,
        firstRetryMs: Long = 2_000,
        maxRetryMs: Long = 120_000,
        onChange: suspend () -> Unit = {},
    ) {
        var lastId: String? = null
        var retry = firstRetryMs
        while (true) {
            try {
                coroutineScope {
                    var pending: Job? = null
                    client.events(lastId, onOpen = { retry = firstRetryMs }).collect { event ->
                        event.id?.let { lastId = it }
                        if (event.type == "library") {
                            pending?.cancel()
                            pending = launch {
                                delay(debounceMs)
                                try {
                                    sync(force = true)
                                    onChange()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    // The next event or the next refresh tries again.
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OwnAudioException.SignedOut) {
                return
            } catch (_: OwnAudioException.SignInRefused) {
                return
            } catch (_: Exception) {
                // Lost: try again below.
            }
            delay(retry)
            retry = (retry * 2).coerceAtMost(maxRetryMs)
        }
    }

    // MARK: shows and episodes

    override suspend fun shows(): List<Show> {
        val held = current()
        val collator = Collator.getInstance()
        return held.podcasts.values.map { show(it) }.sortedWith(compareBy(collator) { it.title })
    }

    override suspend fun episodes(showId: String): List<Episode> {
        val held = current()
        if (showId !in held.podcasts) throw SourceException.NotFound()
        return Inbox.ordered(observe(held, showId), serial = false)
    }

    override suspend fun episode(key: EpisodeKey): Episode {
        val known = catalogue().episodes[key.episodeId] ?: translating { client.fetchEpisode(key.episodeId) }
        return store.observe(listOf(episode(known)), key.showId).first()
    }

    /** Whether the show is shared with the household: unfollowing it takes it away from them too. */
    suspend fun isSharedWithFamily(showId: String): Boolean = catalogue().podcasts[showId]?.visibility == "family"

    /** The account that follows the show for the household: only it may share it or keep it private. */
    suspend fun owner(showId: String): String? = catalogue().podcasts[showId]?.ownerId?.ifEmpty { null }

    /** The account's role in its household (`admin` or `member`), asked once. */
    @Volatile private var familyRole: String? = null

    /**
     * Whether the server lets this account ([me]) unfollow the show: its owner may, and a
     * household admin may for a show shared with the household. Anyone else is refused, so the
     * app doesn't offer it.
     */
    suspend fun mayUnfollow(showId: String, me: String?): Boolean {
        val podcast = catalogue().podcasts[showId] ?: return false
        if (me != null && podcast.ownerId == me) return true
        if (podcast.visibility != "family") return false
        val role = familyRole ?: runCatching { translating { client.me() }.family.role }.getOrNull()?.also { familyRole = it }
        return role == "admin"
    }

    /** How many episodes of each show the listener hasn't finished, for the Shows grid. */
    suspend fun unplayedCounts(): Map<String, Long> =
        translating { pageAll { cursor -> client.unplayed(cursor) } }.associate { it.podcastId to it.unplayed }

    // MARK: following

    private val followLock = Mutex()
    private var lastFollow: Instant? = null

    override suspend fun follow(feedUrl: String): Show = follow(feedUrl, visibility = null)

    /**
     * Follows a feed on the server, which reads it at once; a show already followed (or shared
     * with the listener) comes back as it is. [idempotencyKey] makes a retry of the same follow
     * count once. Follows in a row are spaced to stay under the server's 30 a minute.
     */
    suspend fun follow(feedUrl: String, visibility: String?, idempotencyKey: String = newKey()): Show {
        val podcast = followLock.withLock {
            lastFollow?.let { last ->
                val wait = FOLLOW_SPACING.toMillis() - Duration.between(last, now()).toMillis()
                if (wait > 0) sleep(wait)
            }
            try {
                translating { client.follow(feedUrl, visibility, idempotencyKey) }
            } finally {
                lastFollow = now()
            }
        }
        update { it.copy(podcasts = it.podcasts + (podcast.id to podcast)) }
        try {
            sync(force = true)
        } catch (_: SourceException.Network) {
            // Followed; its episodes come with the next sync.
        }
        return show(catalogue().podcasts[podcast.id] ?: podcast)
    }

    /**
     * Stops following on the server: the show and its episodes leave the household's library
     * (the owner, or a household admin for a shared show; others get [SourceException.Server]).
     */
    override suspend fun unfollow(showId: String) {
        try {
            translating { client.unfollow(showId) }
        } catch (_: SourceException.NotFound) {
            // Gone already.
        }
        update { held ->
            held.copy(
                podcasts = held.podcasts - showId,
                episodes = held.episodes.filterValues { it.podcastId != showId },
            )
        }
        store.forget(showId)
    }

    /** Shares the show with the household, or keeps it to its owner. */
    suspend fun setSharedWithFamily(showId: String, shared: Boolean) {
        val answer = translating { client.setPodcastVisibility(showId, if (shared) "family" else "private") }
        update { held ->
            val podcast = held.podcasts[showId] ?: return@update held
            held.copy(podcasts = held.podcasts + (showId to podcast.copy(visibility = answer.visibility)))
        }
    }

    override suspend fun updateSettings(settings: ShowSettings, showId: String) = store.setSettings(settings, showId)

    /**
     * With [force] (the listener asked) the server reads the feeds again — one show's or all —
     * and the change feed is read; its new episodes may arrive a moment later, through the next
     * sync. An automatic refresh only reads the change feed, as the server refreshes on its own.
     */
    override suspend fun refresh(showId: String?, force: Boolean): List<Episode> {
        if (force) {
            translating { if (showId != null) client.refresh(showId) else client.refreshAllPodcasts() }
        }
        val fresh = sync(force)
        return if (showId == null) fresh else fresh.filter { it.showId == showId }
    }

    // MARK: the inbox

    override suspend fun inbox(limit: Int): List<Episode> {
        val held = current()
        val out = mutableListOf<Episode>()
        for (showId in held.podcasts.keys) {
            for (episode in observe(held, showId)) {
                if (!store.isInInbox(episode)) continue
                // Finished on another device, later than anything this one holds.
                val server = held.progress[episode.id]
                val local = store.progress(episode.key)
                if (server?.finished == true && (local == null || server.updatedAt.isAfter(local.updatedAt))) continue
                out += episode
            }
        }
        return Inbox.newestFirst(out, limit)
    }

    override suspend fun dismissFromInbox(keys: List<EpisodeKey>) = store.dismiss(keys)

    // MARK: progress

    /** The newest of the device's, the catalogue's and (when reachable) the server's own. */
    override suspend fun progress(key: EpisodeKey): ListeningProgress? {
        val candidates = mutableListOf<ListeningProgress>()
        store.progress(key)?.let { candidates += it }
        catalogue().progress[key.episodeId]?.let { candidates += it.listening() }
        try {
            client.fetchProgress(ItemKind.EPISODE, key.episodeId)?.let { candidates += it.listening() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline: what the device holds.
        }
        return candidates.maxByOrNull { it.updatedAt }
    }

    override suspend fun allProgress(): Map<EpisodeKey, ListeningProgress> {
        val out = store.allProgress().toMutableMap()
        val held = catalogue()
        for ((episodeId, server) in held.progress) {
            // The server names episodes alone; the show comes from the catalogue.
            val showId = held.episodes[episodeId]?.podcastId ?: continue
            val key = EpisodeKey(showId, episodeId)
            val remote = server.listening()
            val local = out[key]
            if (local != null && !local.updatedAt.isBefore(remote.updatedAt)) continue
            out[key] = remote
        }
        return out
    }

    /**
     * On the device first, then to the server with the time it happened. When another device
     * was there later, the server keeps its position (`applied: false`) and that is what this
     * device takes and returns.
     */
    override suspend fun saveProgress(progress: ListeningProgress, key: EpisodeKey): ListeningProgress {
        val kept = store.save(progress, key)
        if (kept != progress) return kept
        val answer = translating {
            client.saveProgress(ItemKind.EPISODE, key.episodeId, progress.positionMs, progress.played, progress.updatedAt)
        }
        update { it.copy(progress = it.progress + (key.episodeId to answer.progress)) }
        if (answer.applied) return kept
        val held = answer.progress.listening()
        return store.save(held, key)
    }

    override suspend fun markPlayed(key: EpisodeKey, played: Boolean) {
        val position = if (played) store.progress(key)?.positionMs ?: 0 else 0
        saveProgress(ListeningProgress(position, played, now()), key)
    }

    /**
     * Positions kept while offline, in one go (`/sync/push`, 500 at a time, oldest first).
     * Returns each one's answer: `ok`, `stale` (another device is newer: the next sync brings
     * its position) and `duplicate` all mean done; anything else is an error code to keep it for.
     */
    suspend fun pushProgress(entries: List<Pair<EpisodeKey, ListeningProgress>>): Map<EpisodeKey, String> {
        val out = LinkedHashMap<EpisodeKey, String>()
        for (batch in entries.sortedBy { it.second.updatedAt }.chunked(PAGE)) {
            val byClientId = batch.associateBy { (key, progress) -> "${key.episodeId}@${progress.updatedAt.toEpochMilli()}" }
            val changes = byClientId.map { (clientId, entry) ->
                OfflineChange.progress(ItemKind.EPISODE, entry.first.episodeId, entry.second.positionMs, entry.second.played, entry.second.updatedAt, clientId)
            }
            for (result in translating { client.push(changes) }) {
                byClientId[result.clientId]?.let { out[it.first] = result.status }
            }
        }
        return out
    }

    // MARK: playing

    /**
     * Where the server says to play from: its own copy behind a signed link, or the publisher's
     * file (`publisher_file`). Neither needs the account's token; the publisher's must never get
     * it, so the player fetches both with the plain client.
     */
    override suspend fun playback(key: EpisodeKey): Playback {
        val info = translating { client.playbackInfo("episode", key.episodeId, PlaybackCapabilities()) }
        return Playback(
            url = info.url,
            isServerFile = PUBLISHER_FILE !in info.reasons,
            isTranscode = info.decision == "transcode",
            expiresAt = info.expiresAt,
        )
    }

    // MARK: the directory

    /**
     * The server's directory of shows nobody here follows yet, for Discover: categories and their
     * best shows, similar shows ([MODULE_DISCOVERY]), search ([MODULE_SEARCH]) and a show's
     * page before following it. A module that is off answers [SourceException.Unsupported].
     */
    inner class Directory internal constructor() {
        val canBrowse: Boolean get() = this@OwnAudioPodcastSource.canBrowse
        val canSearch: Boolean get() = this@OwnAudioPodcastSource.canSearch

        suspend fun categories(): List<DirectoryCategory> = browsing { client.directoryCategories().items }

        /** One page; pass [Page.next] back as [cursor]. */
        suspend fun browse(category: String, language: String? = null, cursor: String? = null): Page<DirectoryShow> =
            browsing { client.directory(category, language, cursor) }

        /** Results carry `podcastId` when the household already follows the show. */
        suspend fun search(query: String, language: String? = null): List<DirectoryShow> {
            if (!canSearch) throw SourceException.Unsupported()
            return translating { client.directorySearch(query, language).items }
        }

        suspend fun similar(podcastId: String? = null, feedUrl: String? = null): List<DirectoryShow> =
            browsing { client.directorySimilar(podcastId, feedUrl).items }

        suspend fun preview(feedUrl: String): DirectoryPreview = translating { client.directoryPreview(feedUrl) }

        private suspend fun <T> browsing(work: suspend () -> T): T {
            if (!canBrowse) throw SourceException.Unsupported()
            return translating(work)
        }
    }

    // MARK: mapping

    private suspend fun show(podcast: Podcast) = Show(
        id = podcast.id,
        title = HtmlText.decodeEntities(podcast.title),
        description = podcast.description?.let(HtmlText::plain)?.ifEmpty { null },
        imageUrl = FeedValues.url(podcast.image),
        feedUrl = FeedValues.url(podcast.feedUrl),
        link = FeedValues.url(podcast.link),
        categories = podcast.categories,
        followedAt = store.firstSeen(podcast.id) ?: podcast.createdAt ?: now(),
        refreshedAt = podcast.lastRefreshedAt,
        settings = store.settings(podcast.id),
    )

    private fun episode(episode: ServerEpisode) = Episode(
        id = episode.id,
        showId = episode.podcastId,
        title = HtmlText.decodeEntities(episode.title),
        description = episode.description?.let(HtmlText::plain)?.ifEmpty { null },
        publishedAt = episode.publishedAt,
        durationMs = episode.durationMs?.takeIf { it > 0 },
        audioUrl = FeedValues.url(episode.audioUrl),
        audioType = episode.contentType,
        audioBytes = episode.sizeBytes,
        imageUrl = FeedValues.url(episode.image),
        season = episode.seasonNumber,
        number = episode.episodeNumber,
        transcripts = episode.transcriptUrl?.let(FeedValues::url)?.let { listOf(Transcript(it, episode.transcriptType ?: "text/plain")) }.orEmpty(),
        firstSeenAt = now(),
    )

    private fun ServerProgress.listening() = ListeningProgress(positionMs, finished, updatedAt)

    private suspend fun <T> translating(work: suspend () -> T): T = try {
        work()
    } catch (error: OwnAudioException) {
        throw when (error) {
            OwnAudioException.SignedOut, OwnAudioException.SignInRefused -> SourceException.SignInRejected()
            is OwnAudioException.Network, OwnAudioException.Offline -> SourceException.Network(error)
            is OwnAudioException.Problem -> when {
                error.status == 401 -> SourceException.SignInRejected()
                error.status == 404 -> SourceException.NotFound()
                error.status == 501 -> SourceException.Unsupported()
                error.code == "feed_unreadable" || error.code == "invalid_feed_url" -> SourceException.NotAFeed()
                else -> SourceException.Server(error.code)
            }
            is OwnAudioException.Http -> when (error.status) {
                401 -> SourceException.SignInRejected()
                404 -> SourceException.NotFound()
                501 -> SourceException.Unsupported()
                else -> SourceException.Server("HTTP ${error.status}")
            }
            else -> SourceException.Server(error.message ?: "error")
        }
    }

    companion object {
        const val MODULE_SEARCH = "podcast_search"
        const val MODULE_DISCOVERY = "podcast_discovery"

        /** How long a synced catalogue is taken as it is (foundation §9.1). */
        val MAX_AGE: Duration = Duration.ofMinutes(10)

        /** 30 follows a minute is the server's outbound limit. */
        val FOLLOW_SPACING: Duration = Duration.ofSeconds(2)

        val SNAPSHOT_KINDS = listOf("podcast", "episode", "progress")
        val CHANGE_KINDS = listOf("podcast", "episode", "progress.episode")

        private const val PAGE = 500
        private const val MAX_RESETS = 2
        private const val MAX_RESUMES = 3
        private const val PUBLISHER_FILE = "publisher_file"

        /** The snapshot sends every kind of position under `progress`; this source keeps the episodes'. */
        private fun isEpisodeProgress(kind: String, record: JsonObject): Boolean {
            if (kind == "progress.episode") return true
            if (kind != "progress") return false
            return (record["kind"] as? JsonPrimitive)?.content == "episode"
        }

        private fun isExpiredCursor(error: OwnAudioException): Boolean =
            error.httpStatus == 410 || (error is OwnAudioException.Problem && error.code in setOf("sync_cursor_expired", "invalid_cursor"))
    }
}
