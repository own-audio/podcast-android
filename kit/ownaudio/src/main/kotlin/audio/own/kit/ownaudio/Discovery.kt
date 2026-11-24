package audio.own.kit.ownaudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** `GET /.well-known/own-audio`: what a server is, before signing in. */
@Serializable
data class Discovery(
    /** The server's stable id: trust a server by it, whichever address answered. */
    val id: String? = null,
    val name: String,
    /** Never branch on it: ask [modules] and the API revision instead. */
    val edition: String = "",
    val version: String = "",
    val api: List<ApiVersion> = emptyList(),
    val auth: AuthMethods = AuthMethods(),
    /** Optional capabilities, one key each. Use [has]: a missing key is off. */
    val modules: JsonObject = JsonObject(emptyMap()),
    val addresses: List<Address> = emptyList(),
    val limits: Limits = Limits(),
    val setup: Setup = Setup(),
    /** A public demo's shared sign-in, published on purpose; absent everywhere else. */
    val demo: Demo? = null,
) {
    @Serializable
    data class ApiVersion(val version: Int, val path: String, val revision: Int, val status: String = "stable")

    @Serializable
    data class AuthMethods(
        val password: Boolean = true,
        val providers: List<String> = emptyList(),
        val grants: List<String> = emptyList(),
        val clients: AuthClients = AuthClients(),
        val deviceGrant: Boolean = false,
        val totp: Boolean = false,
        val passwordReset: Boolean = false,
        val emailVerification: Boolean = false,
        /** Sign in with Apple through the browser: `true`, or an object of its settings. Read [supportsAppleWeb]. */
        val appleWeb: JsonElement? = null,
    )

    @Serializable
    data class AuthClients(val google: ProviderClients? = null, val apple: ProviderClients? = null, val microsoft: ProviderClients? = null)

    /** The OAuth client ids a provider's sign-in is made for. */
    @Serializable
    data class ProviderClients(val web: String? = null, val desktop: String? = null)

    @Serializable
    data class Address(
        val url: String,
        /** `public`, `lan` or `vpn`. */
        val scope: String = "public",
    )

    @Serializable
    data class Limits(
        val listLimitDefault: Int = 100,
        val listLimitMax: Int = 500,
        val uploadMaxBytes: Long? = null,
        val batchMax: Int? = null,
        val idempotencyHours: Int? = null,
        val changeLogDays: Long? = null,
    )

    @Serializable
    data class Setup(val complete: Boolean = true)

    @Serializable
    data class Demo(val email: String, val password: String)

    /** The `/api/v2` this server speaks, if any. */
    val v2: ApiVersion? get() = api.firstOrNull { it.version == 2 }

    /**
     * Whether a module is on. A module with settings of its own (`auth`, `uploads`) comes as an
     * object and counts as on.
     */
    fun has(module: String): Boolean = when (val value = modules[module]) {
        null -> false
        is JsonPrimitive -> value.booleanOrNull == true
        is JsonObject -> true
        else -> false
    }

    /**
     * Whether the server runs Sign in with Apple in the browser ([appleStartUrl]), which is how
     * Android signs in with Apple. Said either as `auth.apple_web` or as the grant in
     * `auth.grants`; anything else, or nothing, is no.
     */
    val supportsAppleWeb: Boolean
        get() = when (val flag = auth.appleWeb) {
            is JsonPrimitive -> flag.booleanOrNull == true
            // An object of settings counts as on unless it says `"enabled": false`.
            is JsonObject -> (flag["enabled"] as? JsonPrimitive)?.booleanOrNull != false
            else -> false
        } || Grants.APPLE_CODE in auth.grants

    /** The yes/no modules, for a quick look; objects left out. */
    val flags: Map<String, Boolean>
        get() = modules.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.booleanOrNull?.let { key to it } }.toMap()

    /**
     * This server's `/api/v2` revision, when it is one these apps can use.
     * @throws OwnAudioException.TooOld otherwise.
     */
    fun requireV2(minRevision: Int = OwnAudioException.MIN_REVISION): Int {
        val v2 = v2 ?: throw OwnAudioException.TooOld(null)
        if (v2.revision < minRevision) throw OwnAudioException.TooOld(v2.revision)
        return v2.revision
    }
}
