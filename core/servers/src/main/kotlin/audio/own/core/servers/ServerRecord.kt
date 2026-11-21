package audio.own.core.servers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tokens as stored, independent of the server client that uses them. */
@Serializable
data class StoredTokens(val access: String, val refresh: String?, val expiresAtEpochMs: Long?)

/**
 * How the app signs in to a server. Tokens rotate on refresh, so they are saved each time; null
 * tokens mean the server refused a refresh and the account has to sign in again. No password is
 * kept, except where the protocol signs every request with it (Subsonic, gPodder).
 */
@Serializable
sealed interface ServerAuth {
    val userName: String
    val isSignedIn: Boolean

    @Serializable @SerialName("own_audio")
    data class OwnAudio(override val userName: String, val tokens: StoredTokens?) : ServerAuth {
        override val isSignedIn get() = tokens != null
    }

    @Serializable @SerialName("audiobookshelf")
    data class Audiobookshelf(override val userName: String, val tokens: StoredTokens?) : ServerAuth {
        override val isSignedIn get() = tokens != null
    }

    @Serializable @SerialName("subsonic")
    data class Subsonic(override val userName: String, val password: String) : ServerAuth {
        override val isSignedIn get() = true
    }

    @Serializable @SerialName("jellyfin")
    data class Jellyfin(override val userName: String, val userId: String, val token: String?) : ServerAuth {
        override val isSignedIn get() = token != null
    }

    @Serializable @SerialName("gpodder")
    data class GPodder(override val userName: String, val password: String, val deviceId: String) : ServerAuth {
        override val isSignedIn get() = true
    }

    /** The same account with the given tokens, for the two kinds whose tokens rotate. */
    fun withTokens(tokens: StoredTokens?): ServerAuth = when (this) {
        is OwnAudio -> copy(tokens = tokens)
        is Audiobookshelf -> copy(tokens = tokens)
        else -> this
    }

    fun sameAccount(other: ServerAuth): Boolean = this::class == other::class && userName == other.userName
}

/** One library of a server that has several (Audiobookshelf, Jellyfin). */
@Serializable
data class ServerLibraryRef(val id: String, val name: String)

/**
 * One configured server. [kind] is the app's own name for the server kind ("own_audio",
 * "subsonic", …); [displayName] defaults to the server's name or host and can be renamed.
 */
@Serializable
data class ServerRecord(
    val id: String,
    val kind: String,
    val displayName: String,
    /** The address the server was added with. */
    val host: String,
    val auth: ServerAuth,
    /** The server's own identity where its kind has one: an address answering with another is not this server. */
    val serverIdentity: String? = null,
    /** Every other address the server is known by: named by the server, or added by hand. */
    val addresses: List<ServerAddress> = emptyList(),
    val connection: ConnectionMode = ConnectionMode.Automatic,
    val libraryId: String? = null,
    val libraries: List<ServerLibraryRef>? = null,
    val userId: String? = null,
    val addedAtEpochMs: Long = 0,
) {
    /** Every address to try: the one it was added with first, each once, the unsafe ones out. */
    val candidateAddresses: List<ServerAddress>
        get() {
            val seen = HashSet<String>()
            return (listOf(ServerAddress.of(host, AddressOrigin.ADDED)) + addresses)
                .filter { it.isSafeToUse && seen.add(ServerAddress.normalized(it.url)) }
        }

    /** Adds addresses the server named, keeping the ones already known. */
    fun learning(named: List<ServerAddress>): ServerRecord {
        val known = (listOf(host) + addresses.map { it.url }).map(ServerAddress::normalized).toSet()
        return copy(addresses = addresses + named.filter { ServerAddress.normalized(it.url) !in known }.distinctBy { ServerAddress.normalized(it.url) })
    }

    /** Server records keep the ids of what they hold; this is the prefix of those ids. */
    fun compositeId(itemId: String): String = CompositeId.make(id, itemId)
}

/**
 * Every item the app holds is named `"<server id>|<item id>"`, so one queue can mix servers and a
 * removed server's downloads, positions and covers can be found and deleted.
 */
object CompositeId {
    fun make(serverId: String, itemId: String) = "$serverId|$itemId"

    /** (server id, item id), or null for a bare id. */
    fun split(composite: String): Pair<String, String>? {
        val bar = composite.indexOf('|')
        return if (bar <= 0) null else composite.substring(0, bar) to composite.substring(bar + 1)
    }
}
