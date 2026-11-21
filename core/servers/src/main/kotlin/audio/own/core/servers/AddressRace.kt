package audio.own.core.servers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** What an address answered when asked, cheaply and without signing in. */
data class AddressCheck(
    val url: String,
    /** The server's identity, where its kind has one. */
    val serverIdentity: String?,
    /** More addresses the server named while answering. */
    val addresses: List<ServerAddress> = emptyList(),
)

object AddressRace {
    const val TIMEOUT_MS = 4_000L

    /**
     * Asks every candidate at once and returns the first that answers as the same server
     * ([expectedIdentity], where the kind has one), or null. Another server at the same address —
     * the same private IP on another network — must never be taken for this one.
     *
     * A server reinstalled from scratch answers with a new identity. The address it was added with
     * is still trusted then if it is a public https name, whose certificate already says whose
     * server it is; the caller adopts the new identity. Home and VPN addresses never get this.
     */
    suspend fun race(
        record: ServerRecord,
        candidates: List<String>,
        expectedIdentity: String?,
        timeoutMs: Long = TIMEOUT_MS,
        check: suspend (String) -> AddressCheck?,
    ): AddressCheck? {
        val same = fastest(candidates, timeoutMs) { url ->
            val answer = runCatching { check(url) }.getOrNull() ?: return@fastest null
            if (expectedIdentity != null && answer.serverIdentity != expectedIdentity) null else answer
        }
        if (same != null) return same
        val host = record.host
        if (expectedIdentity == null || !host.startsWith("https://") || AddressScope.infer(host) != AddressScope.PUBLIC) return null
        if (candidates.none { ServerAddress.normalized(it) == ServerAddress.normalized(host) }) return null
        return withTimeoutOrNull(timeoutMs) { runCatching { check(host) }.getOrNull() }?.takeIf { it.serverIdentity != null }
    }

    private suspend fun <T : Any> fastest(items: List<String>, timeoutMs: Long, probe: suspend (String) -> T?): T? = coroutineScope {
        if (items.isEmpty()) return@coroutineScope null
        val pending = items.map { url -> async { withTimeoutOrNull(timeoutMs) { probe(url) } } }.toMutableList()
        var winner: T? = null
        while (pending.isNotEmpty() && winner == null) {
            val (done, value) = select {
                pending.forEach { deferred -> deferred.onAwait { deferred to it } }
            }
            pending.remove(done)
            winner = value
        }
        pending.forEach { it.cancel() }
        winner
    }
}

/**
 * The address that won last time on each network, used at once next time and then checked again.
 * Keyed by server and by the network's signature.
 */
interface AddressMemory {
    fun get(serverId: String, network: String): String?

    fun set(serverId: String, network: String, url: String?)
}

class InMemoryAddressMemory : AddressMemory {
    private val map = HashMap<String, String>()

    override fun get(serverId: String, network: String) = synchronized(map) { map["$serverId.$network"] }

    override fun set(serverId: String, network: String, url: String?) {
        synchronized(map) { if (url == null) map.remove("$serverId.$network") else map["$serverId.$network"] = url }
    }
}

class PreferencesAddressMemory(private val prefs: android.content.SharedPreferences) : AddressMemory {
    override fun get(serverId: String, network: String): String? = prefs.getString(key(serverId, network), null)

    override fun set(serverId: String, network: String, url: String?) {
        prefs.edit().apply { if (url == null) remove(key(serverId, network)) else putString(key(serverId, network), url) }.apply()
    }

    private fun key(serverId: String, network: String) = "address.$serverId.$network"
}
