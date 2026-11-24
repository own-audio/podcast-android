@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import okhttp3.RequestBody.Companion.toRequestBody

/** The signed-in account (`GET /me`). */
@Serializable
data class Me(
    val id: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean = true,
    val image: String? = null,
    /** `admin` or `user`: the whole server, not the household. */
    val instanceRole: String = "user",
    val family: MeFamily,
    val discoveryLanguages: List<String> = emptyList(),
    val recommendationsEnabled: Boolean = true,
    val twoFactor: TwoFactor = TwoFactor(),
) {
    @Serializable
    data class MeFamily(
        val id: String,
        /** `admin` or `member`. */
        val role: String,
        val canUpload: Boolean = false,
        val canGenerate: Boolean = false,
    )

    @Serializable
    data class TwoFactor(val enabled: Boolean = false, val recoveryCodesLeft: Long = 0)
}

/** `PATCH /me`: what is left `null` stays as it is. */
@Serializable
data class MePatch(
    val displayName: String? = null,
    val discoveryLanguages: List<String>? = null,
    val recommendationsEnabled: Boolean? = null,
)

@Serializable
internal data class RegisterBody(val email: String, val password: String, val displayName: String, val inviteCode: String? = null)

@Serializable
internal data class PasswordBody(val password: String? = null)

/** Grant types of `POST /auth/token`. */
object Grants {
    const val PASSWORD = "password"
    const val REFRESH = "refresh_token"
    const val MFA_OTP = "urn:own-audio:params:oauth:grant-type:mfa-otp"
    const val JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer"

    /** The one-time code of the browser's Sign in with Apple ([signInWithApple]). */
    const val APPLE_CODE = "urn:own-audio:params:oauth:grant-type:apple-code"
}

// region Before signing in

/** What the server is, or an error when it is not an own.audio server. Check [Discovery.requireV2]. */
suspend fun OwnAudioClient.discover(): Discovery =
    public(base(rootUrl(".well-known/own-audio")).get().build(), Discovery.serializer())

/**
 * Creates an account with a password; sign in afterwards. Refusals come as problems with
 * `invalid_email`, `weak_password`, `invalid_display_name`, `registration_closed` or `email_taken`.
 */
suspend fun OwnAudioClient.register(email: String, password: String, displayName: String, inviteCode: String? = null): Me {
    val body = CanonicalJson.encode(RegisterBody.serializer(), RegisterBody(email, password, displayName, inviteCode))
    return public(base(url("auth/register")).post(body.toRequestBody(OwnAudioClient.JSON_TYPE)).build(), Me.serializer())
}

/**
 * Signs in with an email and password. A problem `mfa_required` carries the `mfaToken` for
 * [finishTwoFactor]; `invalid_grant` is a wrong email or password.
 */
suspend fun OwnAudioClient.signIn(email: String, password: String, device: OwnAudioClient.Device): Tokens = tokenGrant(
    mapOf(
        "grant_type" to Grants.PASSWORD, "username" to email, "password" to password,
        "device_name" to device.name, "device_kind" to device.kind,
    ),
)

/** Finishes a sign-in that answered `mfa_required`, with the authenticator's code or a recovery code. */
suspend fun OwnAudioClient.finishTwoFactor(mfaToken: String, code: String): Tokens =
    tokenGrant(mapOf("grant_type" to Grants.MFA_OTP, "mfa_token" to mfaToken, "otp" to code))

/**
 * A provider's id token (`google`): the server checks it and signs the account in, making one
 * when [inviteCode] lets it. `identity_conflict` (409): an account with this email exists with
 * a password; sign in with it.
 */
suspend fun OwnAudioClient.signIn(
    provider: String,
    idToken: String,
    device: OwnAudioClient.Device,
    inviteCode: String? = null,
    fullName: String? = null,
): Tokens = tokenGrant(
    mapOf(
        "grant_type" to Grants.JWT_BEARER, "provider" to provider, "assertion" to idToken,
        "device_name" to device.name, "device_kind" to device.kind, "invite_code" to inviteCode, "full_name" to fullName,
    ),
)

internal suspend fun OwnAudioClient.tokenGrant(fields: Map<String, String?>): Tokens {
    val request = base(url("auth/token"))
        .post(OwnAudioClient.formEncode(fields).toRequestBody(OwnAudioClient.FORM_TYPE))
        .build()
    return public(request, TokenResponse.serializer()).tokens(clock.instant())
}

// endregion

// region The account

suspend fun OwnAudioClient.me(): Me = getJson("me")

suspend fun OwnAudioClient.updateMe(patch: MePatch): Me = send("PATCH", "me", patch)

/**
 * Ends this device's session on the server (`POST /auth/revoke`); the tokens are forgotten
 * either way. Call it when a server is removed.
 */
suspend fun OwnAudioClient.signOut() {
    val session = tokens ?: return
    session.current?.refreshToken?.let { refresh ->
        runCatching {
            execute(
                base(url("auth/revoke"))
                    .post(OwnAudioClient.formEncode(mapOf("token" to refresh, "token_type_hint" to "refresh_token")).toRequestBody(OwnAudioClient.FORM_TYPE))
                    .build(),
            )
        }
    }
    session.signedOut()
}

/**
 * Deletes the account and everything it owns, at once. An account with a password confirms
 * with it; one that signs in only with a provider sends none. Problems: `password_required`,
 * `invalid_credentials`, `last_admin`, `demo_read_only`. Signed out afterwards.
 */
suspend fun OwnAudioClient.deleteAccount(password: String? = null) {
    sendOnly("DELETE", "me", PasswordBody(password))
    tokens?.signedOut()
}

// endregion

@Serializable
data class Session(
    val id: String,
    val deviceKind: String,
    val deviceName: String? = null,
    val signedInAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant,
    /** The device making the request. */
    val current: Boolean = false,
)

/** The devices signed in to this account, this one marked. */
suspend fun OwnAudioClient.sessions(): List<Session> = getJson("me/sessions")

/** Signs one device out, at once. */
suspend fun OwnAudioClient.revokeSession(id: String) = sendOnly("DELETE", "me/sessions/$id")

/** Signs out everywhere, this device too: sign in again afterwards. */
suspend fun OwnAudioClient.revokeAllSessions() {
    sendOnly("DELETE", "me/sessions")
    tokens?.signedOut()
}
