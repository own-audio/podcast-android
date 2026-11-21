package audio.own.podcastplayer.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    private companion object {
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
}
