package audio.own.kit.ownaudio

import kotlinx.serialization.Serializable

/** What can go wrong talking to an own.audio server. Branch on [Problem.code], never on text. */
sealed class OwnAudioException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The server answered with RFC 9457 problem details. `mfa_required` carries [mfaToken] and
     * [mfaMethods]; `feature_unavailable` names the [feature]; `invalid_body` lists [errors].
     */
    class Problem(
        val status: Int,
        val code: String,
        val detail: String? = null,
        val mfaToken: String? = null,
        val mfaMethods: List<String> = emptyList(),
        val feature: String? = null,
        val errors: List<FieldError> = emptyList(),
        /** From `Retry-After` on a `429`: wait at least this long before asking again. */
        val retryAfterSeconds: Long? = null,
    ) : OwnAudioException("$status $code${detail?.let { ": $it" } ?: ""}")

    /** A non-2xx answer that wasn't problem details (a proxy in the way, an old server). */
    class Http(val status: Int) : OwnAudioException("HTTP $status")

    /** The server couldn't be reached, or the connection broke. */
    class Network(cause: Throwable? = null) : OwnAudioException("network: ${cause?.message ?: "unreachable"}", cause)

    /** Offline, and no answer to this was kept from an earlier visit. */
    object Offline : OwnAudioException("offline") {
        private fun readResolve(): Any = Offline
    }

    /** The answer wasn't what the models expect. */
    class Decoding(cause: Throwable) : OwnAudioException("unreadable answer: ${cause.message}", cause)

    /** No tokens: the account isn't signed in on this server. */
    object SignedOut : OwnAudioException("signed out") {
        private fun readResolve(): Any = SignedOut
    }

    /**
     * The server refused the refresh token (`invalid_grant`, `invalid_credentials`). Every call
     * fails with this until the account signs in again; nothing retries.
     */
    object SignInRefused : OwnAudioException("sign-in refused: sign in again") {
        private fun readResolve(): Any = SignInRefused
    }

    /** The server has no `/api/v2`, or one older than [MIN_REVISION]. */
    class TooOld(val revision: Int?) : OwnAudioException(
        "This own.audio server is too old for this app. Update it to 1.0.4 or later.",
    )

    /**
     * A snapshot ended without its `end` line. Resume with `after = `[after] and
     * `cursor = `[cursor].
     */
    class SnapshotTruncated(val after: String?, val cursor: String?) : OwnAudioException("snapshot cut short after $after")

    companion object {
        /** The `/api/v2` revision these apps need: the one the 1.0.4 server shipped. */
        const val MIN_REVISION = 16
    }
}

/** One field's reason in an `invalid_body` problem. */
@Serializable
data class FieldError(val field: String, val code: String, val detail: String? = null)

/** The HTTP status behind an error, from problem details or a bare status. */
val Throwable.httpStatus: Int?
    get() = when (this) {
        is OwnAudioException.Problem -> status
        is OwnAudioException.Http -> status
        else -> null
    }
