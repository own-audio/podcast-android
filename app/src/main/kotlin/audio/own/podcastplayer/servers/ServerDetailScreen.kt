package audio.own.podcastplayer.servers

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.AddressScope
import audio.own.core.servers.ConnectionMode
import audio.own.core.servers.ServerAddress
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.strings.R
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.settings.RemoveServerDialog
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.TwoLines
import java.util.Date
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Everything the server page shows, so it can be drawn without a live server. */
data class ServerPageUi(
    val server: ServerRecord,
    val connection: ConnectionState?,
    val check: ConnectionCheck?,
    val checking: Boolean,
    val refused: Boolean,
    val detail: ServerDetailState,
)

/** Orange for "not now", as iOS's status lines; the app's accent read as an error beside them. */
private val Caution = Color(0xFFFF9500)

@Composable
fun ServerDetailContent(
    ui: ServerPageUi,
    onBack: () -> Unit,
    onCheckNow: () -> Unit,
    onSignInAgain: () -> Unit,
    onDeleteAccount: (String?) -> Unit,
    onFamily: () -> Unit,
    onDevices: () -> Unit,
    onListening: () -> Unit,
    onRename: (String) -> Unit,
    onConnection: (ConnectionMode) -> Unit,
    onAddAddress: (String) -> Unit,
    onRemoveAddress: (String) -> Unit,
    onRemove: () -> Unit,
    onDismissError: () -> Unit,
) {
    val server = ui.server
    val palette = LocalPalette.current
    val isOwnAudio = server.kind == ServerKinds.OWN_AUDIO && server.auth is ServerAuth.OwnAudio
    var renaming by remember { mutableStateOf(false) }
    var addingAddress by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Page(title = server.displayName, onBack = onBack) {
        Section(header = null) {
            StatusRow(server, ui.connection)
            RowDivider()
            ButtonRow(stringResource(R.string.check_now), onClick = onCheckNow, working = ui.checking, enabled = !ui.checking)
            if (!ui.checking && ui.check != null) CheckResult(ui.check)
        }

        Section(
            header = stringResource(R.string.account),
            footer = stringResource(AppR.string.signed_in_as_podcasts, server.auth.userName),
        ) {
            if (ui.refused) {
                Row(Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                    Icon(Icons.Filled.PersonOff, contentDescription = null, tint = Caution)
                    Text(stringResource(R.string.sign_in_refused_warning), color = Caution)
                }
                RowDivider()
            }
            ButtonRow(stringResource(R.string.sign_in_again), onClick = onSignInAgain)
            if (isOwnAudio && server.auth.isSignedIn) {
                RowDivider()
                ButtonRow(stringResource(R.string.delete_account_ellipsis), onClick = { confirmDelete = true }, color = palette.error, working = ui.detail.deleting)
            }
        }

        Section(header = stringResource(R.string.server)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
            ) {
                Text(stringResource(AppR.string.kind), modifier = Modifier.weight(1f))
                ServerKindIcon(server.kind, size = 22.dp)
                // TODO(P5): "podfetch" for a gPodder server that said it is one.
                Text(kindTitle(server.kind), color = palette.muted)
            }
            ui.detail.version?.let {
                RowDivider()
                ValueRow(stringResource(R.string.version), it, onClick = null)
            }
            RowDivider()
            ValueRow(stringResource(R.string.name), server.displayName, onClick = { renaming = true })
        }

        // TODO(P3): Libraries, a switch per podcast library. TODO(P5): Sync — Sync Now, Last Synced.

        if (isOwnAudio) {
            Section(header = stringResource(R.string.account), footer = stringResource(AppR.string.account_pages_footer)) {
                ButtonRow(stringResource(R.string.family), onClick = onFamily, icon = Icons.Filled.Groups)
                RowDivider(start = 56.dp)
                ButtonRow(stringResource(R.string.devices), onClick = onDevices, icon = Icons.Outlined.Devices)
                RowDivider(start = 56.dp)
                ButtonRow(stringResource(R.string.listening), onClick = onListening, icon = Icons.AutoMirrored.Outlined.ShowChart)
            }
        }

        Section(header = stringResource(R.string.connection)) {
            val fixed = (server.connection as? ConnectionMode.Fixed)?.url
            ChoiceRow(stringResource(R.string.automatic), selected = fixed == null) { onConnection(ConnectionMode.Automatic) }
            server.candidateAddresses.forEach { address ->
                RowDivider()
                ChoiceRow(address.url, selected = fixed == address.url) { onConnection(ConnectionMode.Fixed(address.url)) }
            }
        }

        Section(
            header = stringResource(R.string.addresses),
            footer = stringResource(if (server.kind == ServerKinds.OWN_AUDIO) AppR.string.addresses_footer_own_audio else R.string.addresses_footer),
        ) {
            server.candidateAddresses.forEach { address ->
                AddressRow(address, onRemove = if (address.origin == AddressOrigin.USER) ({ onRemoveAddress(address.url) }) else null)
                RowDivider()
            }
            ButtonRow(stringResource(R.string.add_address), onClick = { addingAddress = true }, icon = Icons.Filled.Add)
        }

        Section(header = null) {
            ButtonRow(stringResource(R.string.remove_server), onClick = { confirmRemove = true }, color = palette.error)
        }
    }

    if (renaming) {
        TextDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.name),
            initial = server.displayName,
            confirm = stringResource(R.string.save),
            onConfirm = { onRename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (addingAddress) {
        TextDialog(
            title = stringResource(R.string.add_address),
            label = stringResource(R.string.address),
            placeholder = "http://192.168.1.20:4533",
            initial = "",
            confirm = stringResource(R.string.add),
            keyboard = KeyboardType.Uri,
            onConfirm = { onAddAddress(it); addingAddress = false },
            onDismiss = { addingAddress = false },
        )
    }
    if (confirmRemove) {
        RemoveServerDialog(server, onConfirm = { confirmRemove = false; onRemove() }, onDismiss = { confirmRemove = false })
    }
    if (confirmDelete) {
        DeleteAccountDialog(
            host = server.host.toHttpUrlOrNull()?.host ?: server.displayName,
            onConfirm = { confirmDelete = false; onDeleteAccount(it) },
            onDismiss = { confirmDelete = false },
        )
    }
    ui.detail.addressError?.let {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.couldnt_add_address)) },
            text = { Text(it.text()) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.ok)) } },
        )
    }
    ui.detail.error?.let { ErrorAlert(it.text(), onDismissError) }
}

@Composable
private fun StatusRow(server: ServerRecord, connection: ConnectionState?) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        when (connection) {
            is ConnectionState.Connected -> {
                val scope = server.candidateAddresses.firstOrNull { ServerAddress.normalized(it.url) == ServerAddress.normalized(connection.url) }?.scope
                    ?: AddressScope.infer(connection.url)
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = palette.success)
                TwoLines(stringResource(R.string.connected_via, stringResource(scope.title())), connection.url)
            }
            ConnectionState.Unreachable -> {
                Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Caution)
                Text(stringResource(R.string.no_address_answers), color = Caution)
            }
            ConnectionState.Checking, null -> {
                Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = palette.muted)
                Text(stringResource(R.string.checking), color = palette.muted)
            }
        }
    }
}

@Composable
private fun CheckResult(check: ConnectionCheck) {
    val palette = LocalPalette.current
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date.from(check.at))
    val (icon, color, text) = when (check.status) {
        ConnectionCheck.Status.OK -> Triple(Icons.Filled.CheckCircle, palette.success, stringResource(R.string.check_ok, time))
        ConnectionCheck.Status.REJECTED -> Triple(Icons.Filled.Error, palette.error, stringResource(R.string.check_rejected, time))
        ConnectionCheck.Status.UNREACHABLE -> Triple(Icons.Filled.WifiOff, Caution, stringResource(R.string.check_unreachable, time))
    }
    Row(Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.minTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun AddressRow(address: ServerAddress, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TwoLines(address.url, "${stringResource(address.scope.title())} · ${stringResource(address.origin.title())}", Modifier.weight(1f))
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove), tint = LocalPalette.current.error) }
        }
    }
}

fun AddressScope.title(): Int = when (this) {
    AddressScope.LAN -> R.string.scope_home_network
    AddressScope.VPN -> R.string.scope_vpn
    AddressScope.PUBLIC -> R.string.scope_internet
}

fun AddressOrigin.title(): Int = when (this) {
    AddressOrigin.ADDED -> R.string.origin_added_with
    AddressOrigin.SERVER -> R.string.origin_from_server
    AddressOrigin.USER -> R.string.origin_added_by_you
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
    message: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                if (message != null) Text(message)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    placeholder = placeholder?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The account is deleted for good. own.audio asks a password account for its password; an
 * account that signs in with Google leaves the field empty.
 */
@Composable
private fun DeleteAccountDialog(host: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_account_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                Text(stringResource(R.string.delete_account_message, host))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(stringResource(R.string.delete_account_password_note), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.ifEmpty { null }) }) { Text(stringResource(R.string.delete_account), color = LocalPalette.current.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
