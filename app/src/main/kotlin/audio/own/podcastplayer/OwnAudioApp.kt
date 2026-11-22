package audio.own.podcastplayer

import android.app.Application
import androidx.work.Configuration
import audio.own.core.servers.MobileData
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OwnAudioApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch { container.servers.load() }
        container.loadServerIcons()
        // Downloaded Only on mobile data stops streams, not the lists (as on iOS): the model
        // checks it at Play, so only Offline Mode and the network make the app offline.
        container.mainScope.launch {
            var before: Boolean? = null
            combine(container.preferences.offlineMode, container.network.state) { mode, network -> mode to network }
                .collect { (mode, network) ->
                    container.offline.update(mode, network, MobileData.ORIGINAL)
                    if (before != null && before != mode) container.podcasts.offlineModeChanged()
                    before = mode
                }
        }
        // The library loads at launch, not with the first screen: the media service and the
        // last episode's resumption need it too.
        container.mainScope.launch { container.podcasts.load() }
        // Once the sources and settings are read: what the index doesn't know goes, and what
        // waited (killed mid-download) starts again.
        container.scope.launch {
            container.podcasts.state.first { it.loaded }
            container.downloads.start()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
}
