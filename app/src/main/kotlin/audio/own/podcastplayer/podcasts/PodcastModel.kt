package audio.own.podcastplayer.podcasts

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import audio.own.core.servers.NetworkState
import audio.own.core.servers.ServerLibraryRef
import audio.own.core.servers.ServerRecord
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.DeviceInfo
import audio.own.kit.gpodder.GPodderClient
import audio.own.kit.gpodder.GPodderException
import audio.own.kit.ownaudio.SettingsPatch
import audio.own.kit.ownaudio.updateSettings as updateAccountSettings
import audio.own.player.core.SkipIntervals
import audio.own.player.spoken.SmartRewind
import audio.own.podcast.library.AudiobookshelfPodcastSource
import audio.own.podcast.library.FeedLoader
import audio.own.podcast.library.GPodderSync
import audio.own.podcast.library.GPodderSyncState
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.player.core.PlaybackLink
import audio.own.player.core.PlaybackLinkResolver
import audio.own.player.core.PlaybackUris
import audio.own.player.spoken.SpokenChapter
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
import audio.own.podcastplayer.app.PlaybackSettings
import audio.own.podcastplayer.app.UserFacingException
import audio.own.podcastplayer.servers.gpodderProblem
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.playback.PlaybackEngine
import audio.own.podcastplayer.playback.VoiceSearch
import audio.own.podcastplayer.downloads.EpisodeDownloader
import audio.own.podcastplayer.downloads.DownloadsState
import audio.own.podcast.library.DownloadRules
import audio.own.podcast.library.EvictionPolicy
import audio.own.podcast.library.Playback
import java.util.concurrent.ConcurrentHashMap
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.ServersState
import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

/** Whether a show of own.audio may be shared with the family here (only by its owner), and whether it is. */
data class FamilySharing(val canChange: Boolean, val shared: Boolean)

/**
 * The servers' clients and the few things the model tells the servers model, for the kinds
 * beyond own.audio; [None] for a model with own.audio and feeds only.
 */
interface ServerAccounts {
    fun audiobookshelf(serverId: String): AudiobookshelfClient? = null

    fun gpodder(serverId: String): GPodderClient? = null

    /** The address requests go to now, so a source is told when it changes. */
    fun address(serverId: String): String? = null

    suspend fun gpodderDevice(): String = "own-audio-podcasts"

    suspend fun noteSynced(serverId: String, atEpochMs: Long) {}

    suspend fun updateLibraries(serverId: String, libraries: List<ServerLibraryRef>) {}

    companion object {
        val None = object : ServerAccounts {}
    }
}

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
    /** Each gPodder account's shows as of its last sync: the device's shows it holds. */
    val accountFeeds: Map<String, Set<String>> = emptyMap(),
    /** gPodder accounts syncing now. */
    val syncing: Set<String> = emptySet(),
    val settings: PlaybackSettings = PlaybackSettings(),
    /** What is on the device, and on its way. */
    val downloads: DownloadsState = DownloadsState(),
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

    private fun matches(source: SourceRef, show: Show?, active: SourceRef): Boolean = when {
        active is SourceRef.Library -> source == SourceRef.Server(active.uuid) && show?.collection == active.libraryId
        // A gPodder account holds no library of its own: its choice is the device's shows on the account.
        active.id in accountFeeds -> source == SourceRef.Phone && show?.feedUrl?.let { it in accountFeeds[active.id].orEmpty() } == true
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
    private val accounts: ServerAccounts = ServerAccounts.None,
    /** Episodes kept on the device; none in a model built without them. */
    private val downloads: EpisodeDownloader = EpisodeDownloader.None,
    /** What Audiobookshelf's sessions show for this device. */
    private val absDevice: DeviceInfo = DeviceInfo("own-audio-podcasts", "own.audio Podcasts", "1", "", ""),
    private val syncDelayMs: Long = SYNC_DELAY_MS,
    /** The app's language, two letters: what summaries are written in. */
    private val language: () -> String = { Locale.getDefault().language },
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
    private var shelves: Map<String, AudiobookshelfPodcastSource> = emptyMap()
    private var syncs: Map<String, GPodderSync> = emptyMap()
    private val listeningStores = HashMap<String, ListeningStore>()
    private val catalogueStores = HashMap<String, RoomOwnAudioCatalogueStore>()
    /** The address each source's client was made for: a new one restarts the live updates. */
    private val sourceAddresses = HashMap<String, String?>()
    private val librariesRead = HashSet<String>()
    private var knownServers: Set<String>? = null
    private var settings = PlaybackSettings()
    private var syncJob: Job? = null

    private val throttle = PositionThrottle()
    private var lastRefreshAt: Instant? = null
    private val refreshLock = Mutex()
    private var liveUpdates: Map<String, Job> = emptyMap()
    private var inForeground = false

    val apple: ShowCatalogue = AppleShowCatalogue(http, country)

    /** Episodes whose last Audiobookshelf link was refused (`401`): the next link takes a new token. */
    private val refusedLinks = ConcurrentHashMap<String, String>()

    /** Each episode's position as it last went past memory, so an unchanged one isn't sent again. */
    private val stored = HashMap<String, ListeningProgress>()

    init {
        player.listener = object : PodcastPlayer.Listener {
            override fun onPosition(report: PodcastPlayer.Report) = positionReported(report)

            override fun onEnded(id: String, playOn: Boolean) {
                forgetLastPlayed()
                if (playOn) scope.launch { playOn(after = id) }
            }

            override fun onLinkFailed(id: String, url: String?, status: Int?) = linkFailed(id, url, status)
        }
    }

    // region Loading

    /** At launch: the stores, the sources, the lists, the last episode paused, a refresh. */
    suspend fun load() {
        if (_state.value.loaded) return
        val upNextList = upNext.all()
        val active = preferences.activeSource.first()
        applySettings(preferences.playback.first())
        _state.update { it.copy(upNext = upNextList, activeSource = active, offline = offlineReason()) }
        scope.launch { preferences.playback.drop(1).collect { applySettings(it) } }
        scope.launch { network.map { it.dataSaver }.distinctUntilChanged().drop(1).collect { applySettings(settings) } }
        servers.first { it.loaded }
        rebuildSources(servers.value)
        reloadLists()
        _state.update { it.copy(loaded = true) }
        // A source remembered from before that is gone now would filter everything away.
        if (active != null && _state.value.sources.none { it.ref.id == active }) setActiveSource(null)
        restoreLastPlayed()
        scope.launch { servers.drop(1).collect { rebuildSources(it) } }
        // Without a network the player waits, and goes on by itself when it is back.
        scope.launch { network.map { it.connected }.distinctUntilChanged().collect { player.networkChanged(it) } }
        scope.launch {
            network.map { it.connected }.distinctUntilChanged().drop(1).collect { connected ->
                _state.update { it.copy(offline = offlineReason()) }
                if (connected) {
                    sendPendingProgress()
                    reloadLists()
                }
                followLiveUpdates()
            }
        }
        watchDownloads()
        refresh(asked = false)
    }

    /**
     * A finished download counts against the storage cap, and the episode playing moves to its
     * file at the next pause.
     */
    private fun watchDownloads() {
        scope.launch {
            downloads.state.map { it.totalBytes }.distinctUntilChanged().drop(1).collect { enforceCap() }
        }
        scope.launch {
            downloads.state.collect { held ->
                _state.update { it.copy(downloads = held) }
                val playing = player.state.value.item?.id ?: return@collect
                if (held.isDownloaded(playing)) player.useLocalCopy(playing)
            }
        }
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
     * One source per signed-in own.audio and Audiobookshelf server, and one sync per gPodder
     * account, each made once for the life of the process: a new address or sign-in only gives
     * it a new client, so a first catalogue snapshot is never taken twice. The device's catalogue
     * and listening state of each server outlive the source object.
     */
    private suspend fun rebuildSources(state: ServersState) {
        if (!state.loaded) return
        val ids = state.servers.map { it.id }.toSet()
        knownServers?.let { before -> (before - ids).forEach { forgetServer(it) } }
        knownServers = ids
        var moved = false
        val nextOwnAudio = LinkedHashMap<String, OwnAudioPodcastSource>()
        val nextShelves = LinkedHashMap<String, AudiobookshelfPodcastSource>()
        val nextSyncs = LinkedHashMap<String, GPodderSync>()
        val newSyncs = mutableListOf<String>()
        for (record in ordered(state.servers)) {
            val address = accounts.address(record.id) ?: state.connections[record.id]?.let { (it as? audio.own.podcastplayer.servers.ConnectionState.Connected)?.url }
            if (sourceAddresses[record.id] != address) moved = moved || record.id in sourceAddresses
            when (record.kind) {
                ServerKinds.OWN_AUDIO -> {
                    val client = client(record.id) ?: continue
                    val modules = state.modules[record.id]
                    val held = ownAudio.value[record.id]
                    if (held != null) {
                        held.client = client
                        if (modules != null) held.modules = modules
                        nextOwnAudio[record.id] = held
                    } else {
                        val catalogue = catalogueStores.getOrPut(record.id) { RoomOwnAudioCatalogueStore(db, record.id) }
                        nextOwnAudio[record.id] = OwnAudioPodcastSource(client, listening(record.id), catalogue, modules.orEmpty(), now)
                    }
                }
                ServerKinds.AUDIOBOOKSHELF -> {
                    val client = accounts.audiobookshelf(record.id) ?: continue
                    val canAdd = state.extras(record.id).canAddShows
                    val held = shelves[record.id]
                    if (held != null) {
                        held.client = client
                        held.canAddShows = canAdd
                        nextShelves[record.id] = held
                    } else {
                        nextShelves[record.id] = AudiobookshelfPodcastSource(client, listening(record.id), FeedLoader { url, validators -> feeds.fetch(url, validators) }, canAdd, absDevice, now = now)
                    }
                    if (librariesRead.add(record.id)) scope.launch { readLibraries(record.id) }
                }
                ServerKinds.GPODDER -> {
                    val client = accounts.gpodder(record.id) ?: continue
                    val held = syncs[record.id]
                    if (held != null && sourceAddresses[record.id] == address && (record.auth as? audio.own.core.servers.ServerAuth.GPodder)?.password == passwords[record.id]) {
                        nextSyncs[record.id] = held
                    } else {
                        val device = (record.auth as? audio.own.core.servers.ServerAuth.GPodder)?.deviceId ?: accounts.gpodderDevice()
                        nextSyncs[record.id] = GPodderSync(client, phone, device, RoomValueStore(db, "${record.id}/gpodder-$device", GPodderSyncState.serializer()))
                        passwords[record.id] = (record.auth as? audio.own.core.servers.ServerAuth.GPodder)?.password
                        if (held == null) newSyncs += record.id
                    }
                }
            }
            sourceAddresses[record.id] = address
        }
        ownAudio.value = nextOwnAudio
        shelves = nextShelves
        syncs = nextSyncs
        _state.update { it.copy(sources = switchable(state), followTargets = followTargets(state), catalogueServer = catalogueServer(state)) }
        // A library turned off, or a server gone, that the switch was on: everything again.
        val active = _state.value.activeSource
        if (_state.value.loaded && active != null && _state.value.sources.none { it.ref.id == active }) {
            val server = (SourceRef.parse(active) as? SourceRef.Library)?.let { SourceRef.Server(it.uuid) }
            setActiveSource(server?.takeIf { ref -> _state.value.sources.any { it.ref == ref } }?.id)
        }
        // A check of the connection or a sync's time changes the servers' state without changing
        // what the lists hold: only a change of source, address, sign-in or library reads them again.
        val signature = ordered(state.servers).map { record ->
            listOf(record.id, sourceAddresses[record.id], record.auth.isSignedIn, record.libraries, state.extras(record.id).hiddenLibraries, state.modules[record.id])
        }
        val changed = signature != listsSignature
        listsSignature = signature
        if (_state.value.loaded) {
            if (moved || newSyncs.isNotEmpty()) followLiveUpdates()
            if (changed) reloadLists()
            // An account just added: its first sync now, so the device's shows reach it.
            newSyncs.forEach { id -> scope.launch { runCatching { sync(id) } } }
        }
        if (newSyncs.isNotEmpty() || _state.value.accountFeeds.keys.any { it !in nextSyncs }) noteAccountFeeds()
    }

    private val passwords = HashMap<String, String?>()
    private var listsSignature: List<List<Any?>>? = null

    private fun listening(serverId: String) =
        listeningStores.getOrPut(serverId) { ListeningStore(RoomValueStore(db, "$serverId/listening", ListeningState.serializer()), now) }

    /** The podcast libraries an Audiobookshelf server has now, for the server page and the switch. */
    private suspend fun readLibraries(serverId: String) {
        val source = shelves[serverId] ?: return
        val found = try {
            source.podcastLibraries()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            librariesRead.remove(serverId)
            return
        }
        accounts.updateLibraries(serverId, found.map { ServerLibraryRef(it.id, it.name) })
    }

    private suspend fun forgetServer(serverId: String) {
        log.info("podcasts", "forgetting a removed server's shows")
        if (player.state.value.item?.source?.serverId?.toString() == serverId) stopPlayback()
        ownAudio.update { it - serverId }
        shelves = shelves - serverId
        // A gPodder account's shows are the device's: they stay, no longer kept in step.
        syncs[serverId]?.let { syncs = syncs - serverId }
        _state.update { it.copy(accountFeeds = it.accountFeeds - serverId) }
        listeningStores.remove(serverId)
        catalogueStores.remove(serverId)
        sourceAddresses.remove(serverId)
        librariesRead.remove(serverId)
        passwords.remove(serverId)
        db.forgetServer(serverId)
        downloads.removeAll(serverId)
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

    /**
     * own.audio first, always; then this device; then Audiobookshelf and the gPodder accounts. A
     * server with several libraries shown offers each one after itself.
     */
    private fun switchable(state: ServersState): List<SourceInfo> {
        val records = ordered(state.servers).filter { it.id in ownAudio.value || it.id in shelves || it.id in syncs }
        val phone = SourceInfo(SourceRef.Phone, strings.thisDevice, null)
        return records.filter { it.kind == ServerKinds.OWN_AUDIO }.map(::info) + phone + records.filter { it.kind != ServerKinds.OWN_AUDIO }.flatMap { entries(it, state) }
    }

    private fun entries(record: ServerRecord, state: ServersState): List<SourceInfo> {
        val libraries = visibleLibraries(record, state)
        val uuid = java.util.UUID.fromString(record.id)
        return listOf(info(record)) + if (libraries.size > 1) libraries.map { SourceInfo(SourceRef.Library(uuid, it.id), it.name, record.kind) } else emptyList()
    }

    private fun visibleLibraries(record: ServerRecord, state: ServersState): List<ServerLibraryRef> {
        val hidden = state.extras(record.id).hiddenLibraries
        return record.libraries.orEmpty().filter { it.id !in hidden }
    }

    /**
     * Where shows can be followed: the device always, and every server that takes a feed
     * address; an Audiobookshelf server with several libraries shown, each of them.
     */
    private fun followTargets(state: ServersState): List<SourceInfo> =
        listOf(SourceInfo(SourceRef.Phone, strings.thisDevice, null)) +
            ordered(state.servers).filter { source(SourceRef.Server(java.util.UUID.fromString(it.id)))?.capabilities?.contains(Capability.ADD_BY_URL) == true }.flatMap { record ->
                val libraries = visibleLibraries(record, state)
                if (record.kind == ServerKinds.AUDIOBOOKSHELF && libraries.size > 1) {
                    libraries.map { SourceInfo(SourceRef.Library(java.util.UUID.fromString(record.id), it.id), "${record.displayName} · ${it.name}", record.kind) }
                } else {
                    listOf(info(record))
                }
            }

    /** The first own.audio server whose directory can be browsed. */
    private fun catalogueServer(state: ServersState): String? =
        ordered(state.servers).firstOrNull { ownAudio.value[it.id]?.directory?.canBrowse == true }?.displayName

    fun source(ref: SourceRef): PodcastSource? = when (ref) {
        SourceRef.Phone -> phone
        is SourceRef.Server -> ownAudio.value[ref.id] ?: shelves[ref.id]
        is SourceRef.Library -> ownAudio.value[ref.uuid.toString()] ?: shelves[ref.uuid.toString()]
    }

    private fun shelf(ref: SourceRef): AudiobookshelfPodcastSource? = ref.serverId?.let { shelves[it.toString()] }

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
            if (!isOffline) (ownAudio.value.keys + shelves.keys).forEach { add(SourceRef.Server(java.util.UUID.fromString(it))) }
        }
        val answers = coroutineScopeAll(refs) { ref ->
            val source = source(ref) ?: return@coroutineScopeAll null
            try {
                // A library turned off on the server page leaves the lists.
                val hidden = ref.serverId?.let { servers.value.extras(it.toString()).hiddenLibraries }.orEmpty()
                val kept = source.shows().filter { show -> show.collection?.let { it !in hidden } ?: true }
                val keptIds = kept.map { it.id }.toSet()
                val shows = kept.map { SourcedShow(ref, it) }
                val inbox = source.inbox(Inbox.LIMIT).filter { hidden.isEmpty() || it.showId in keptIds }.map { SourcedEpisode(ref, it) }
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
            for (id in syncs.keys) {
                try {
                    sync(id, reload = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.error("gpodder", "sync failed: ${error.javaClass.simpleName}")
                }
            }
            phone.refresh(null, asked)
            (ownAudio.value.values + shelves.values).forEach { source ->
                try {
                    source.refresh(null, asked)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Shown as unreachable by the reload.
                }
            }
            reloadLists()
            applyDownloadRules()
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
        applyDownloadRules()
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
        val shelf = shelf(target)
        val show = if (target is SourceRef.Library && shelf != null) shelf.follow(feedUrl, target.libraryId) else source.follow(feedUrl)
        if (target == SourceRef.Phone) scheduleSync()
        reloadLists()
        val ref = target.serverId?.let { SourceRef.Server(it) } ?: SourceRef.Phone
        return _state.value.shows.firstOrNull { it.source == ref && it.show.id == show.id } ?: SourcedShow(ref, show)
    }

    fun unfollow(show: SourcedShow, after: () -> Unit = {}) = perform {
        source(show.source)?.unfollow(show.show.id)
        val current = player.state.value.item
        if (current?.source == show.source && current.episode.showId == show.show.id) stopPlayback()
        upNext.removeAll { it.source == show.source.id && it.episode.showId == show.show.id }
        downloads.remove(downloads.state.value.entries.values.filter { it.record.source == show.source.id && it.record.episode.showId == show.show.id }.map { it.id })
        if (show.source == SourceRef.Phone) scheduleSync()
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
        applyDownloadRules()
    }

    /** Whether unfollowing takes the show away from the family too (own.audio). */
    suspend fun isSharedWithFamily(show: SourcedShow): Boolean = ownAudioSource(show.source)?.isSharedWithFamily(show.show.id) == true

    /**
     * Whether the show is shared with the family, and whether this account may change that: only
     * the account that follows it for the household (the record's owner) can. Null outside own.audio.
     */
    suspend fun familySharing(show: SourcedShow): FamilySharing? {
        val source = ownAudioSource(show.source) ?: return null
        val me = show.source.serverId?.let { servers.value.server(it.toString())?.userId }
        val owner = source.owner(show.show.id)
        return FamilySharing(canChange = me != null && owner == me, shared = source.isSharedWithFamily(show.show.id))
    }

    /** Shares the show with the family, or keeps it to its owner; [after] gets what the server holds. */
    fun setSharedWithFamily(show: SourcedShow, shared: Boolean, after: (Boolean) -> Unit = {}) = perform {
        val source = ownAudioSource(show.source) ?: return@perform
        source.setSharedWithFamily(show.show.id, shared)
        after(source.isSharedWithFamily(show.show.id))
    }

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
            scheduleSync()
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
        if (played) applyDownloadRules()
    }

    /**
     * Offline, only what is on the device can play: a download, or a whole copy in the listening
     * cache; on mobile data with Downloaded Only, the same.
     */
    fun canPlay(item: SourcedEpisode): Boolean =
        (!isOffline && !onlyDownloaded) || downloads.state.value.isDownloaded(item.id) || isFullyCached(item.id)

    private val onlyDownloaded: Boolean
        get() = settings.mobileData == PlaybackSettings.MobileData.DOWNLOADED_ONLY && network.value.connected && network.value.metered

    // endregion

    // region Playing

    /**
     * Plays [item] from [startAt], or from where the listener was. A server may know a newer
     * position from another device: when it differs by more than 30 s, the listener is asked
     * ([PodcastState.pendingResume]) — unless [automatic] (playing on), where the newer wins.
     */
    suspend fun play(item: SourcedEpisode, startAt: Long? = null, autoplay: Boolean = true, automatic: Boolean = false, quietly: Boolean = false) {
        val downloaded = downloads.state.value.entries[item.id]?.takeIf { it.isDone }
        // A download still plays when its server was removed; it has nowhere to report to.
        val source = source(item.source)
        if (source == null && downloaded == null) return
        val show = _state.value.show(item) ?: _state.value.upNext.firstOrNull { it.id == item.id }?.show ?: downloaded?.record?.show
        var start: Long
        if (startAt != null) {
            start = startAt
        } else {
            val local = _state.value.progress[item.id]
            val remote = if (item.source == SourceRef.Phone || isOffline || source == null) {
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
            if (!quietly) fail(UiText(if (isOffline) R.string.error_not_on_device else R.string.error_mobile_data))
            return
        }

        refusedLinks.remove(item.id)
        val chapters = chapters(item)
        player.load(item, show, start, autoplay, show?.settings?.speed ?: settings.defaultRate, chapters = chapters)
        if (chapters.isEmpty()) fetchChapters(item)
        saveLastPlayed(item, show, start, now())
        if (downloaded != null) downloads.notePlayed(item.id)
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
        player.stop()
        forgetLastPlayed()
    }

    /**
     * The chapters the player starts with: the feed's inline ones (or the server's), else the
     * feed's chapters document kept from an earlier play. With none, the player reads the file's
     * own marks until [fetchChapters] brings the document.
     */
    private suspend fun chapters(item: SourcedEpisode): List<SpokenChapter> {
        val episode = item.episode
        var marks = EpisodeChapters.marks(episode.chapters)
        if (marks.isEmpty() && episode.chaptersUrl != null) marks = EpisodeChapters.marks(chaptersStore(item).load().orEmpty())
        return spokenChapters(marks, episode.durationMs)
    }

    private fun chaptersStore(item: SourcedEpisode) = RoomValueStore(db, "chapters/${item.id}", ListSerializer(Chapter.serializer()))

    /**
     * The feed's chapters document, fetched once on the model's scope and kept for offline: Play
     * doesn't wait for a slow feed host; the player takes the chapters when they arrive.
     */
    private fun fetchChapters(item: SourcedEpisode) {
        val url = item.episode.chaptersUrl ?: return
        if (isOffline) return
        scope.launch {
            val fetched = runCatching { feeds.chapters(url) }.getOrNull().orEmpty()
                .map { Chapter(it.startMs, it.endMs, it.title, it.url, it.imageUrl, it.isInTableOfContents) }
            if (fetched.isEmpty()) return@launch
            chaptersStore(item).save(fetched)
            val late = spokenChapters(EpisodeChapters.marks(fetched), item.episode.durationMs)
            if (late.isNotEmpty()) player.setChapters(item.id, late)
        }
    }

    private fun spokenChapters(marks: List<EpisodeChapters.Mark>, durationMs: Long?): List<SpokenChapter> =
        EpisodeChapters.closed(marks, durationMs ?: 0).map { SpokenChapter(it.title, it.startMs, it.endMs ?: it.startMs) }

    /**
     * Where the player fetches an episode from, asked when its data source opens: the publisher's
     * file for a device feed, own.audio's answer to `/playback-info` (a signed link, or the
     * publisher's) for a server's. None of these needs the account's token.
     */
    val linkResolver = PlaybackLinkResolver { request ->
        val (ref, key) = SourcedEpisode.parse(request.mediaId) ?: throw SourceException.NotFound()
        if (offlineMode()) throw SourceException.Network()
        val source = source(ref) ?: throw SourceException.NotFound()
        refusedLinks.remove(request.mediaId)?.let { refused -> shelf(ref)?.renewAfterRefusal(refused) }
        val playback = source.playback(key)
        // The player asks again a little before a signed link or a token in it runs out.
        PlaybackLink(playback.url, playback.expiresAt?.toEpochMilli())
    }

    /**
     * Audiobookshelf refused the token in an episode's link: the player asks for a link once
     * more, and that one takes a fresh token. A second refusal is the player's error.
     */
    private fun linkFailed(id: String, url: String?, status: Int?) {
        val (ref, _) = SourcedEpisode.parse(id) ?: return
        if (status == 401 && url != null && shelf(ref) != null) refusedLinks[id] = url
    }

    /** Where a download of [item] comes from: the source's own answer, as for playing it. */
    suspend fun downloadPlayback(item: SourcedEpisode): Playback {
        // A worker can start before any screen: the sources come with the library's load.
        _state.first { it.loaded }
        if (offlineMode()) throw SourceException.Network()
        val source = source(item.source) ?: throw SourceException.NotFound()
        return source.playback(item.episode.key)
    }

    private fun positionReported(report: PodcastPlayer.Report) {
        val (ref, _) = SourcedEpisode.parse(report.id) ?: return
        if (source(ref) == null) return
        val played = report.reason == PodcastPlayer.Reason.FINISHED || ListeningProgress.reachedEnd(report.positionMs, report.durationMs.takeIf { it > 0 })
        val value = ListeningProgress(report.positionMs, played, report.at)
        _state.update { state ->
            state.copy(
                progress = state.progress + (report.id to value),
                inbox = if (played) state.inbox.filterNot { it.id == report.id } else state.inbox,
            )
        }
        val current = player.state.value
        if (report.reason != PodcastPlayer.Reason.FINISHED && report.reason != PodcastPlayer.Reason.STOP && current.item?.id == report.id) {
            // The one write every tick: enough to come back here after a kill.
            saveLastPlayed(current.item, current.show, report.positionMs, report.at)
        }
        if (throttle.isDue(report.reason == PodcastPlayer.Reason.PERIODIC, report.id, played, report.at.toEpochMilli())) {
            storeProgress(value, report.id)
        }
        // A device episode paused, stopped or finished: the gPodder accounts hear of it shortly.
        if (ref == SourceRef.Phone && report.reason in SYNCING_REASONS) scheduleSync()
        // Heard to the end: a show's rules may let it go, or fetch the next.
        if (played && report.reason == PodcastPlayer.Reason.FINISHED) scope.launch { applyDownloadRules() }
    }

    /** Into the source's store: the device's database, or a server's queue and then the server. */
    private fun storeProgress(value: ListeningProgress, id: String) {
        val (ref, key) = SourcedEpisode.parse(id) ?: return
        stored[id] = value
        if (ref == SourceRef.Phone) {
            scope.launch { runCatching { phone.saveProgress(value, key) } }
        } else {
            send(value, id)
        }
    }

    /** Written to the device first, then sent; it stays until the server has it. */
    private fun send(value: ListeningProgress, id: String) {
        stored[id] = value
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

    /**
     * Going to the background: the position the throttle held back is stored now. One that
     * hasn't moved since it was last stored (an episode left paused) is not sent again: each trip
     * to the background would otherwise put the same position to the server with a new time.
     */
    fun flushPosition() {
        val current = player.state.value
        val item = current.item ?: return
        val played = ListeningProgress.reachedEnd(current.positionMs, current.durationMs.takeIf { it > 0 })
        if (stored[item.id]?.let { it.positionMs == current.positionMs && it.played == played } == true) return
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
        val held = _state.value.progress[item.id]
        val missed = last.missedBy(held)
        if (missed != null) {
            _state.update { it.copy(progress = it.progress + (item.id to missed)) }
            storeProgress(missed, item.id)
        } else {
            // The source has this position already.
            stored[item.id] = ListeningProgress(last.positionMs, held?.played ?: false, last.at)
        }
        val show = _state.value.show(item) ?: last.show
        player.load(item, show, last.positionMs, autoplay = false, rate = show?.settings?.speed ?: settings.defaultRate, prepare = false, chapters = chapters(item))
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
            // What the player loaded, the downloaded file included, rather than a new placeholder.
            player.resumptionItems()?.let { return it }
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
            continueWithNewEpisodes = settings.continueWithNewEpisodes,
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

    // region Downloads

    /** Keeps [item] on the device; the listener asked, so it starts now. */
    fun download(item: SourcedEpisode) = perform {
        downloads.download(item, _state.value.show(item))
        enforceCap()
    }

    fun removeDownload(item: SourcedEpisode) = perform { downloads.remove(listOf(item.id)) }

    /** Try the Download Again: from the start, as the listener's. */
    fun retryDownload(item: SourcedEpisode) = perform {
        val show = _state.value.show(item) ?: downloads.state.value.entries[item.id]?.record?.show
        downloads.remove(listOf(item.id))
        downloads.download(item, show)
    }

    fun retryFailedDownloads() = perform { downloads.retryFailed() }

    fun removeAllDownloads() = perform { downloads.removeAll() }

    /**
     * Each show's rules (download the newest, delete when played, keep at most) against its
     * episodes as they are now. Offline nothing is downloaded and nothing is let go; the episode
     * playing is never removed.
     */
    suspend fun applyDownloadRules() {
        if (isOffline) return
        val state = _state.value
        val playing = player.state.value.item?.id
        for (show in state.shows.filter { it.show.settings.hasDownloadRules }) {
            val episodes = try {
                episodes(show)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            val held = downloads.state.value
            val items = episodes.map {
                DownloadRules.Item(it.id, it.episode.publishedAt ?: it.episode.firstSeenAt, isPlayed = state.progress[it.id]?.played == true, isDownloaded = it.id in held.entries)
            }
            val plan = DownloadRules.plan(items, show.show.settings, playing)
            if (plan.remove.isNotEmpty()) downloads.remove(plan.remove)
            for (id in plan.download) {
                val episode = episodes.firstOrNull { it.id == id } ?: continue
                downloads.download(episode, show.show, automatic = true)
            }
            if (plan.download.isNotEmpty() || plan.remove.isNotEmpty()) log.info("downloads", "${show.show.title}: ${plan.download.size} to download, ${plan.remove.size} to remove")
        }
        enforceCap()
    }

    /** Over Keep at Most: played episodes go first, then the ones not played for longest; never the one playing. */
    private suspend fun enforceCap() = capLock.withLock {
        val cap = settings.keepAtMostBytes ?: return@withLock
        val progress = _state.value.progress
        val candidates = downloads.state.value.entries.values.filter { it.isDone }.map {
            EvictionPolicy.Candidate(it.id, it.bytes, progress[it.id]?.played == true, Instant.ofEpochMilli(it.lastPlayedAt ?: it.finishedAt ?: it.queuedAt))
        }
        val out = EvictionPolicy(cap).evictions(candidates, player.state.value.item?.id)
        if (out.isEmpty()) return@withLock
        log.info("downloads", "${out.size} let go over Keep at Most")
        downloads.remove(out)
    }

    /** One pass at a time: a removal changes the total, which asks for another pass. */
    private val capLock = Mutex()


    // endregion

    // region gPodder sync

    /**
     * After the device's shows or positions changed: one sync of each gPodder account a few
     * seconds later, however many changes come in between.
     */
    private fun scheduleSync() {
        if (syncs.isEmpty() || isOffline) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(syncDelayMs)
            for (id in syncs.keys) runCatching { sync(id) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Sync Now on the server page; the listener reads why it failed. */
    suspend fun syncNow(serverId: String) {
        try {
            sync(serverId)
        } catch (error: GPodderException) {
            throw gpodderProblem(error)
        }
    }

    private suspend fun sync(serverId: String, reload: Boolean = true) {
        val sync = syncs[serverId] ?: return
        if (isOffline) return
        _state.update { it.copy(syncing = it.syncing + serverId) }
        try {
            val report = sync.sync()
            accounts.noteSynced(serverId, now().toEpochMilli())
            val feeds = sync.accountFeeds()
            _state.update { it.copy(accountFeeds = it.accountFeeds + (serverId to feeds)) }
            val changed = report.followed + report.unfollowed + report.positionsApplied > 0
            if (changed) log.info("gpodder", "synced: ${report.followed} followed, ${report.unfollowed} unfollowed, ${report.positionsApplied} positions")
            if (reload && changed) reloadLists()
        } finally {
            _state.update { it.copy(syncing = it.syncing - serverId) }
        }
    }

    private suspend fun noteAccountFeeds() {
        val feeds = syncs.mapValues { (_, sync) -> runCatching { sync.accountFeeds() }.getOrDefault(emptySet()) }
        _state.update { it.copy(accountFeeds = feeds) }
    }

    // endregion

    // region Settings

    /** The listener changed a playback setting: kept, applied, and the speed told to own.audio. */
    fun setPlaybackSettings(next: PlaybackSettings) {
        val before = settings
        applySettings(next)
        scope.launch {
            preferences.setPlayback(next)
            if (next.defaultRate != before.defaultRate) saveSpeedOnServers(next.defaultRate)
        }
    }

    /** The account's default speed on every own.audio server; the skip intervals stay on the device. */
    private suspend fun saveSpeedOnServers(rate: Float) {
        for (record in servers.value.servers.filter { it.kind == ServerKinds.OWN_AUDIO }) {
            val client = client(record.id) ?: continue
            try {
                client.updateAccountSettings(SettingsPatch(playback = SettingsPatch.Playback(speed = rate.toDouble())))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.error("podcasts", "the default speed didn't reach a server: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun applySettings(next: PlaybackSettings) {
        val before = settings
        settings = next
        if (_state.value.loaded) {
            if (next.wifiOnlyDownloads != before.wifiOnlyDownloads) scope.launch { downloads.wifiOnlyChanged() }
            if (next.keepAtMostGb != before.keepAtMostGb) scope.launch { enforceCap() }
        }
        player.setSkipIntervals(SkipIntervals(next.skipBackSecs, next.skipForwardSecs))
        player.smartRewind = SmartRewind(next.smartRewind)
        player.setKeepAhead(next.keepAheadMinutes(network.value.dataSaver) * 60_000L)
        _state.update { it.copy(settings = next) }
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

    // region Summaries and questions (own.audio's hosted service)

    /**
     * Summaries and questions for [item]: an episode with a transcript on an own.audio server
     * that has `modules.summaries`. Null everywhere else, and the section stays hidden.
     */
    fun episodeAI(item: SourcedEpisode): EpisodeAI? {
        if (item.episode.transcripts.isEmpty()) return null
        val source = ownAudioSource(item.source) ?: return null
        if (MODULE_SUMMARIES !in source.modules) return null
        return OwnAudioEpisodeAI(source.client, item.episode.id, language().take(2).ifEmpty { "en" })
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

    /**
     * Plays an episode known only by its id (the widget's `ownpodcast://play/<id>`, a row in the
     * car): from the lists, the downloads, or its source. [known] is the episode when the caller
     * already holds it. Returns whether it plays.
     */
    suspend fun playLink(id: String, known: SourcedEpisode? = null): Boolean {
        _state.first { it.loaded }
        val current = player.state.value
        if (current.item?.id == id) {
            if (!current.isPlaying) player.play()
            return true
        }
        val state = _state.value
        val item = known ?: state.inbox.firstOrNull { it.id == id } ?: state.downloads.entries[id]?.record?.item ?: run {
            val (ref, key) = SourcedEpisode.parse(id) ?: return failed(R.string.error_episode_gone)
            val source = source(ref) ?: return failed(R.string.error_episode_gone)
            val episode = try {
                source.episode(key)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return failed(R.string.error_episode_gone)
            }
            SourcedEpisode(ref, episode)
        }
        // From a widget or a car nobody is asked where to continue: the newer position wins.
        play(item, automatic = true)
        return player.state.value.item?.id == item.id
    }

    /**
     * "Continue listening": the episode in the player, else the last one played, else one under
     * way in New Episodes, else the newest that can play.
     */
    suspend fun continueListening(): Boolean {
        _state.first { it.loaded }
        if (player.state.value.item == null) restoreLastPlayed()
        if (player.state.value.item != null) {
            if (!player.state.value.isPlaying) player.play()
            return true
        }
        val state = _state.value
        val next = VoiceSearch.continueCandidate(state.inbox, state.progress, ::canPlay)
            ?: return failed(R.string.voice_nothing_to_play)
        play(next, automatic = true)
        return player.state.value.item?.id == next.id
    }

    /**
     * What the assistant or the car's search asked for: nothing or "continue" goes on listening;
     * a show's name plays its episode under way, else its newest; an episode's title plays it.
     */
    suspend fun playFromSearch(query: String?): Boolean {
        _state.first { it.loaded }
        if (VoiceSearch.isContinue(query)) return continueListening()
        val state = _state.value
        VoiceSearch.bestShow(query.orEmpty(), state.shows)?.let { show ->
            val episodes = try {
                episodes(show)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                state.inbox.filter { it.source == show.source && it.episode.showId == show.show.id }
            }
            val next = VoiceSearch.showEpisode(episodes, state.progress, ::canPlay) ?: return failed(R.string.voice_nothing_to_play)
            play(next, automatic = true)
            return player.state.value.item?.id == next.id
        }
        val pool = (state.inbox + state.downloads.entries.values.filter { it.isDone }.mapNotNull { it.record.item }).distinctBy { it.id }.filter(::canPlay)
        val episode = VoiceSearch.rankedEpisodes(query.orEmpty(), pool, state).firstOrNull() ?: return failed(R.string.voice_show_not_found)
        play(episode, automatic = true)
        return player.state.value.item?.id == episode.id
    }

    /**
     * The periodic job: the lists, feeds and servers refreshed with the download rules after
     * them, positions sent; while an episode plays only the positions, so nothing competes with it.
     */
    suspend fun backgroundRefresh(): Boolean {
        if (!_state.value.loaded) {
            load()
            return true
        }
        if (player.state.value.isPlaying) {
            sendPendingProgress()
            return true
        }
        refresh(asked = false)
        return true
    }

    private fun failed(id: Int): Boolean {
        fail(UiText(id))
        return false
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
        const val MODULE_SUMMARIES = "summaries"
        const val RESUME_QUESTION_MS = 30_000L
        const val REMOTE_POSITION_TIMEOUT_MS = 4_000L
        const val MAX_SUGGESTIONS = 20

        /** How long after a change on the device the gPodder accounts are synced. */
        const val SYNC_DELAY_MS = 5_000L
        private val SYNCING_REASONS = setOf(PodcastPlayer.Reason.PAUSE, PodcastPlayer.Reason.STOP, PodcastPlayer.Reason.FINISHED)

        val MIN_REFRESH_INTERVAL: Duration = Duration.ofMinutes(15)

        /** What the listener is told when something fails. */
        fun message(error: Throwable): UiText = when (error) {
            is SourceException.NotAFeed -> UiText(R.string.error_not_a_feed)
            is SourceException.SignInRejected -> UiText(R.string.error_signed_out)
            is SourceException.Network -> UiText(R.string.error_cant_reach)
            is SourceException.NotFound -> UiText(R.string.error_episode_gone)
            is UserFacingException -> error.text
            else -> UiText(R.string.error_something)
        }
    }
}
