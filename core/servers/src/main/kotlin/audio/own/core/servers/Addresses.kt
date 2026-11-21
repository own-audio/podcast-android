package audio.own.core.servers

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where an address is reachable from. Decides what is worth trying where, and that a password or
 * token may travel over plain http only to the home network.
 */
@Serializable
enum class AddressScope {
    @SerialName("lan") LAN,
    @SerialName("vpn") VPN,
    @SerialName("public") PUBLIC,
    ;

    companion object {
        /**
         * LAN for private, link-local and `.local` hosts; VPN for the carrier-grade NAT range
         * Tailscale uses and `*.ts.net`; PUBLIC otherwise — the rule own.audio's server uses too.
         */
        fun infer(url: String): AddressScope {
            var host = hostOf(url)?.lowercase() ?: return PUBLIC
            if (host.startsWith("[")) host = host.removePrefix("[").removeSuffix("]")
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return LAN
            if (host.endsWith(".ts.net")) return VPN
            val parts = host.split('.')
            if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
                val a = parts[0].toInt()
                val b = parts[1].toInt()
                return when {
                    a == 100 && b in 64..127 -> VPN
                    a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || a == 127 -> LAN
                    else -> PUBLIC
                }
            }
            if (':' in host) {
                // fc00::/7 unique local, fe80::/10 link-local, ::1 loopback.
                if (host == "::1" || listOf("fc", "fd", "fe8", "fe9", "fea", "feb").any(host::startsWith)) return LAN
            }
            return PUBLIC
        }

        fun fromWire(value: String?): AddressScope? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

internal fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

@Serializable
enum class AddressOrigin {
    /** The address the server was added with. */
    @SerialName("added") ADDED,

    /** Named by the server. */
    @SerialName("server") SERVER,

    /** Typed by the listener. */
    @SerialName("user") USER,
}

@Serializable
data class ServerAddress(val url: String, val scope: AddressScope, val origin: AddressOrigin) {
    /**
     * Credentials go over plain http only to an address on the home network, or one the listener
     * typed or added the server with themselves — never to an http address the server advertised
     * for the internet.
     */
    val isSafeToUse: Boolean get() = url.startsWith("https://", ignoreCase = true) || scope == AddressScope.LAN || origin != AddressOrigin.SERVER

    companion object {
        fun of(url: String, origin: AddressOrigin, scope: AddressScope? = null) = ServerAddress(url, scope ?: AddressScope.infer(url), origin)

        /** The same address however it was written: no trailing slashes. */
        fun normalized(url: String): String = url.trimEnd('/')
    }
}

/** How the app picks an address. */
@Serializable
sealed interface ConnectionMode {
    /** Try them all after each network change and keep the fastest that is the same server. */
    @Serializable @SerialName("automatic") data object Automatic : ConnectionMode

    /** Always this one. */
    @Serializable @SerialName("fixed") data class Fixed(val url: String) : ConnectionMode
}

/** What a typed address becomes before anything is asked of it. */
object TypedAddress {
    /**
     * own.audio's web addresses lead to its API: people type the address they know from the
     * browser. `app-<x>.own.audio` is a console address, its API is `api-<x>.own.audio`.
     */
    fun hostedApi(typed: String): String? {
        val host = hostOf(withScheme(typed, "https"))?.lowercase() ?: return null
        return when {
            host == "own.audio" || host == "www.own.audio" || host == "app.own.audio" -> "https://api.own.audio"
            host.startsWith("app-") && host.endsWith(".own.audio") -> "https://api-" + host.removePrefix("app-")
            else -> null
        }
    }

    /**
     * The addresses to try, in order. Without a scheme https comes first: defaulting to http met
     * own.audio's http→https redirect, which replays a POST as a GET (a 405 at sign-in) after the
     * password had already gone out unencrypted.
     */
    fun candidates(typed: String): List<String> {
        val trimmed = typed.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        hostedApi(trimmed)?.let { return listOf(it) }
        if (trimmed.contains("://")) return listOf(trimmed)
        return listOf(withScheme(trimmed, "https"), withScheme(trimmed, "http"))
    }

    private fun withScheme(text: String, scheme: String) = if (text.contains("://")) text else "$scheme://$text"
}
