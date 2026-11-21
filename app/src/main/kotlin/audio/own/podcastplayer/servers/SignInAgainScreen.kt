package audio.own.podcastplayer.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.onboarding.FormTextField
import audio.own.podcastplayer.ui.BarAction
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInAgainState(
    val title: String,
    val isOwnAudio: Boolean,
    val username: String,
    val password: String = "",
    val working: Boolean = false,
    val error: UiText? = null,
    val googleClientId: String? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !working
}

/** The saved server's sign-in, entered again: its username kept, the password asked for. */
class SignInAgainViewModel(
    private val serverId: String,
    private val servers: ServersModel,
    private val log: AppLog,
    googleAvailable: Boolean,
) : ViewModel() {
    private val record = servers.state.value.server(serverId)
    private val _state = MutableStateFlow(
        SignInAgainState(
            title = record?.displayName.orEmpty(),
            isOwnAudio = record?.kind == ServerKinds.OWN_AUDIO,
            username = record?.auth?.userName.orEmpty(),
        ),
    )
    val state: StateFlow<SignInAgainState> = _state.asStateFlow()

    init {
        viewModelScope.launch { servers.signedIn.collect { if (it == serverId) _state.update { s -> s.copy(done = true) } } }
        if (googleAvailable && record?.kind == ServerKinds.OWN_AUDIO) {
            viewModelScope.launch { _state.update { it.copy(googleClientId = servers.googleClientIdFor(serverId)) } }
        }
    }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        run { servers.signInAgain(serverId, s.username.trim(), s.password) }
    }

    fun signInWithGoogle(idToken: String) = run { servers.signInAgainWithGoogle(serverId, idToken) }

    fun googleFailed(reason: String) {
        log.error("google", "sign-in with Google unavailable: $reason")
        _state.update { it.copy(googleClientId = null) }
    }

    private fun run(work: suspend () -> Unit) {
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                work()
            } catch (e: TwoFactorNeeded) {
                // The code is asked for next.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }
}

@Composable
fun SignInAgainContent(
    state: SignInAgainState,
    showGoogle: Boolean,
    onCancel: () -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
) {
    Page(
        title = state.title,
        onBack = onCancel,
        closeIsCancel = true,
        actions = { BarAction(stringResource(R.string.sign_in), enabled = state.canSubmit, working = state.working, onClick = onSubmit) },
    ) {
        if (showGoogle && state.googleClientId != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                GoogleSignInButton(onClick = onGoogle, enabled = !state.working)
                Text(stringResource(R.string.sign_in_again_google_footer), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        }
        Section(header = null) {
            FormTextField(state.username, onUsername, stringResource(if (state.isOwnAudio) R.string.email else R.string.username), null, KeyboardType.Email)
            RowDivider()
            FormTextField(
                state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, ime = ImeAction.Go,
                onGo = onSubmit,
            )
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
    }
}
