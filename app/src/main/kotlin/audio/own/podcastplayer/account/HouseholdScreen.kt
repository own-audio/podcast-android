package audio.own.podcastplayer.account

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.QrCode
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.kit.ownaudio.FamilyBilling
import audio.own.kit.ownaudio.FamilyStorage
import audio.own.kit.ownaudio.Invite
import audio.own.kit.ownaudio.Member
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.ui.asHeading
import audio.own.podcastplayer.app.forView
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.servers.TextDialog
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.TwoLines
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** What the family keeps, by kind, as on iOS. */
object StorageColours {
    val music = Color(0xFF6E44FF)
    val audiobooks = Color(0xFFFABB05)
    val podcasts = Color(0xFF29ABE2)
}

@Composable
fun HouseholdContent(
    state: HouseholdState,
    offline: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onInvite: () -> Unit,
    onShowInvite: (Invite?) -> Unit,
    onWithdraw: (Invite) -> Unit,
    onJoin: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var askingCode by remember { mutableStateOf(false) }
    var confirmingJoin by remember { mutableStateOf<String?>(null) }
    Page(title = stringResource(R.string.family), onBack = onBack, scrolls = false) {
        LoadStateView(state.load.forView(), onRetry = onRetry) { household ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Header(household)

                Section(header = stringResource(R.string.members), footer = stringResource(AppR.string.members_footer)) {
                    household.members.forEachIndexed { index, member ->
                        if (index > 0) RowDivider(start = 72.dp)
                        MemberRow(member, isMe = member.userId == household.meId)
                    }
                }

                state.storage?.let { StorageSection(it) }
                state.credit?.let { CreditSection(it) }

                if (household.isAdmin) {
                    Section(header = stringResource(R.string.invitations), footer = stringResource(R.string.invitations_footer)) {
                        ButtonRow(
                            stringResource(R.string.invite_someone),
                            onClick = onInvite,
                            icon = Icons.Filled.PersonAdd,
                            working = state.creatingInvite,
                            enabled = !offline,
                        )
                        state.invites.forEach { invite ->
                            RowDivider()
                            InviteRow(invite, onOpen = { onShowInvite(invite) }, onWithdraw = { onWithdraw(invite) })
                        }
                    }
                }

                Section(header = null, footer = stringResource(R.string.join_footer)) {
                    ButtonRow(stringResource(R.string.join_another_family_ellipsis), onClick = { askingCode = true }, icon = Icons.Outlined.GroupAdd, enabled = !offline)
                }
                Spacer(Modifier.height(Tokens.Spacing.xxl))
            }
        }
    }

    if (askingCode) {
        TextDialog(
            title = stringResource(R.string.join_another_family),
            label = stringResource(R.string.code),
            message = stringResource(R.string.join_enter_code),
            initial = "",
            confirm = stringResource(R.string.join),
            onConfirm = { askingCode = false; confirmingJoin = it },
            onDismiss = { askingCode = false },
        )
    }
    confirmingJoin?.let { code ->
        AlertDialog(
            onDismissRequest = { confirmingJoin = null },
            title = { Text(stringResource(R.string.join_confirm_title)) },
            text = { Text(stringResource(R.string.join_confirm_message)) },
            confirmButton = { TextButton(onClick = { confirmingJoin = null; onJoin(code) }) { Text(stringResource(R.string.join)) } },
            dismissButton = { TextButton(onClick = { confirmingJoin = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.shownInvite?.let { InviteSheet(it, onDone = { onShowInvite(null) }) }
    state.error?.let { ErrorAlert(it.text(), onDismissError) }
}

@Composable
private fun Header(household: HouseholdInfo) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(44.dp))
            household.family.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(104.dp)) }
        }
        Text(household.family.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.asHeading())
        Text(
            pluralStringResource(R.plurals.members_count, household.members.size, household.members.size),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalPalette.current.muted,
        )
    }
}

/** The family's colours, one per person, as in the own.audio logo. */
private val MemberColours = audio.own.core.designsystem.theme.Tokens.family

@Composable
private fun MemberRow(member: Member, isMe: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        // From the id, so a member keeps their colour from launch to launch.
        val colour = MemberColours[member.userId.sumOf { it.code } % MemberColours.size]
        Box(Modifier.size(44.dp).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
            val letters = member.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
            Text(letters, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            member.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp)) }
        }
        val role = when {
            member.pending -> stringResource(R.string.invited)
            member.role == "admin" -> stringResource(R.string.admin)
            else -> null
        }
        TwoLines(if (isMe) stringResource(R.string.member_you, member.name) else member.name, role, Modifier.weight(1f))
    }
}

@Composable
private fun StorageSection(storage: FamilyStorage) {
    val context = LocalContext.current
    val header = stringResource(R.string.storage) + " · " + bytes(context, storage.totalBytes)
    Section(header = header) {
        StorageBar(storage)
        StorageRow(stringResource(R.string.kind_music), storage.musicBytes, StorageColours.music)
        StorageRow(stringResource(R.string.kind_audiobooks), storage.audiobooksBytes, StorageColours.audiobooks)
        StorageRow(stringResource(R.string.kind_podcasts), storage.podcastsBytes, StorageColours.podcasts)
    }
}

@Composable
private fun StorageBar(storage: FamilyStorage) {
    val parts = listOf(storage.musicBytes to StorageColours.music, storage.audiobooksBytes to StorageColours.audiobooks, storage.podcastsBytes to StorageColours.podcasts)
        .filter { it.first > 0 }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md).height(12.dp).clip(RoundedCornerShape(6.dp))
            .background(LocalPalette.current.border),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (bytes, colour) -> Box(Modifier.weight(bytes.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(colour)) }
    }
}

@Composable
private fun StorageRow(title: String, bytes: Long, colour: Color) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Text(title, modifier = Modifier.weight(1f))
        Text(bytes(LocalContext.current, bytes), color = LocalPalette.current.muted)
    }
}

/** Display only: never a link or a word about where to top up (store rules). */
@Composable
private fun CreditSection(credit: FamilyBilling) {
    val palette = LocalPalette.current
    val money = remember(credit.pricing.currency) {
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { runCatching { currency = Currency.getInstance(credit.pricing.currency) } }
    }
    Section(
        header = stringResource(R.string.credit),
        footer = stringResource(if (credit.depleted) R.string.credit_depleted else R.string.credit_footer),
    ) {
        ValueLine(stringResource(R.string.balance), money.format(credit.balanceMicro / 1_000_000.0), if (credit.depleted) palette.error else palette.foreground)
        RowDivider()
        ValueLine(stringResource(R.string.month_at_todays_storage), money.format(credit.estimatedMonthlyCostMicro / 1_000_000.0))
        credit.daysRemaining?.let { days ->
            RowDivider()
            ValueLine(stringResource(R.string.lasts_about), pluralStringResource(R.plurals.days_count, days.toInt(), days.toInt()))
        }
    }
}

@Composable
fun ValueLine(label: String, value: String, valueColor: Color = LocalPalette.current.muted) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = valueColor)
    }
}

@Composable
private fun InviteRow(invite: Invite, onOpen: () -> Unit, onWithdraw: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(start = Tokens.Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Text(invite.code, fontFamily = FontFamily.Monospace, color = palette.foreground, modifier = Modifier.weight(1f))
            expiry(invite.expiresAt)?.let { Text(it, color = palette.muted) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = palette.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.withdraw), color = palette.error) },
                    leadingIcon = { Icon(Icons.Filled.Cancel, contentDescription = null, tint = palette.error) },
                    onClick = { menu = false; onWithdraw() },
                )
            }
        }
    }
}

private fun expiry(text: String): String? = runCatching {
    val at: Instant = OffsetDateTime.parse(text).toInstant()
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(at)
}.getOrNull()

/** An invitation to hand on: its code, a QR code of its link, and the share sheet. */
@Composable
private fun InviteSheet(invite: Invite, onDone: () -> Unit) {
    val context = LocalContext.current
    val payload = invite.joinUrl ?: invite.code
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Page(title = stringResource(R.string.invitation), onBack = onDone, closeIsCancel = true, actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } }) {
                Column(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
                    val label = stringResource(R.string.invitation_qr)
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(Tokens.Spacing.lg).semantics { contentDescription = label }) {
                        QrCode(payload, size = 220.dp)
                    }
                    Text(invite.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.invitation_explainer), color = LocalPalette.current.muted, textAlign = TextAlign.Center)
                    Button(onClick = { shareText(context, payload) }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(Tokens.Spacing.sm))
                        Text(stringResource(R.string.share_invitation))
                    }
                }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_invitation)))
}

fun bytes(context: Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)
