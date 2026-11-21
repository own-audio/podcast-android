package audio.own.podcastplayer.podcasts

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import audio.own.core.servers.NetworkState
import audio.own.core.servers.ServerRecord
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.discover
import audio.own.player.core.PlaybackLink
import audio.own.player.core.PlaybackLinkRequest
import audio.own.player.core.PlaybackLinkResolver
import audio.own.player.core.PlaybackUris
import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.chapters.BlockCachingRangeReader
import audio.own.player.spoken.chapters.EmbeddedChapters
import audio.own.podcast.library.AppleShowCatalogue
import audio.own.podcast.library.AutoQueue
import audio.own.podcast.library.Capability
import audio.own.podcast.library.Chapter
import audio.own.podcast.library.EpisodeChapters
import audio.own.podcast.library.FeedLibrary
import audio.own.podcast.library.Inbox
import audio.own.podcast.library.LastPlayed
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.ListeningState
import audio.own.podcast.library.ListeningStore
import audio.own.podcast.library.OwnAudioPodcastSource
import audio.own.podcast.library.OwnAudioShowCatalogue
import audio.own.podcast.library.PendingProgress
import audio.own.podcast.library.PlayOn
import audio.own.podcast.library.PodcastSource
import audio.own.podcast.library.PositionThrottle
import audio.own.podcast.library.Show
import audio.own.podcast.library.ShowCatalogue
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.ShowSettings
import audio.own.podcast.library.SourceException
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcast.library.UpNext
import audio.own.podcast.library.ValueStore
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomOwnAudioCatalogueStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcastplayer.R
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.AppPreferences
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.playback.EpisodePlayer
import audio.own.podcastplayer.playback.HttpRangeReader
import audio.own.podcastplayer.playback.PlaybackEngine
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.ServersState
import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/** A source as the switch, Follow On and the labels show it. */
data class SourceInfo(val ref: SourceRef, val name: String, val kind: String?)

/** Another device got somewhere else in the episode; asked, never applied silently. */
data class PendingResume(val episode: SourcedEpisode, val localMs: Long, val otherMs: Long)

/** A show Discover suggests, and the followed show that brought it up. */
data class Suggestion(val show: ShowSearchResult, val because: String)

enum class OfflineReason { CHOSEN, NO_NETWORK }

data class PodcastState(
    val loaded: Boolean = false,
    val shows: List<SourcedShow> = emptyList(),
    /** Every source's New Episodes, newest first. */
    val inbox: List<SourcedEpisode> = emptyList(),
    /** By [SourcedEpisode.id]. */
    val progress: Map<String, ListeningProgress> = emptyMap(),
    /** [SourceRef.id]s whose lists failed to load. */
    val unreachable: Set<String> = emptySet(),
    val upNext: List<UpNext.Entry> = emptyList(),
    /** The switch's choice; null is All Sources. */
    val activeSource: String? = null,
    /** own.audio first, then this device, then the other servers. */
    val sources: List<SourceInfo> = listOf(SourceInfo(SourceRef.Phone, "", null)),
    val followTargets: List<SourceInfo> = listOf(SourceInfo(SourceRef.Phone, "", null)),
    val offline: OfflineReason? = null,
    val refreshing: Boolean = false,
    /** The own.audio server whose directory Discover browses, by name; null: Apple's. */
    val catalogueServer: String? = null,
    val suggestions: List<Suggestion>? = null,
    val pendingResume: PendingResume? = null,
    val error: UiText? = null,
    /** A feed address a link handed over, for Add a Show. */
    val pendingFeedAddress: String? = null,
) {
    private val showsByKey: Map<String, Show> by lazy { shows.associate { "${it.source.id}/${it.show.id}" to it.show } }

    fun show(item: SourcedEpisode): Show? = showsByKey["${item.source.id}/${item.episode.showId}"]

    val isOffline: Boolean get() = offline != null

    val catalogueSuggests: Boolean get() = catalogueServer != null

    private val active: SourceRef? get() = activeSource?.let(SourceRef::parse)

    val visibleInbox: List<SourcedEpisode>
        get() = active?.let { ref -> inbox.filter { matches(it.source, show(it), ref) } } ?: inbox

    val visibleShows: List<SourcedShow>
        get() = active?.let { ref -> shows.filter { matches(it.source, it.show, ref) } } ?: shows

    fun isQueued(item: SourcedEpisode): Boolean = upNext.any { it.id == item.id }

    fun sourceName(ref: SourceRef): String = sources.firstOrNull { it.ref == ref }?.name
        ?: followTargets.firstOrNull { it.ref == ref }?.name
        ?: ""

    private fun matches(source: SourceRef, show: Show?, active: SourceRef): Boolean = when (active) {
        is SourceRef.Library -> source == SourceRef.Server(active.uuid) && show?.collection == active.libraryId
        else -> source == active
    }
}

/**
 * The app's podcast library across every source, and what plays: the port of the iOS app's
 * `AppModel` (its shows, New Episodes, positions, Up Next and play paths). The device's own feeds
 * are always one source; every signed-in own.audio server is another. Screens read [state] and
 * [player]'s state and call the actions here.
 *
 * Positions are never lost: each report goes to the device first (the last-played file, the
 * device's store or the queue of positions for a server) and to a server after.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PodcastModel(
    private val db: PodcastDatabase,
    val phone: FeedLibrary,
    private val upNext: UpNext,
    private val pending: PendingProgress,
    private val lastPlayed: ValueStore<LastPlayed>,
    val player: PlaybackEngine,
    private val servers: StateFlow<ServersState>,
    private val client: (String) -> OwnAudioClient?,
    private val preferences: AppPreferences,
    private val network: StateFlow<NetworkState>,
    private val offlineMode: () -> Boolean,
    /** The plain client: feeds, chapters, Apple's directory. No token ever goes through it. */
    private val http: OkHttpClient,
    private val feeds: FeedFetcher,
    private val isFullyCached: (String) -> Boolean,
    private val strings: Strings,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val country: String? = Locale.getDefault().country,
) {
    /** The few words the model itself names (the device as a source, a removed server). */
    interface Strings {
        val thisDevice: String

        /** A chapter without a title of its own: "Chapter 3". */
        fun chapter(number: Int): String
    }

    private val _state = MutableStateFlow(
        PodcastState(
            sources = listOf(SourceInfo(SourceRef.Phone, strings.thisDevice, null)),
            followTargets = listOf(SourceInfo(SourceRef.Phone, strings.thisDevice, null)),
        ),
    )
    val state: StateFlow<PodcastState> = _state.asStateFlow()

    private val ownAudio = MutableStateFlow<Map<String, OwnAudioPodcastSource>>(emptyMap())
    private val listeningStores = HashMap<String, ListeningStore>()
    private val catalogueStores = HashMap<String, RoomOwnAudioCatalogueStore>()
    private val sourceKeys = HashMap<String, String>()
    private val modules = HashMap<String, Set<String>>()
    private var knownServers: Set<String>? = null
    private val throttle = PositionThrottle()
    private var lastRefreshAt: Instant? = null
    private val refreshLock = Mutex()
    private var liveUpdates: Map<String, Job> = emptyMap()
    private var inForeground = false
    private var chaptersJob: Job? = null

    val apple: ShowCatalogue = AppleShowCatalogue(http, country)

    init {
        player.listener = object : EpisodePlayer.Listener {
            override fun onPosition(report: EpisodePlayer.Report) = positionReported(report)

            override fun onEnded(id: String, playOn: Boolean) {
                forgetLastPlayed()
                if (playOn) scope.launch { playOn(after = id) }
            }
        }
    }

    // region Loading

    /** At launch: the stores, the sources, the lists, the last episode paused, a refresh. */
    suspend fun load() {
        if (_state.value.loaded) return
        val upNextList = upNext.all()
        val active = preferences.activeSource.first()
        _state.update { it.copy(upNext = upNextList, activeSource = active, offline = offlineReason()) }
        servers.first { it.loaded }
        rebuildSources(servers.value)
        reloadLists()
        _state.update { it.copy(loaded = true) }
        // A source remembered from before that is gone now would filter everything away.
        if (active != null && _state.value.sources.none { it.ref.id == active }) setActiveSource(null)
        restoreLastPlayed()
        scope.launch { servers.drop(1).collect { rebuildSources(it) } }
        scope.launch {
            network.map { it.connected }.distinctUntilChanged().drop(1).collect { connected ->
                _state.update { it.copy(offline = offlineReason()) }
                if (connected) {
                    player.retryIfWaiting()
                    sendPendingProgress()
                    reloadLists()
                }
                followLiveUpdates()
            }
        }
        refresh(asked = false)
    }

    /** Offline Mode changed: the lists show what is on the device, or everything again. */
    fun offlineModeChanged() {
        _state.update { it.copy(offline = offlineReason()) }
        followLiveUpdates()
        scope.launch {
            reloadLists()
            if (!offlineMode()) refresh(asked = true)
        }
    }

    private fun offlineReason(): OfflineReason? = when {
        offlineMode() -> OfflineReason.CHOSEN
        !network.value.connected -> OfflineReason.NO_NETWORK
        else -> null
    }

    private val isOffline: Boolean get() = offlineReason() != null

    /**
     * One own.audio source per signed-in own.audio server, at the address in use. The device's
     * catalogue and listening state of each server outlive the source object, which is made again
     * when the address changes.
     */
    private suspend fun rebuildSources(state: ServersState) {
        if (!state.loaded) return
        val ids = state.servers.map { it.id }.toSet()
        knownServers?.let { before -> (before - ids).forEach { forgetServer(it) } }
        knownServers = ids
        val next = LinkedHashMap<String, OwnAudioPodcastSource>()
        for (record in ordered(state.servers)) {
            if (record.kind != ServerKinds.OWN_AUDIO) continue
            val key = "${state.connections[record.id]}|${record.connection}|${modules[record.id]}"
            val held = ownAudio.value[record.id]
            if (held != null && sourceKeys[record.id] == key) {
                next[record.id] = held
                continue
            }
            val client = client(record.id) ?: continue
            val listening = listeningStores.getOrPut(record.id) { ListeningStore(RoomValueStore(db, "${record.id}/listening", ListeningState.serializer()), now) }
            val catalogue = catalogueStores.getOrPut(record.id) { RoomOwnAudioCatalogueStore(db, record.id) }
            next[record.id] = OwnAudioPodcastSource(client, listening, catalogue, modules[record.id].orEmpty(), now)
            sourceKeys[record.id] = key
            if (record.id !in modules) scope.launch { learnModules(record.id, client) }
        }
        ownAudio.value = next
        _state.update { it.copy(sources = switchable(state), followTargets = followTargets(state), catalogueServer = catalogueServer(state)) }
        if (_state.value.loaded) {
            followLiveUpdates()
            reloadLists()
        }
    }

    /** Which modules a server has on (its directory is one); the source is made again with them. */
    private suspend fun learnModules(serverId: String, client: OwnAudioClient) {
        val discovery = try {
            client.discover()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        modules[serverId] = discovery.modules.keys.filter(discovery::has).toSet()
        rebuildSources(servers.value)
    }

    private suspend fun forgetServer(serverId: String) {
        log.info("podcasts", "forgetting a removed server's shows")
        if (player.state.value.item?.source?.serverId?.toString() == serverId) stopPlayback()
        ownAudio.update { it - serverId }
        listeningStores.remove(serverId)
        catalogueStores.remove(serverId)
        modules.remove(serverId)
        sourceKeys.remove(serverId)
        db.forgetServer(serverId)
        pending.removeAll("$serverId/")
        upNext.removeAll { it.source == serverId }
        if (_state.value.activeSource?.startsWith(serverId) == true) setActiveSource(null)
        _state.update { it.copy(upNext = upNext.all()) }
    }

    private fun ordered(records: List<ServerRecord>): List<ServerRecord> {
        val collator = Collator.getInstance()
        fun rank(kind: String) = when (kind) {
            ServerKinds.OWN_AUDIO -> 0
            ServerKinds.AUDIOBOOKSHELF -> 1
            else -> 2
        }
        return records.sortedWith(compareBy<ServerRecord> { rank(it.kind) }.thenBy(collator) { it.displayName })
    }

    private fun info(record: ServerRecord) = SourceInfo(SourceRef.Server(java.util.UUID.fromString(record.id)), record.displayName, record.kind)

    /** own.audio first, always; then this device; then the other servers with a library of their own. */
    private fun switchable(state: ServersState): List<SourceInfo> {
        val records = ordered(state.servers).filter { it.id in ownAudio.value }
        val phone = SourceInfo(SourceRef.Phone, strings.thisDevice, null)
        return records.filter { it.kind == ServerKinds.OWN_AUDIO }.map(::info) + phone + records.filter { it.kind != ServerKinds.OWN_AUDIO }.map(::info)
    }

    /** Where shows can be followed: the device always, and every server that takes a feed address. */
    private fun followTargets(state: ServersState): List<SourceInfo> =
        listOf(SourceInfo(SourceRef.Phone, strings.thisDevice, null)) +
            ordered(state.servers).filter { ownAudio.value[it.id]?.capabilities?.contains(Capability.ADD_BY_URL) == true }.map(::info)

    /** The first own.audio server whose directory can be browsed. */
    private fun catalogueServer(state: ServersState): String? =
        ordered(state.servers).firstOrNull { ownAudio.value[it.id]?.directory?.canBrowse == true }?.displayName

    fun source(ref: SourceRef): PodcastSource? = when (ref) {
        SourceRef.Phone -> phone
        is SourceRef.Server -> ownAudio.value[ref.id]
        is SourceRef.Library -> ownAudio.value[ref.uuid.toString()]
    }

    private fun ownAudioSource(ref: SourceRef): OwnAudioPodcastSource? = ref.serverId?.let { ownAudio.value[it.toString()] }

    fun capabilities(ref: SourceRef): Set<Capability> = source(ref)?.capabilities.orEmpty()

    // endregion

    // region Lists

    /**
     * Every source's shows, New Episodes and positions, read again. Offline, only the device's
     * own feeds: they are on the device, and a server would only time out.
     */
    suspend fun reloadLists() {
        val refs = buildList {
            add(SourceRef.Phone)
            if (!isOffline) ownAudio.value.keys.forEach { add(SourceRef.Server(java.util.UUID.fromString(it))) }
        }
        val answers = coroutineScopeAll(refs) { ref ->
            val source = source(ref) ?: return@coroutineScopeAll null
            try {
                val shows = source.shows().map { SourcedShow(ref, it) }
                val inbox = source.inbox(Inbox.LIMIT).map { SourcedEpisode(ref, it) }
                val progress = source.allProgress().mapKeys { (key, _) -> SourcedEpisode.id(ref, key) }
                Triple(shows, inbox, progress)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.error("podcasts", "a source's lists failed: ${error.javaClass.simpleName}")
                null
            }
        }
        val collator = Collator.getInstance()
        val shows = answers.values.filterNotNull().flatMap { it.first }.sortedWith(compareBy(collator) { it.show.title })
        val inbox = answers.values.filterNotNull().flatMap { it.second }.sortedByDescending { it.episode.publishedAt ?: it.episode.firstSeenAt }
        val progress = HashMap<String, ListeningProgress>()
        answers.values.filterNotNull().forEach { progress.putAll(it.third) }
        // A position this device holds in memory and has not stored yet is newer than any list.
        val held = _state.value.progress
        for ((id, value) in held) {
            val listed = progress[id]
            if (listed == null || value.updatedAt.isAfter(listed.updatedAt)) progress[id] = value
        }
        val failing = answers.filterValues { it == null }.keys.map { it.id }.toSet()
        _state.update { it.copy(shows = shows, inbox = inbox, progress = progress, unreachable = failing, upNext = upNext.all()) }
    }

    private suspend fun <K, V> coroutineScopeAll(keys: List<K>, work: suspend (K) -> V): Map<K, V> =
        kotlinx.coroutines.coroutineScope { keys.map { key -> async { key to work(key) } }.awaitAll().toMap() }

    /**
     * Sends waiting positions, asks every feed and server for new episodes, and reads the lists
     * again. [asked] is a pull to refresh: always runs, and the servers read their feeds now. The
     * app's own refreshes wait 15 minutes after the last one.
     */
    suspend fun refresh(asked: Boolean = true) {
        if (isOffline) return
        if (!asked) {
            val last = lastRefreshAt
            if (last != null && Duration.between(last, now()) < MIN_REFRESH_INTERVAL) {
                sendPendingProgress()
                return
            }
        }
        if (!refreshLock.tryLock()) return
        try {
            lastRefreshAt = now()
            _state.update { it.copy(refreshing = true) }
            sendPendingProgress()
            phone.refresh(null, asked)
            ownAudio.value.values.forEach { source ->
                try {
                    source.refresh(null, asked)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Shown as unreachable by the reload.
                }
            }
            reloadLists()
            queueNewEpisodes()
        } finally {
            _state.update { it.copy(refreshing = false) }
            refreshLock.unlock()
        }
    }

    suspend fun refresh(show: SourcedShow) {
        if (isOffline) return
        runCatching { source(show.source)?.refresh(show.show.id, true) }
        reloadLists()
    }

    fun setActiveSource(id: String?) {
        _state.update { it.copy(activeSource = id) }
        scope.launch { preferences.setActiveSource(id) }
    }

    // endregion

    // region Shows

    /** Follows the feed at [feedUrl] on [target]; the device when nothing else is chosen. */
    suspend fun follow(feedUrl: String, target: SourceRef = SourceRef.Phone): SourcedShow {
        val source = source(target) ?: throw SourceException.NotFound()
        val show = source.follow(feedUrl)
        reloadLists()
        val ref = target.serverId?.let { SourceRef.Server(it) } ?: SourceRef.Phone
        return _state.value.shows.firstOrNull { it.source == ref && it.show.id == show.id } ?: SourcedShow(ref, show)
    }

    fun unfollow(show: SourcedShow, after: () -> Unit = {}) = perform {
        source(show.source)?.unfollow(show.show.id)
        val current = player.state.value.item
        if (current?.source == show.source && current.episode.showId == show.show.id) stopPlayback()
        upNext.removeAll { it.source == show.source.id && it.episode.showId == show.show.id }
        _state.update { state ->
            state.copy(
                shows = state.shows.filterNot { it.id == show.id },
                inbox = state.inbox.filterNot { it.source == show.source && it.episode.showId == show.show.id },
                upNext = upNext.all(),
            )
        }
        after()
    }

    suspend fun episodes(show: SourcedShow): List<SourcedEpisode> {
        val source = source(show.source) ?: throw SourceException.NotFound()
        return source.episodes(show.show.id).map { SourcedEpisode(show.source, it) }
    }

    fun updateSettings(settings: ShowSettings, show: SourcedShow) = perform {
        source(show.source)?.updateSettings(settings, show.show.id)
        reloadLists()
    }

    /** Whether unfollowing takes the show away from the family too (own.audio). */
    suspend fun isSharedWithFamily(show: SourcedShow): Boolean = ownAudioSource(show.source)?.isSharedWithFamily(show.show.id) == true

    // endregion

    // region Episodes

    fun dismiss(item: SourcedEpisode) {
        _state.update { state -> state.copy(inbox = state.inbox.filterNot { it.id == item.id }) }
        scope.launch { runCatching { source(item.source)?.dismissFromInbox(listOf(item.episode.key)) } }
    }

    fun markPlayed(item: SourcedEpisode, played: Boolean) = perform {
        val held = _state.value.progress[item.id]
        val value = ListeningProgress(if (played) held?.positionMs ?: 0 else 0, played, now())
        if (item.source == SourceRef.Phone) {
            phone.markPlayed(item.episode.key, played)
        } else {
            // Through the queue, so marking works offline too.
            send(value, item.id)
        }
        _state.update { state ->
            state.copy(
                progress = state.progress + (item.id to value),
                inbox = if (played) state.inbox.filterNot { it.id == item.id } else state.inbox,
            )
        }
    }

    /** Offline, only what the listening cache holds whole can play. */
    fun canPlay(item: SourcedEpisode): Boolean = !isOffline || isFullyCached(item.id)

    // endregion

    // region Playing

    /**
     * Plays [item] from [startAt], or from where the listener was. A server may know a newer
     * position from another device: when it differs by more than 30 s, the listener is asked
     * ([PodcastState.pendingResume]) — unless [automatic] (playing on), where the newer wins.
     */
    suspend fun play(item: SourcedEpisode, startAt: Long? = null, autoplay: Boolean = true, automatic: Boolean = false, quietly: Boolean = false) {
        val source = source(item.source) ?: return
        val show = _state.value.show(item) ?: _state.value.upNext.firstOrNull { it.id == item.id }?.show
        var start: Long
        if (startAt != null) {
            start = startAt
        } else {
            val local = _state.value.progress[item.id]
            val remote = if (item.source == SourceRef.Phone || isOffline) {
                null
            } else {
                withTimeoutOrNull(REMOTE_POSITION_TIMEOUT_MS) { runCatching { source.progress(item.episode.key) }.getOrNull() }
            }
            if (!automatic && local != null && remote != null && !local.played && !remote.played &&
                kotlin.math.abs(local.positionMs - remote.positionMs) > RESUME_QUESTION_MS && local.positionMs > 0
            ) {
                _state.update { it.copy(pendingResume = PendingResume(item, local.positionMs, remote.positionMs)) }
                return
            }
            val newest = listOfNotNull(local, remote).maxByOrNull { it.updatedAt }
            start = newest?.let { if (it.played) 0 else it.positionMs } ?: 0
        }
        val skip = show?.settings?.skipIntroSecs ?: 0.0
        if (start == 0L && skip > 0) start = (skip * 1000).toLong()
        if (!canPlay(item)) {
            if (!quietly) fail(UiText(R.string.error_cant_play))
            return
        }
        player.load(item, show, start, autoplay, show?.settings?.speed ?: DEFAULT_RATE)
        saveLastPlayed(item, show, start, now())
        loadChapters(item)
    }

    fun resolveResume(useOther: Boolean) {
        val pending = _state.value.pendingResume ?: return
        _state.update { it.copy(pendingResume = null) }
        scope.launch { play(pending.episode, startAt = if (useOther) pending.otherMs else pending.localMs) }
    }

    fun dismissResume() = _state.update { it.copy(pendingResume = null) }

    /** Plays a queued episode now; it leaves the queue. */
    suspend fun play(entry: UpNext.Entry) {
        val ref = SourceRef.parse(entry.source) ?: return
        upNext.remove(entry.id)
        _state.update { it.copy(upNext = upNext.all()) }
        play(SourcedEpisode(ref, entry.episode))
    }

    /** Lets go of the episode everywhere: the player, the mini player, the notification. */
    fun stopPlayback() {
        chaptersJob?.cancel()
        player.stop()
        forgetLastPlayed()
    }

    /**
     * The episode's chapters, from the first place that has them: the feed's inline ones (or the
     * server's), the feed's chapters document (kept for offline), the file's own marks within 6 s.
     * The episode plays meanwhile.
     */
    private fun loadChapters(item: SourcedEpisode, networkAllowed: Boolean = true) {
        chaptersJob?.cancel()
        chaptersJob = scope.launch {
            val episode = item.episode
            var marks = EpisodeChapters.marks(episode.chapters)
            val cached = RoomValueStore(db, "chapters/${item.id}", ListSerializer(Chapter.serializer()))
            if (marks.isEmpty() && episode.chaptersUrl != null) {
                marks = EpisodeChapters.marks(cached.load().orEmpty())
                if (marks.isEmpty() && networkAllowed && !isOffline) {
                    val fetched = runCatching { feeds.chapters(episode.chaptersUrl!!) }.getOrNull().orEmpty()
                    val chapters = fetched.map { Chapter(it.startMs, it.endMs, it.title, it.url, it.imageUrl, it.isInTableOfContents) }
                    if (chapters.isNotEmpty()) cached.save(chapters)
                    marks = EpisodeChapters.marks(chapters)
                }
            }
            if (marks.isEmpty() && networkAllowed && !isOffline) marks = embeddedMarks(item)
            val duration = player.state.value.takeIf { it.item?.id == item.id }?.durationMs?.takeIf { it > 0 } ?: episode.durationMs ?: 0
            val chapters = EpisodeChapters.closed(marks, duration).map { SpokenChapter(it.title, it.startMs, it.endMs ?: it.startMs) }
            player.setChapters(item.id, chapters)
        }
    }

    /** The file's own marks (ID3 CHAP, an M4A's chapter track, Vorbis comments), read by Range. */
    private suspend fun embeddedMarks(item: SourcedEpisode): List<EpisodeChapters.Mark> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(EmbeddedChapters.TIME_LIMIT_MS) {
            val url = runCatching { linkResolver.resolve(PlaybackLinkRequest(item.id)).url }.getOrNull() ?: return@withTimeoutOrNull emptyList()
            val reader = BlockCachingRangeReader(HttpRangeReader(http, url))
            EmbeddedChapters.read(reader).mapIndexed { index, mark ->
                EpisodeChapters.Mark(mark.startMs, mark.endMs, mark.title ?: strings.chapter(index + 1))
            }
        } ?: emptyList()
    }

    /**
     * Where the player fetches an episode from, asked when its data source opens: the publisher's
     * file for a device feed, own.audio's answer to `/playback-info` (a signed link, or the
     * publisher's) for a server's. None of these needs the account's token.
     */
    val linkResolver = PlaybackLinkResolver { request ->
        val (ref, key) = SourcedEpisode.parse(request.mediaId) ?: throw SourceException.NotFound()
        if (offlineMode()) throw SourceException.Network()
        val source = source(ref) ?: throw SourceException.NotFound()
        PlaybackLink(source.playback(key).url)
    }

    private fun positionReported(report: EpisodePlayer.Report) {
        val (ref, _) = SourcedEpisode.parse(report.id) ?: return
        if (source(ref) == null) return
        val played = report.reason == EpisodePlayer.Reason.FINISHED || ListeningProgress.reachedEnd(report.positionMs, report.durationMs.takeIf { it > 0 })
        val value = ListeningProgress(report.positionMs, played, report.at)
        _state.update { state ->
            state.copy(
                progress = state.progress + (report.id to value),
                inbox = if (played) state.inbox.filterNot { it.id == report.id } else state.inbox,
            )
        }
        val current = player.state.value
        if (report.reason != EpisodePlayer.Reason.FINISHED && report.reason != EpisodePlayer.Reason.STOP && current.item?.id == report.id) {
            // The one write every tick: enough to come back here after a kill.
            saveLastPlayed(current.item, current.show, report.positionMs, report.at)
        }
        if (throttle.isDue(report.reason == EpisodePlayer.Reason.PERIODIC, report.id, played, report.at.toEpochMilli())) {
            storeProgress(value, report.id)
        }
    }

    /** Into the source's store: the device's database, or a server's queue and then the server. */
    private fun storeProgress(value: ListeningProgress, id: String) {
        val (ref, key) = SourcedEpisode.parse(id) ?: return
        if (ref == SourceRef.Phone) {
            scope.launch { runCatching { phone.saveProgress(value, key) } }
        } else {
            send(value, id)
        }
    }

    /** Written to the device first, then sent; it stays until the server has it. */
    private fun send(value: ListeningProgress, id: String) {
        val entry = PendingProgress.Entry(id, value)
        scope.launch {
            pending.record(entry)
            if (isOffline) return@launch
            val (ref, key) = SourcedEpisode.parse(id) ?: return@launch
            val source = source(ref) ?: return@launch
            try {
                val kept = source.saveProgress(value, key)
                pending.sent(entry)
                if (kept != value) _state.update { it.copy(progress = it.progress + (id to kept)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Kept for the next refresh.
            }
        }
    }

    /** What a server didn't get, oldest first; a source that fails is left for next time. */
    suspend fun sendPendingProgress() {
        if (isOffline) return
        val failing = HashSet<String>()
        for (entry in pending.all()) {
            val (ref, key) = SourcedEpisode.parse(entry.id) ?: run {
                pending.sent(entry)
                null
            } ?: continue
            if (ref.id in failing) continue
            val source = source(ref) ?: continue
            try {
                source.saveProgress(entry.progress, key)
                pending.sent(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failing += ref.id
            }
        }
    }

    /** Going to the background: the position the throttle held back is stored now. */
    fun flushPosition() {
        val current = player.state.value
        val item = current.item ?: return
        val played = ListeningProgress.reachedEnd(current.positionMs, current.durationMs.takeIf { it > 0 })
        val value = ListeningProgress(current.positionMs, played, now())
        _state.update { it.copy(progress = it.progress + (item.id to value)) }
        saveLastPlayed(item, current.show, current.positionMs, value.updatedAt)
        storeProgress(value, item.id)
    }

    private fun saveLastPlayed(item: SourcedEpisode, show: Show?, positionMs: Long, at: Instant) {
        val last = LastPlayed(item.source.id, item.episode, show, positionMs, at)
        scope.launch { runCatching { lastPlayed.save(last) } }
    }

    private fun forgetLastPlayed() {
        scope.launch { runCatching { lastPlayed.save(null) } }
    }

    /** Paused at its position, in the mini player; nothing opens on the network until Play. */
    private suspend fun restoreLastPlayed() {
        if (player.state.value.item != null) return
        val last = runCatching { lastPlayed.load() }.getOrNull() ?: return
        val ref = SourceRef.parse(last.source) ?: return
        if (source(ref) == null) return
        val item = SourcedEpisode(ref, last.episode)
        last.missedBy(_state.value.progress[item.id])?.let { missed ->
            _state.update { it.copy(progress = it.progress + (item.id to missed)) }
            storeProgress(missed, item.id)
        }
        val show = _state.value.show(item) ?: last.show
        player.load(item, show, last.positionMs, autoplay = false, rate = show?.settings?.speed ?: DEFAULT_RATE, prepare = false)
        loadChapters(item, networkAllowed = false)
    }

    /**
     * For Media3's playback resumption (Bluetooth, the system's media controls after the app was
     * killed): the last episode where it stopped.
     */
    suspend fun resumption(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val last = runCatching { lastPlayed.load() }.getOrNull() ?: return null
        val ref = SourceRef.parse(last.source) ?: return null
        val item = SourcedEpisode(ref, last.episode)
        if (isForPlayback) {
            if (!_state.value.loaded) load()
            play(item, startAt = last.positionMs, autoplay = false, quietly = true)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(last.episode.title)
            .setArtist(last.show?.title)
            .setArtworkUri((last.episode.imageUrl ?: last.show?.imageUrl)?.let(Uri::parse))
            .build()
        val mediaItem = MediaItem.Builder().setMediaId(item.id).setUri(PlaybackUris.forItem(item.id)).setMediaMetadata(metadata).build()
        return MediaSession.MediaItemsWithStartPosition(listOf(mediaItem), 0, last.positionMs)
    }

    // endregion

    // region Up Next

    fun playNext(item: SourcedEpisode) = changeUpNext { it.playNext(entry(item)) }

    fun playLater(item: SourcedEpisode) = changeUpNext { it.playLater(entry(item)) }

    fun removeFromUpNext(id: String) = changeUpNext { it.remove(id) }

    fun moveUpNext(from: Int, to: Int) = changeUpNext { it.move(setOf(from), if (to > from) to + 1 else to) }

    fun clearUpNext() = changeUpNext { it.clear() }

    private fun changeUpNext(work: suspend (UpNext) -> Unit) {
        scope.launch {
            work(upNext)
            _state.update { it.copy(upNext = upNext.all()) }
        }
    }

    private fun entry(item: SourcedEpisode): UpNext.Entry {
        val playing = player.state.value
        val show = _state.value.show(item) ?: playing.show?.takeIf { playing.item?.id == item.id }
        return UpNext.Entry(item.id, item.source.id, item.episode, show)
    }

    /**
     * When an episode ends: the next in Up Next, else the newest new episode that can play now.
     * Nobody is asked where to start: the newer position wins.
     */
    suspend fun playOn(after: String) {
        val state = _state.value
        val next = PlayOn.next(
            finished = after,
            upNext = upNext,
            inbox = state.inbox,
            continueWithNewEpisodes = CONTINUE_WITH_NEW_EPISODES,
            canPlay = ::canPlay,
            isPlayed = { state.progress[it.id]?.played == true },
            shows = { state.show(it) },
        )
        _state.update { it.copy(upNext = upNext.all()) }
        if (next == null) {
            log.info("podcasts", "nothing to play on")
            return
        }
        play(next.episode, automatic = true)
    }

    /** Shows set to add their new episodes to Up Next: what arrived since the last look. */
    private suspend fun queueNewEpisodes() {
        val state = _state.value
        val autoShows = state.shows.filter { it.show.settings.autoQueue }.map { it.id }.toSet()
        val result = AutoQueue.arrivals(preferences.autoQueuedAt()?.let(Instant::ofEpochMilli), now(), state.inbox, autoShows)
        preferences.setAutoQueuedAt(result.lookedAt.toEpochMilli())
        if (result.toQueue.isEmpty()) return
        result.toQueue.forEach { upNext.playLater(UpNext.Entry(it.id, it.source.id, it.episode, state.show(it))) }
        _state.update { it.copy(upNext = upNext.all()) }
    }

    // endregion

    // region Discover

    /** own.audio's directory where a server has one (browse or search), else Apple's. */
    fun catalogue(): ShowCatalogue {
        val records = ordered(servers.value.servers)
        for (record in records) {
            val directory = ownAudio.value[record.id]?.directory ?: continue
            return OwnAudioShowCatalogue(directory, record.displayName, apple)
        }
        return apple
    }

    suspend fun search(term: String): List<ShowSearchResult> = catalogue().search(term)

    /**
     * "More Like Your Shows": shows like the six followed most recently, a few from each in turn,
     * without any already followed. From what is followed, never from what is played.
     */
    suspend fun loadSuggestions(force: Boolean = false) {
        if (!_state.value.catalogueSuggests || isOffline) return
        if (!force && _state.value.suggestions != null) return
        val catalogue = catalogue()
        val seeds = _state.value.shows.filter { it.show.feedUrl != null }.sortedByDescending { it.show.followedAt }.take(6)
        val lists = kotlinx.coroutines.coroutineScope {
            seeds.map { seed -> async { seed.show.title to (runCatching { catalogue.similar(seed.show.feedUrl!!) }.getOrNull().orEmpty()) } }.awaitAll()
        }
        val seen = _state.value.shows.mapNotNull { it.show.feedUrl?.lowercase() }.toMutableSet()
        val out = mutableListOf<Suggestion>()
        var round = 0
        while (out.size < MAX_SUGGESTIONS && lists.any { round < it.second.size }) {
            for ((because, list) in lists) {
                if (round >= list.size) continue
                val show = list[round]
                if (seen.add(show.feedUrl.lowercase())) out += Suggestion(show, because)
            }
            round++
        }
        _state.update { it.copy(suggestions = out.take(MAX_SUGGESTIONS)) }
    }

    /** Shows like [feedUrl] from the catalogue, without the followed ones; null without one that knows. */
    suspend fun similarShows(feedUrl: String): List<ShowSearchResult>? {
        if (!_state.value.catalogueSuggests || isOffline) return null
        val found = runCatching { catalogue().similar(feedUrl) }.getOrNull() ?: return null
        val followed = _state.value.shows.mapNotNull { it.show.feedUrl?.lowercase() }.toSet()
        return found.filter { it.feedUrl.lowercase() !in followed && it.feedUrl != feedUrl }
    }

    // endregion

    // region Foreground, links, errors

    /**
     * While the app is on screen and online, each own.audio server's `/events` stream is
     * followed; a change there reads the lists again. In the background it stops.
     */
    fun setForeground(foreground: Boolean) {
        if (foreground == inForeground) return
        inForeground = foreground
        followLiveUpdates()
        if (foreground) {
            if (_state.value.loaded) scope.launch { refresh(asked = false) }
        } else {
            flushPosition()
        }
    }

    private fun followLiveUpdates() {
        liveUpdates.values.forEach { it.cancel() }
        liveUpdates = emptyMap()
        if (!inForeground || isOffline || network.value.dataSaver || !_state.value.loaded) return
        liveUpdates = ownAudio.value.mapValues { (_, source) ->
            scope.launch { source.followLiveUpdates(onChange = { reloadLists() }) }
        }
    }

    /** A `feed://`, `podcast://` or `pcast://` link: Add a Show opens with it filled in. */
    fun openFeedLink(address: String) = _state.update { it.copy(pendingFeedAddress = address) }

    fun feedLinkHandled() = _state.update { it.copy(pendingFeedAddress = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun fail(text: UiText) = _state.update { it.copy(error = text) }

    private fun perform(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(message(error))
            }
        }
    }

    // endregion

    companion object {
        const val DEFAULT_RATE = 1.0f
        const val RESUME_QUESTION_MS = 30_000L
        const val REMOTE_POSITION_TIMEOUT_MS = 4_000L
        const val MAX_SUGGESTIONS = 20

        /** Settings' Play New Episodes After Up Next comes with P9; on, as its default. */
        const val CONTINUE_WITH_NEW_EPISODES = true
        val MIN_REFRESH_INTERVAL: Duration = Duration.ofMinutes(15)

        /** What the listener is told when something fails. */
        fun message(error: Throwable): UiText = when (error) {
            is SourceException.NotAFeed -> UiText(R.string.error_not_a_feed)
            is SourceException.SignInRejected -> UiText(R.string.error_signed_out)
            is SourceException.Network -> UiText(R.string.error_cant_reach)
            is SourceException.NotFound -> UiText(R.string.error_episode_gone)
            else -> UiText(R.string.error_something)
        }
    }
}
