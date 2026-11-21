package audio.own.core.servers

import java.util.concurrent.atomic.AtomicBoolean

/** "On mobile data": the one visible streaming choice. Feeds have no Saver; Podcasts offers Stream / Downloaded Only. */
enum class MobileData { ORIGINAL, SAVER, DOWNLOADED_ONLY }

/**
 * Whether only downloads play. Read from any thread by every server client: offline means no
 * server request at all — clients answer from saved answers or fail with "offline".
 */
class OfflineSwitch {
    private val offline = AtomicBoolean(false)
    private val byChoice = AtomicBoolean(false)

    val isOffline: Boolean get() = offline.get()

    /** Offline because the listener chose it (the banner then offers to turn it off). */
    val isOfflineByChoice: Boolean get() = byChoice.get()

    fun update(offlineMode: Boolean, network: NetworkState, mobileData: MobileData) {
        val chosen = offlineMode || (network.connected && network.metered && mobileData == MobileData.DOWNLOADED_ONLY)
        byChoice.set(chosen)
        offline.set(chosen || !network.connected)
    }
}
