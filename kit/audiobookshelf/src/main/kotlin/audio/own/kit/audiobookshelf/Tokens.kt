package audio.own.kit.audiobookshelf

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** The tokens of one signed-in account. */
@Serializable
data class Tokens(
    val accessToken: String,
    /** `null` on a server older than 2.26: its one token never expires. */
    val refreshToken: String?,
    /**
     * When the access token stops working, in milliseconds since 1970; refreshed a little
     * before. [NEVER] for a token with no expiry.
     */
    val expiresAtMillis: Long,
) {
    // Tokens end up in log lines by accident through toString(); never print them.
    override fun toString(): String =
        "Tokens(refreshToken=${if (refreshToken == null) "none" else "present"}, expiresAtMillis=$expiresAtMillis)"

    companion object {
        const val NEVER: Long = Long.MAX_VALUE

        /**
         * The `exp` claim of a JWT access token, in milliseconds since 1970, read without
         * checking the signature (the server does that); `null` when the token is not a JWT
         * with one.
         */
        fun expiryOfJwt(token: String): Long? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            return runCatching {
                val payload = Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
                val claims = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return null
                val exp = claims["exp"]?.jsonPrimitive?.doubleOrNull ?: return null
                (exp * 1000).toLong()
            }.getOrNull()
        }
    }
}

/**
 * The access and refresh tokens of one signed-in account, refreshed one at a time. Keep one
 * per account for as long as the app runs, shared by every client built for that server.
 *
 * Since Audiobookshelf 2.26 the access token is a JWT that lives an hour and the refresh token
 * rotates on every use; a refresh token used twice signs the device out. So concurrent requests
 * that find the access token expired must share one refresh rather than each start their own:
 * a mutex makes them wait for it, and whoever arrives after it finished takes its result. A
 * server older than 2.26 hands out one token that never expires and no refresh token; those
 * tokens are kept as they are.
 */
class TokenSession(
    initial: Tokens?,
    private val store: TokenStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var tokens: Tokens? = initial

    val current: Tokens? get() = tokens

    /** An access token that is good now, refreshing first when it is about to expire. */
    suspend fun accessToken(refresh: suspend (String) -> Tokens): String {
        val now = tokens ?: throw AudiobookshelfError.SignedOut
        if (!needsRefresh(now)) return now.accessToken
        return refresh(stale = now.accessToken, refresh = refresh).accessToken
    }

    /**
     * Refreshes now, unless the access token in use is no longer [stale] (another caller
     * refreshed while this one waited), in which case the newer tokens are returned. With
     * `stale = null` it always refreshes.
     */
    suspend fun refresh(stale: String?, refresh: suspend (String) -> Tokens): Tokens = mutex.withLock {
        val now = tokens ?: throw AudiobookshelfError.SignedOut
        if (stale != null && now.accessToken != stale) return@withLock now
        val refreshToken = now.refreshToken ?: throw AudiobookshelfError.SignedOut
        // The server rotates the refresh token as soon as it answers. If the caller were
        // cancelled between that answer and the save, the only valid refresh token would be
        // lost, so the exchange and the save run to the end regardless.
        withContext(NonCancellable) {
            val fresh = try {
                refresh(refreshToken)
            } catch (e: AudiobookshelfError.Http) {
                if (e.status == 401 || e.status == 403) {
                    tokens = null
                    store?.save(null)
                    throw AudiobookshelfError.SignedOut
                }
                throw e
            }
            try {
                store?.save(fresh)
            } finally {
                // The old refresh token is spent either way; keep the new pair in memory even
                // when writing it failed.
                tokens = fresh
            }
            fresh
        }
    }

    suspend fun signedOut() {
        mutex.withLock {
            tokens = null
            store?.save(null)
        }
    }

    private fun needsRefresh(t: Tokens): Boolean =
        t.refreshToken != null && t.expiresAtMillis - clock() <= MARGIN_MILLIS

    private companion object {
        /** Refresh this long before expiry, so a request is not sent with a token that dies on the way. */
        const val MARGIN_MILLIS = 60_000L
    }
}
