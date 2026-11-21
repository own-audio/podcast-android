package audio.own.kit.audiobookshelf

/** What can go wrong talking to an Audiobookshelf server. */
sealed class AudiobookshelfError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** The server refused the sign-in (wrong name or password, or a locked account). */
    data object InvalidCredentials : AudiobookshelfError("invalid credentials")

    /** Signed out: the refresh token was refused, so the account has to sign in again. */
    data object SignedOut : AudiobookshelfError("signed out")

    /** The server answered with an HTTP error; [serverMessage] is its text or `{error}` body. */
    data class Http(val status: Int, val serverMessage: String?) : AudiobookshelfError("HTTP $status")

    /**
     * Too many requests, still refused after the backoff (or the server asked for a wait longer
     * than a request should block). [retryAfterMillis] is the wait it asked for, when it said.
     */
    data class RateLimited(val retryAfterMillis: Long?) : AudiobookshelfError("rate limited")

    data object Decoding : AudiobookshelfError("unreadable answer")

    data class Network(val reason: String? = null) : AudiobookshelfError(reason ?: "network")

    /** Offline, and this wasn't saved from an earlier visit. */
    data object Offline : AudiobookshelfError("offline")
}

/**
 * Where answers to reads are kept, so a screen seen once opens again with no network. The key
 * names the server, the path and the query, never a token.
 */
interface AnswerCache {
    fun data(key: String): ByteArray?
    fun store(data: ByteArray, key: String)
}

/**
 * Where the tokens live between launches; the app implements it on its encrypted store.
 *
 * [save] is called whenever a refresh rotates the tokens, and it completes before the new
 * tokens are used for any request: a refresh token is single-use, so a rotation that is used
 * but never written would sign the device out at the next launch. `null` means signed out.
 * Nothing else writes here, so stale tokens are never written back.
 */
fun interface TokenStore {
    suspend fun save(tokens: Tokens?)
}

/**
 * Where failed requests are reported, and why: the errors thrown say only "network" or
 * "decoding". Messages name the method and the path alone, never the query, which can carry a
 * token.
 */
fun interface NetworkLog {
    fun error(message: String)

    companion object {
        val None = NetworkLog { }
    }
}
