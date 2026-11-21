package audio.own.podcastplayer.account

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.core.designsystem.components.LoadStateView
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.Session
import audio.own.kit.ownaudio.revokeSession
import audio.own.kit.ownaudio.sessions
import audio.own.podcastplayer.app.Load
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.forView
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import java.text.DateFormat
import java.time.Clock
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesState(val load: Load<List<Session>> = Load.Loading, val error: UiText? = null, val signedOutEverywhere: Boolean = false)

/** The devices signed in to the account, this one first; any can be signed out, or all at once. */
class DevicesViewModel(
    private val client: () -> OwnAudioClient?,
    private val signOutEverywhere: suspend () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesState())
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val client = client() ?: return
        _state.update { it.copy(load = Load.Loading) }
        viewModelScope.launch {
            try {
                val sessions = client.sessions().sortedWith(
                    compareByDescending<Session> { it.current }.thenByDescending { it.lastUsedAt ?: it.signedInAt },
                )
                _state.update { it.copy(load = Load.Loaded(sessions)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(load = Load.Failed(e.userMessage())) }
            }
        }
    }

    fun signOut(session: Session) {
        val client = client() ?: return
        _state.update { s -> (s.load as? Load.Loaded)?.let { s.copy(load = Load.Loaded(it.value.filterNot { x -> x.id == session.id })) } ?: s }
        perform { client.revokeSession(session.id) }
    }

    fun signOutEverywhere() = perform {
        signOutEverywhere.invoke()
        _state.update { it.copy(signedOutEverywhere = true) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun perform(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }
}

@Composable
fun DevicesContent(
    state: DevicesState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: (Session) -> Unit,
    onSignOutEverywhere: () -> Unit,
    onDismissError: () -> Unit,
    clock: Clock = Clock.systemDefaultZone(),
) {
    var confirmAll by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Page(title = stringResource(R.string.devices), onBack = onBack, scrolls = false) {
        LoadStateView(state.load.forView(), onRetry = onRetry) { sessions ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Section(header = null, footer = stringResource(R.string.devices_footer)) {
                    sessions.forEachIndexed { index, session ->
                        if (index > 0) RowDivider(start = 56.dp)
                        DeviceRow(session, clock, onSignOut = { onSignOut(session) })
                    }
                }
                Section(header = null) {
                    ButtonRow(stringResource(R.string.sign_out_everywhere), onClick = { confirmAll = true }, color = palette.error)
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.sign_out_everywhere_question)) },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; onSignOutEverywhere() }) { Text(stringResource(R.string.sign_out_everywhere), color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.error?.let { ErrorAlert(it.text(), onDismissError) }
}

private fun icon(kind: String): ImageVector = when (kind) {
    "ios" -> Icons.Filled.PhoneIphone
    "android" -> Icons.Filled.PhoneAndroid
    "macos" -> Icons.Filled.Laptop
    "web" -> Icons.Filled.Public
    "tvos" -> Icons.Filled.Tv
    "windows" -> Icons.Filled.DesktopWindows
    else -> Icons.Filled.Computer
}

@Composable
private fun DeviceRow(session: Session, clock: Clock, onSignOut: () -> Unit) {
    val palette = LocalPalette.current
    val accent = MaterialTheme.colorScheme.primary
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        Icon(icon(session.deviceKind), contentDescription = null, tint = accent, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(session.deviceName?.takeIf { it.isNotBlank() } ?: session.deviceKind.replaceFirstChar { it.uppercase() })
                if (session.current) {
                    Box(Modifier.background(accent.copy(alpha = 0.15f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(stringResource(R.string.this_device), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val used = session.lastUsedAt
            val line = if (used != null) {
                val ago = DateUtils.getRelativeTimeSpanString(used.toEpochMilli(), clock.millis(), DateUtils.MINUTE_IN_MILLIS).toString()
                stringResource(R.string.last_used, ago)
            } else {
                stringResource(R.string.signed_in_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(session.signedInAt)))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
        if (!session.current) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = palette.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sign_out), color = palette.error) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = palette.error) },
                        onClick = { menu = false; onSignOut() },
                    )
                }
            }
        }
    }
}
