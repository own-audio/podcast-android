package audio.own.core.servers

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the network is like now. [metered] is iOS's "expensive" (mobile data, a metered Wi-Fi);
 * [dataSaver] is iOS's Low Data Mode. [signature] names the network: the same interfaces in
 * another order are not a new network, so address memory and racing don't restart for nothing.
 */
data class NetworkState(
    val connected: Boolean,
    val metered: Boolean,
    val dataSaver: Boolean,
    val signature: String,
) {
    companion object {
        val Offline = NetworkState(connected = false, metered = false, dataSaver = false, signature = "none")

        fun signature(connected: Boolean, transports: Collection<String>, interfaces: Collection<String>): String =
            if (!connected) "none" else (transports.sorted() + interfaces.sorted()).joinToString(",")
    }
}

class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkState.Offline)

    /** Raw changes; callers debounce (3 s) before racing addresses. */
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var capabilities: NetworkCapabilities? = null
    private var links: LinkProperties? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            capabilities = caps
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            links = properties
            publish()
        }

        override fun onLost(network: Network) {
            capabilities = null
            links = null
            publish()
        }
    }

    fun start() {
        manager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /** Data Saver changes come as a broadcast; the app calls this from its receiver. */
    fun refreshDataSaver() = publish()

    private fun publish() {
        val caps = capabilities
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ||
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val interfaces = links?.interfaceName?.let { listOf(it) } ?: emptyList()
        _state.value = NetworkState(
            connected = connected,
            metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            dataSaver = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            signature = NetworkState.signature(connected, transports, interfaces),
        )
    }
}
