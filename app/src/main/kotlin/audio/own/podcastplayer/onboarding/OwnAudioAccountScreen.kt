package audio.own.podcastplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.components.AppleSignInButton
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.podcastplayer.apple.AppleSignIn
import audio.own.podcastplayer.apple.followApple
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.servers.ServersModel
import audio.own.podcastplayer.servers.TwoFactorNeeded
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountMode { SIGN_UP, SIGN_IN }

data class AccountState(
    val mode: AccountMode,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val working: Boolean = false,
    val error: UiText? = null,
    val googleClientId: String? = null,
    /** own.audio runs Sign in with Apple in the browser. */
    val appleWeb: Boolean = false,
    val appleWorking: Boolean = false,
    val done: Boolean = false,
) {
    val canSubmit: Boolean
        get() = '@' in email && !working && !appleWorking && when (mode) {
            AccountMode.SIGN_UP -> name.isNotBlank() && password.length >= 12
            AccountMode.SIGN_IN -> password.isNotEmpty()
        }
}

/**
 * own.audio's hosted service in one page, for creating an account or signing in to one: Google
 * first where the build has it, then email and password. The address is fixed.
 */
class OwnAudioAccountViewModel(
    mode: AccountMode,
    private val servers: ServersModel,
    private val log: AppLog,
    googleAvailable: Boolean,
    private val apple: AppleSignIn? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountState(mode))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        viewModelScope.launch { servers.signedIn.collect { _state.update { it.copy(done = true) } } }
        if (googleAvailable || apple != null) {
            viewModelScope.launch {
                val options = servers.signInOptions(servers.hostedService)
                _state.update {
                    it.copy(googleClientId = options.googleClientId.takeIf { googleAvailable }, appleWeb = apple != null && options.appleWeb)
                }
            }
        }
        apple?.let {
            viewModelScope.followApple(
                it, AppleSignIn.ORIGIN_ACCOUNT,
                onBusy = { busy -> _state.update { s -> s.copy(appleWorking = busy) } },
                onError = { text -> _state.update { s -> s.copy(error = text) } },
                onDone = { _state.update { s -> s.copy(done = true) } },
            )
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setEmail(value: String) = _state.update { it.copy(email = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        run {
            when (s.mode) {
                AccountMode.SIGN_UP -> servers.createAccount(s.name, s.email, s.password)
                AccountMode.SIGN_IN -> servers.signIn(servers.hostedService, s.email.trim(), s.password, "")
            }
        }
    }

    fun signInWithGoogle(idToken: String) = run { servers.signInWithGoogle(servers.hostedService, idToken, "") }

    fun googleFailed(reason: String) {
        log.error("google", "sign-in with Google unavailable: $reason")
        _state.update { it.copy(googleClientId = null) }
    }

    /** Opens own.audio's Sign in with Apple in the browser; the outcome comes back through [AppleSignIn]. */
    fun signInWithApple() {
        val apple = apple ?: return
        if (_state.value.working || _state.value.appleWorking) return
        _state.update { it.copy(error = null) }
        apple.begin(servers.hostedService, "", null, AppleSignIn.ORIGIN_ACCOUNT)
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
fun OwnAudioAccountContent(
    state: AccountState,
    showGoogle: Boolean,
    onCancel: () -> Unit,
    onName: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onApple: () -> Unit = {},
) {
    val signUp = state.mode == AccountMode.SIGN_UP
    val links = LocalUriHandler.current
    Page(
        title = stringResource(if (signUp) R.string.own_audio_account else R.string.sign_in_to_own_audio),
        onBack = onCancel,
        closeIsCancel = true,
    ) {
        val google = showGoogle && state.googleClientId != null
        if (google || state.appleWeb) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                if (state.appleWeb) AppleSignInButton(onClick = onApple, enabled = !state.working && !state.appleWorking)
                if (google) GoogleSignInButton(onClick = onGoogle, enabled = !state.working && !state.appleWorking)
                Text(
                    stringResource(if (signUp) R.string.or_create_with_email else R.string.or_sign_in_with_email),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalPalette.current.muted,
                    modifier = Modifier.padding(start = Tokens.Spacing.lg),
                )
            }
        }
        Section(header = null, footer = if (signUp) stringResource(R.string.password_rule) else null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
            ) {
                Icon(Icons.Filled.Language, contentDescription = null, tint = LocalPalette.current.muted)
                Text(stringResource(R.string.server), modifier = Modifier.weight(1f))
                Text(OWN_AUDIO, color = LocalPalette.current.muted)
            }
            if (signUp) {
                RowDivider()
                FormTextField(state.name, onName, stringResource(R.string.your_name), null, KeyboardType.Text)
            }
            RowDivider()
            FormTextField(state.email, onEmail, stringResource(R.string.email), null, KeyboardType.Email)
            RowDivider()
            FormTextField(
                state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, ime = ImeAction.Go,
                onGo = { if (state.canSubmit) onSubmit() },
            )
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null, footer = if (signUp) stringResource(R.string.sign_up_footer) else null) {
            ButtonRow(
                label = stringResource(if (signUp) R.string.create_account else R.string.sign_in),
                onClick = onSubmit,
                enabled = state.canSubmit,
                working = state.working,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (signUp) {
            Row(Modifier.padding(horizontal = Tokens.Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
                androidx.compose.material3.TextButton(onClick = { links.openUri(TERMS_URL) }) { Text(stringResource(R.string.terms), fontWeight = FontWeight.Medium) }
                androidx.compose.material3.TextButton(onClick = { links.openUri(PRIVACY_POLICY_URL) }) { Text(stringResource(R.string.privacy_policy), fontWeight = FontWeight.Medium) }
            }
        }
    }
}

const val OWN_AUDIO = "own.audio"
const val TERMS_URL = "https://www.own.audio/terms/"
const val PRIVACY_POLICY_URL = "https://www.own.audio/privacy/"
