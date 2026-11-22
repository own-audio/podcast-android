package audio.own.podcastplayer.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import audio.own.core.designsystem.theme.AccentChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Small state that outlives the process: the accent, Offline Mode, the server last added, the playback settings. */
interface AppPreferences {
    val accent: Flow<AccentChoice>

    val offlineMode: Flow<Boolean>

    suspend fun setOfflineMode(on: Boolean)

    suspend fun setAccent(choice: AccentChoice)

    suspend fun activeServerId(): String?

    suspend fun setActiveServerId(id: String?)

    /** The source switch's choice ([audio.own.podcast.library.SourceRef.id]); null is All Sources. */
    val activeSource: Flow<String?>

    suspend fun setActiveSource(id: String?)

    /** How the Shows tab sorts: `title`, `newEpisodes`, `recentlyFollowed`. */
    val showsSort: Flow<String>

    suspend fun setShowsSort(sort: String)

    /** When shows set to add new episodes to Up Next last looked; null before the first look. */
    suspend fun autoQueuedAt(): Long?

    suspend fun setAutoQueuedAt(epochMs: Long)

    /** Whether the notification permission was asked for (once, at the first play). */
    suspend fun notificationsAsked(): Boolean

    suspend fun setNotificationsAsked()

    val playback: Flow<PlaybackSettings>

    suspend fun setPlayback(settings: PlaybackSettings)

    /** By server id. */
    val serverExtras: Flow<Map<String, ServerExtras>>

    /** Null forgets the server's. */
    suspend fun setServerExtras(serverId: String, extras: ServerExtras?)

    /** A random id made once per install: the gPodder device id is made from it. */
    suspend fun installId(): String
}

private val PrefsJson = Json { ignoreUnknownKeys = true }
private val ExtrasSerializer = MapSerializer(String.serializer(), ServerExtras.serializer())

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStorePreferences(context: Context) : AppPreferences {
    private val store = context.applicationContext.dataStore

    override val accent: Flow<AccentChoice> = store.data.map { AccentChoice.fromKey(it[ACCENT]) }

    override suspend fun setAccent(choice: AccentChoice) {
        store.edit { it[ACCENT] = choice.key }
    }

    override val offlineMode: Flow<Boolean> = store.data.map { it[OFFLINE_MODE] ?: false }

    override suspend fun setOfflineMode(on: Boolean) {
        store.edit { it[OFFLINE_MODE] = on }
    }

    override suspend fun activeServerId(): String? = store.data.first()[ACTIVE_SERVER]

    override suspend fun setActiveServerId(id: String?) {
        store.edit { if (id == null) it.remove(ACTIVE_SERVER) else it[ACTIVE_SERVER] = id }
    }

    override val activeSource: Flow<String?> = store.data.map { it[ACTIVE_SOURCE] }

    override suspend fun setActiveSource(id: String?) {
        store.edit { if (id == null) it.remove(ACTIVE_SOURCE) else it[ACTIVE_SOURCE] = id }
    }

    override val showsSort: Flow<String> = store.data.map { it[SHOWS_SORT] ?: "title" }

    override suspend fun setShowsSort(sort: String) {
        store.edit { it[SHOWS_SORT] = sort }
    }

    override suspend fun autoQueuedAt(): Long? = store.data.first()[AUTO_QUEUED_AT]

    override suspend fun setAutoQueuedAt(epochMs: Long) {
        store.edit { it[AUTO_QUEUED_AT] = epochMs }
    }

    override suspend fun notificationsAsked(): Boolean = store.data.first()[NOTIFICATIONS_ASKED] ?: false

    override suspend fun setNotificationsAsked() {
        store.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    override val playback: Flow<PlaybackSettings> = store.data.map { prefs ->
        prefs[PLAYBACK]?.let { runCatching { PrefsJson.decodeFromString(PlaybackSettings.serializer(), it) }.getOrNull() } ?: PlaybackSettings()
    }

    override suspend fun setPlayback(settings: PlaybackSettings) {
        store.edit { it[PLAYBACK] = PrefsJson.encodeToString(PlaybackSettings.serializer(), settings) }
    }

    override val serverExtras: Flow<Map<String, ServerExtras>> = store.data.map { prefs ->
        prefs[SERVER_EXTRAS]?.let { runCatching { PrefsJson.decodeFromString(ExtrasSerializer, it) }.getOrNull() } ?: emptyMap()
    }

    override suspend fun setServerExtras(serverId: String, extras: ServerExtras?) {
        store.edit { prefs ->
            val held = prefs[SERVER_EXTRAS]?.let { runCatching { PrefsJson.decodeFromString(ExtrasSerializer, it) }.getOrNull() }.orEmpty()
            val next = if (extras == null) held - serverId else held + (serverId to extras)
            prefs[SERVER_EXTRAS] = PrefsJson.encodeToString(ExtrasSerializer, next)
        }
    }

    override suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        var made = ""
        store.edit { prefs -> made = prefs[INSTALL_ID] ?: UUID.randomUUID().toString().also { prefs[INSTALL_ID] = it } }
        return made
    }

    private companion object {
        val PLAYBACK = stringPreferencesKey("playback")
        val SERVER_EXTRAS = stringPreferencesKey("serverExtras")
        val INSTALL_ID = stringPreferencesKey("installId")
        val ACTIVE_SOURCE = stringPreferencesKey("activeSource")
        val SHOWS_SORT = stringPreferencesKey("shows.sort")
        val AUTO_QUEUED_AT = longPreferencesKey("upNext.autoQueuedAt")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notificationsAsked")
        val ACCENT = stringPreferencesKey(AccentChoice.STORAGE_KEY)
        val ACTIVE_SERVER = stringPreferencesKey("activeServerId")
        val OFFLINE_MODE = booleanPreferencesKey("offlineMode")
    }
}

/** For tests and previews. */
class MemoryPreferences(active: String? = null) : AppPreferences {
    private val accentFlow = MutableStateFlow(AccentChoice.APP)
    private var active: String? = active

    override val accent: Flow<AccentChoice> = accentFlow
    private val offlineFlow = MutableStateFlow(false)
    override val offlineMode: Flow<Boolean> = offlineFlow

    override suspend fun setOfflineMode(on: Boolean) {
        offlineFlow.value = on
    }

    override suspend fun setAccent(choice: AccentChoice) {
        accentFlow.value = choice
    }

    override suspend fun activeServerId() = active

    override suspend fun setActiveServerId(id: String?) {
        active = id
    }

    private val sourceFlow = MutableStateFlow<String?>(null)
    override val activeSource: Flow<String?> = sourceFlow

    override suspend fun setActiveSource(id: String?) {
        sourceFlow.value = id
    }

    private val sortFlow = MutableStateFlow("title")
    override val showsSort: Flow<String> = sortFlow

    override suspend fun setShowsSort(sort: String) {
        sortFlow.value = sort
    }

    private var autoQueued: Long? = null

    override suspend fun autoQueuedAt() = autoQueued

    override suspend fun setAutoQueuedAt(epochMs: Long) {
        autoQueued = epochMs
    }

    private var asked = false

    override suspend fun notificationsAsked() = asked

    override suspend fun setNotificationsAsked() {
        asked = true
    }

    private val playbackFlow = MutableStateFlow(PlaybackSettings())
    override val playback: Flow<PlaybackSettings> = playbackFlow

    override suspend fun setPlayback(settings: PlaybackSettings) {
        playbackFlow.value = settings
    }

    private val extrasFlow = MutableStateFlow<Map<String, ServerExtras>>(emptyMap())
    override val serverExtras: Flow<Map<String, ServerExtras>> = extrasFlow

    override suspend fun setServerExtras(serverId: String, extras: ServerExtras?) {
        extrasFlow.update { if (extras == null) it - serverId else it + (serverId to extras) }
    }

    var install = "0123456789abcdef"

    override suspend fun installId() = install
}
