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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * Hand-wired dependencies. Android Auto, the assistant and the widget can bind to the media
 * service without any screen having started, so the playback dependencies will be created here,
 * eagerly, rather than from the UI.
 */
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

    /**
     * Whether any show is followed on this device or a source. The welcome screen stays until
     * there is a show or a server. TODO(P2): from the podcast library.
     */
    val hasShows: StateFlow<Boolean> = MutableStateFlow(false)

    /** The header of a shared log: which app, which system. */
    val logHeader: String get() = "own.podcast $versionName ($versionCode), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}
