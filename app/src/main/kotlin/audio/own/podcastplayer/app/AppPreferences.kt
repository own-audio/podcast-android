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

/** Small state that outlives the process: the accent, Offline Mode, the server last added. */
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
}

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

    private companion object {
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
}
