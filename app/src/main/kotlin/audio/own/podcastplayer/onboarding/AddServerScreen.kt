package audio.own.podcastplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import audio.own.core.designsystem.components.AppleSignInButton
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.kindTitle
import audio.own.podcastplayer.ui.BarAction
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.Section

/** Add a Server: the kind (or Automatic), the address and the sign-in; Cancel / Add, as on iOS. */
@Composable
fun AddServerContent(
    state: ServerFormState,
    showGoogle: Boolean,
    onCancel: () -> Unit,
    onKind: (String?) -> Unit,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onGoogle: () -> Unit,
    onApple: () -> Unit = {},
) {
    Page(
        title = stringResource(R.string.add_a_server),
        onBack = onCancel,
        closeIsCancel = true,
        actions = { BarAction(stringResource(R.string.add), enabled = state.canConnect, working = state.connecting, onClick = onConnect) },
    ) {
        Section(header = null, footer = kindFooter(state.kind)) {
            KindRow(state.kind, onKind)
        }
        Section(header = null) {
            FormTextField(
                state.address, onAddress, stringResource(R.string.address),
                if (state.kind == ServerKinds.OWN_AUDIO) "own.audio" else "https://podcasts.example.com", KeyboardType.Uri,
            )
            RowDivider()
            FormTextField(
                state.username, onUsername,
                stringResource(
                    when (state.kind) {
                        ServerKinds.OWN_AUDIO -> R.string.email
                        null -> AppR.string.email_or_user_name
                        else -> AppR.string.user_name
                    },
                ),
                null, if (state.kind == ServerKinds.OWN_AUDIO) KeyboardType.Email else KeyboardType.Text,
            )
            RowDivider()
            FormTextField(
                state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, ime = ImeAction.Go,
                onGo = { if (state.canConnect) onConnect() },
            )
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        val google = showGoogle && state.mayOfferGoogle && state.googleClientId != null
        val apple = state.mayOfferGoogle && state.appleWeb
        if (google || apple) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                if (apple) AppleSignInButton(onClick = onApple, enabled = !state.busy)
                if (google) GoogleSignInButton(onClick = onGoogle, enabled = !state.busy)
                Text(stringResource(providersNote(google, apple)), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        }
    }
}

@Composable
private fun kindFooter(kind: String?): String = stringResource(
    when (kind) {
        ServerKinds.OWN_AUDIO -> AppR.string.kind_footer_own_audio
        ServerKinds.AUDIOBOOKSHELF -> AppR.string.kind_footer_audiobookshelf
        ServerKinds.GPODDER -> AppR.string.kind_footer_gpodder
        else -> AppR.string.kind_footer_automatic
    },
)

/** Kind, with the choice in a menu, as iOS's picker row. */
@Composable
private fun KindRow(kind: String?, onKind: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val automatic = stringResource(R.string.automatic)
    Box {
        ValueRow(stringResource(AppR.string.kind), kind?.let { kindTitle(it) } ?: automatic, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(automatic) }, onClick = { open = false; onKind(null) })
            ServerKinds.all.forEach { choice ->
                DropdownMenuItem(text = { Text(kindTitle(choice)) }, onClick = { open = false; onKind(choice) })
            }
        }
    }
}

/** The note under the Apple and Google buttons, naming the ones shown. */
fun providersNote(google: Boolean, apple: Boolean): Int = when {
    google && apple -> R.string.apple_or_google_on_this_server
    apple -> R.string.apple_on_this_server
    else -> R.string.google_on_this_server
}
