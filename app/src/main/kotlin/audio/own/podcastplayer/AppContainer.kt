package audio.own.podcastplayer

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import audio.own.core.servers.DiskAnswerCache
import audio.own.core.servers.KeystoreSecretBox
import audio.own.core.servers.NetworkMonitor
import audio.own.core.servers.OfflineSwitch
import audio.own.core.servers.PreferencesAddressMemory
import audio.own.core.servers.ServerStore
import audio.own.kit.ownaudio.AnswerCache
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSessions
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.AppPreferences
import audio.own.podcastplayer.app.DataStorePreferences
import audio.own.podcastplayer.app.LoggingInterceptor
import audio.own.podcastplayer.servers.ServerDetector
import audio.own.podcastplayer.servers.ServersModel
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import android.util.Xml
import androidx.media3.common.util.UnstableApi
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedParser
import audio.own.kit.feed.XmlParserFactory
import audio.own.player.core.JsonFile
import audio.own.player.core.ListeningCache
import audio.own.player.core.PlaybackDataSources
import audio.own.player.core.PlaybackLinkRequest
import audio.own.player.core.PlaybackLinkResolver
import audio.own.player.core.StreamHttpClient
import audio.own.podcast.library.FeedLibrary
import audio.own.podcast.library.FeedLoader
import audio.own.podcast.library.LastPlayed
import audio.own.podcast.library.PendingProgress
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.UpNext
import audio.own.podcast.library.ValueStore
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomFeedLibraryStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcastplayer.playback.Artwork
import audio.own.podcastplayer.playback.EpisodePlayer
import audio.own.podcastplayer.podcasts.PodcastModel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import audio.own.core.servers.ServerLibraryRef
import audio.own.kit.audiobookshelf.DeviceInfo
import audio.own.podcastplayer.podcasts.ServerAccounts
import audio.own.podcastplayer.servers.ServerIcons
import kotlinx.serialization.builtins.ListSerializer

/**
 * Hand-wired dependencies. Android Auto, the assistant and the widget can bind to the media
 * service without any screen having started, so the playback dependencies will be created here,
 * eagerly, rather than from the UI.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log = AppLog(File(app.filesDir, "logs"))

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** The hosted service's edge refuses requests without a proper User-Agent. */
    val userAgent = "own.audio Podcasts/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor(log))
        .build()

    val preferences: AppPreferences = DataStorePreferences(app)
    val network = NetworkMonitor(app).also { it.start() }
    val offline = OfflineSwitch()
    val answers = DiskAnswerCache(File(app.cacheDir, "server-responses"))
    val tokenSessions = TokenSessions()
    val serverStore = ServerStore(File(app.filesDir, "servers.bin"), KeystoreSecretBox("own.podcast.servers")) { log.error("servers", it) }

    /** What the session list on the server shows for this device. */
    val device = OwnAudioClient.Device(
        name = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
        kind = "android",
    )

    val servers = ServersModel(
        store = serverStore,
        sessions = tokenSessions,
        http = http,
        userAgent = userAgent,
        device = device,
        preferences = preferences,
        detector = ServerDetector(http, userAgent, log),
        addressMemory = PreferencesAddressMemory(app.getSharedPreferences("addresses", Context.MODE_PRIVATE)),
        network = network.state,
        cache = object : AnswerCache {
            override fun get(key: String) = answers.get(key)
            override fun put(key: String, bytes: ByteArray) = answers.put(key, bytes)
        },
        clearCache = { answers.clear() },
        isOffline = { offline.isOffline },
        acceptLanguage = { appLocale().toLanguageTag() },
        log = log,
        scope = scope,
    )

    /** Where every part of the podcast library is kept. */
    val database: PodcastDatabase = PodcastDatabase.open(app)

    /** Publishers' feeds and chapter documents, on the plain client: no token goes to a publisher. */
    val feeds = FeedFetcher(http, FeedParser(XmlParserFactory { Xml.newPullParser() }), userAgent)

    val upNext = UpNext(RoomValueStore(database, "up-next", ListSerializer(UpNext.Entry.serializer())))
    private val pendingProgress = PendingProgress(RoomValueStore(database, "pending-progress", ListSerializer(PendingProgress.Entry.serializer())))

    /** The shows followed on this device by their feeds. */
    val phone = FeedLibrary(
        store = RoomFeedLibraryStore(database),
        loader = FeedLoader { url, validators -> feeds.fetch(url, validators) },
        // A refresh keeps an episode the feed dropped while it waits in Up Next.
        queued = { upNext.all().mapNotNull { SourcedEpisode.parse(it.id) }.filter { it.first == SourceRef.Phone }.map { it.second } },
    )

    /** The main thread: the player and the model that drives it live there. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // One SimpleCache per folder per process; a second (a test's fresh Application) would throw,
    // and the app then streams without one.
    private val listeningCache = runCatching { ListeningCache.open(app) }.getOrNull()

    val playbackSources = PlaybackDataSources(
        context = app,
        resolver = PlaybackLinkResolver { request -> podcasts.linkResolver.resolve(request) },
        httpClient = StreamHttpClient.from(http),
        cache = listeningCache,
        userAgent = userAgent,
    )

    val player = EpisodePlayer(app, playbackSources, mainScope)

    val artwork = Artwork(app)

    val navStash = audio.own.podcastplayer.ui.NavStash()

    private val lastPlayedFile = lastItemFile(File(app.filesDir, "last-played.json"))

    val podcasts: PodcastModel by lazy {
        PodcastModel(
            db = database,
            phone = phone,
            upNext = upNext,
            pending = pendingProgress,
            lastPlayed = lastPlayedFile,
            player = player,
            servers = servers.state,
            client = { id -> servers.client(id) },
            preferences = preferences,
            network = network.state,
            offlineMode = { offline.isOfflineByChoice },
            http = http,
            feeds = feeds,
            isFullyCached = { id -> playbackSources.isFullyCached(PlaybackLinkRequest(id)) },
            strings = object : PodcastModel.Strings {
                override val thisDevice: String get() = app.getString(R.string.this_device)

                override fun chapter(number: Int): String = app.getString(R.string.chapter_number, number)
            },
            log = log,
            scope = mainScope,
            accounts = object : ServerAccounts {
                override fun audiobookshelf(serverId: String) = servers.audiobookshelf(serverId)

                override fun gpodder(serverId: String) = servers.gpodder(serverId)

                override fun address(serverId: String) = servers.address(serverId)

                override suspend fun gpodderDevice() = servers.gpodderDevice()

                override suspend fun noteSynced(serverId: String, atEpochMs: Long) = servers.noteSynced(serverId, atEpochMs)

                override suspend fun updateLibraries(serverId: String, libraries: List<ServerLibraryRef>) = servers.updateLibraries(serverId, libraries)
            },
            forgetLink = { id -> playbackSources.links.forget(PlaybackLinkRequest(id)) },
            absDevice = DeviceInfo(
                deviceId = "own-audio-podcasts-android",
                clientName = "own.audio Podcasts",
                clientVersion = versionName,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkVersion = Build.VERSION.SDK_INT.toString(),
            ),
        )
    }

    /** Audiobookshelf's and gPodder servers' own icons, fetched from them and kept on the device. */
    val serverIcons = ServerIcons(File(app.filesDir, "server-icons"), http, userAgent, log)

    /** Fetches the icons once the servers are known, and drops a removed server's. */
    fun loadServerIcons() {
        scope.launch {
            var known = emptySet<String>()
            servers.state.map { it.servers to it.extras }.distinctUntilChanged().collect { (records, extras) ->
                if (!servers.state.value.loaded) return@collect
                (known - records.map { it.id }.toSet()).forEach(serverIcons::forget)
                known = records.map { it.id }.toSet()
                serverIcons.load(records, extras, { record -> servers.address(record.id) }, online = network.state.value.connected && !offline.isOffline)
            }
        }
    }

    /** The header of a shared log: which app, which system. */
    val logHeader: String get() = "own.podcast $versionName ($versionCode), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}

/** `last-played.json`, written whole and atomically: a kill mid-write leaves the previous one. */
private fun lastItemFile(file: File): ValueStore<LastPlayed> = object : ValueStore<LastPlayed> {
    private val json = JsonFile(file, LastPlayed.serializer())

    override suspend fun load(): LastPlayed? = withContext(Dispatchers.IO) { json.read() }

    override suspend fun save(value: LastPlayed?) = withContext(Dispatchers.IO) {
        if (value == null) json.delete() else json.write(value)
    }
}
