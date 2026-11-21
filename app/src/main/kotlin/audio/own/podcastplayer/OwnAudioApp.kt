package audio.own.podcastplayer

import android.app.Application
import audio.own.core.servers.MobileData
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OwnAudioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch { container.servers.load() }
        // The mobile-data choice comes with the settings (P9); for now Offline Mode and the network decide.
        container.scope.launch {
            combine(container.preferences.offlineMode, container.network.state) { mode, network -> mode to network }
                .collect { (mode, network) -> container.offline.update(mode, network, MobileData.ORIGINAL) }
        }
    }
}
