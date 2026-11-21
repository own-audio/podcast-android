@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.serializer

// The household (the API calls it the family): who is in it, inviting and joining, storage and
// the prepaid credit on a server that charges.

@Serializable
data class Family(
    val id: String,
    val name: String,
    /** A signed link to its picture; it expires, read the record again for a new one. */
    val image: String? = null,
    val createdAt: String? = null,
    val me: Membership,
) {
    @Serializable
    data class Membership(
        val userId: String,
        /** `admin` or `member`. */
        val role: String,
        val canUpload: Boolean = false,
        val canGenerate: Boolean = false,
    )
}

/** Someone in the household, as others see them. */
@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    /** What the household calls them, when it differs ("Mum"). */
    val label: String? = null,
    /** `admin` or `member`. */
    val role: String = "member",
    /** Invited by an admin, not signed in yet. */
    val pending: Boolean = false,
    val active: Boolean = true,
    /** `adult`, `teen` or `child`. */
    val ageBracket: String? = null,
    val canUpload: Boolean = false,
    val canGenerate: Boolean = false,
    val joinedAt: String? = null,
    /** A signed link to their picture. */
    val image: String? = null,
) {
    /** The name to show: the household's own word for them first. */
    val name: String get() = label?.takeIf { it.isNotEmpty() } ?: displayName
}

/** An invitation: a code (and a link with it) that lets an account join the household. */
@Serializable
data class Invite(
    val id: String,
    /** `email`, `link` or `claim`. */
    val kind: String,
    val code: String,
    val role: String = "member",
    val maxUses: Int = 1,
    val useCount: Int = 0,
    val expiresAt: String,
    val createdAt: String? = null,
    val email: String? = null,
    val label: String? = null,
    val memberUserId: String? = null,
    val joinUrl: String? = null,
)

/** What a code joins, before joining (`POST /family/join/preview`). */
@Serializable
data class JoinPreview(
    val family: JoinFamily,
    val kind: String,
    val role: String,
    val memberCount: Long = 0,
    val usesLeft: Int = 0,
    val inviterName: String? = null,
    val expiresAt: Instant,
    /** For a `claim` code: the account an admin made, to take over with a password. */
    val claim: Claim? = null,
) {
    @Serializable
    data class JoinFamily(val name: String, val image: String? = null)

    @Serializable
    data class Claim(val displayName: String, val loginEmail: String)
}

/** What the household keeps on the server, in bytes. */
@Serializable
data class FamilyStorage(
    val totalBytes: Long = 0,
    val musicBytes: Long = 0,
    val audiobooksBytes: Long = 0,
    val podcastsBytes: Long = 0,
    val otherBytes: Long = 0,
    val unsizedObjects: Long = 0,
)

/**
 * The household's prepaid credit, where the server charges for storage (`modules.billing`).
 * Amounts are millionths of the currency.
 */
@Serializable
data class FamilyBilling(
    val balanceMicro: Long,
    /** The balance less what running jobs have reserved. */
    val availableMicro: Long,
    val estimatedMonthlyCostMicro: Long = 0,
    val daysRemaining: Long? = null,
    val depleted: Boolean = false,
    val pricing: Pricing,
) {
    @Serializable
    data class Pricing(val currency: String, val pricePerGbMonthMicro: Long)
}

/** An admin's changes to a member; what is left `null` stays as it is. */
@Serializable
data class MemberPatch(
    val label: String? = null,
    val role: String? = null,
    val ageBracket: String? = null,
    val canUpload: Boolean? = null,
    val canGenerate: Boolean? = null,
    val blocked: Boolean? = null,
    val statsVisibleToAdmin: Boolean? = null,
)

@Serializable
internal data class InviteBody(val kind: String? = null, val maxUses: Int? = null, val role: String? = null, val label: String? = null, val email: String? = null)

@Serializable
internal data class CodeBody(val code: String)

suspend fun OwnAudioClient.family(): Family = getJson("family")

suspend fun OwnAudioClient.members(): List<Member> = getJson("family/members")

/** Admins only. */
suspend fun OwnAudioClient.invites(): List<Invite> = getJson("family/invites")

/** A code to hand on, good for [maxUses] accounts. Admins only. */
suspend fun OwnAudioClient.createInvite(maxUses: Int = 1, kind: String = "link", role: String? = null, label: String? = null, email: String? = null): Invite =
    send("POST", "family/invites", InviteBody(kind, maxUses, role, label, email))

suspend fun OwnAudioClient.regenerateInvite(id: String): Invite =
    call("POST", "family/invites/$id/regenerate", Invite.serializer(), null, Unit.serializer())

suspend fun OwnAudioClient.deleteInvite(id: String) = sendOnly("DELETE", "family/invites/$id")

/** What the code joins, before joining. */
suspend fun OwnAudioClient.joinPreview(code: String): JoinPreview = send("POST", "family/join/preview", CodeBody(code))

/** Joins the household the code belongs to. */
suspend fun OwnAudioClient.join(code: String): Family = send("POST", "family/join", CodeBody(code))

/** Admins only. */
suspend fun OwnAudioClient.updateMember(userId: String, patch: MemberPatch) = sendOnly("PATCH", "family/members/$userId", patch)

/** Admins only: the member leaves the household. */
suspend fun OwnAudioClient.removeMember(userId: String) = sendOnly("DELETE", "family/members/$userId")

suspend fun OwnAudioClient.familyStorage(): FamilyStorage = getJson("family/storage")

/** Only where the server charges (`modules.billing`); elsewhere `404` or `501 feature_unavailable`. */
suspend fun OwnAudioClient.familyBilling(): FamilyBilling = getJson("family/billing")
