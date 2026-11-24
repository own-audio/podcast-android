package audio.own.kit.ownaudio

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl

/**
 * Sign in with Apple through the browser. Android has no Apple sign-in of its own, so the
 * server talks to Apple: the app opens [appleStartUrl] in a Custom Tab, the server sends the
 * browser back to the app's redirect address with a one-time code, and [signInWithApple]
 * trades that code for the usual tokens. PKCE ties the code to this app: only the phone that
 * made the challenge knows the verifier.
 */
object AppleWeb {
    /** The path the app's redirect address carries: `<applicationId>:/apple`. */
    const val REDIRECT_PATH = "/apple"

    /** Where the server sends the browser back to, for an app with this [applicationId]. */
    fun redirectUri(applicationId: String): String = "$applicationId:$REDIRECT_PATH"
}

/** A PKCE pair (RFC 7636, `S256`): the [verifier] stays on the phone, the [challenge] travels. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        const val METHOD = "S256"

        /** A fresh pair: 64 characters of verifier from 48 random bytes. */
        fun create(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = randomToken(random, 48)
            return Pkce(verifier, challenge(verifier))
        }

        /** base64url(SHA-256(verifier)), without padding. */
        fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** A random value for `state`: what comes back has to match it. */
        fun state(random: SecureRandom = SecureRandom()): String = randomToken(random, 24)

        private fun randomToken(random: SecureRandom, bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * The page that starts Sign in with Apple on this server, to open in the browser. The server
 * answers at [redirectUri] with `code` and [state], or with `error` (`access_denied` when the
 * person cancelled) and [state].
 */
fun OwnAudioClient.appleStartUrl(redirectUri: String, codeChallenge: String, state: String): HttpUrl = url(
    "auth/apple/start",
    Query()
        .add("redirect_uri", redirectUri)
        .add("code_challenge", codeChallenge)
        .add("code_challenge_method", Pkce.METHOD)
        .add("state", state),
)

/**
 * Trades the one-time code from the Apple redirect for tokens. Problems as with Google:
 * `mfa_required` (with its `mfaToken`), `identity_conflict`, `registration_closed`; a code
 * that expired or was used already is `invalid_grant`.
 */
suspend fun OwnAudioClient.signInWithApple(
    code: String,
    codeVerifier: String,
    device: OwnAudioClient.Device,
    inviteCode: String? = null,
): Tokens = tokenGrant(
    mapOf(
        "grant_type" to Grants.APPLE_CODE, "code" to code, "code_verifier" to codeVerifier,
        "device_name" to device.name, "device_kind" to device.kind, "invite_code" to inviteCode,
    ),
)
