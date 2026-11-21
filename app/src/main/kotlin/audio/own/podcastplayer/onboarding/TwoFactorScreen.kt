package audio.own.podcastplayer.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.servers.ServersModel
import audio.own.podcastplayer.servers.TwoFactorNeeded
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TwoFactorState(val code: String = "", val checking: Boolean = false, val error: UiText? = null) {
    val canCheck: Boolean get() = code.isNotBlank() && !checking
}

/** The second step of an own.audio sign-in: the authenticator's six digits, or a recovery code. */
class TwoFactorViewModel(private val servers: ServersModel) : ViewModel() {
    private val _state = MutableStateFlow(TwoFactorState())
    val state: StateFlow<TwoFactorState> = _state.asStateFlow()

    fun setCode(value: String) = _state.update { it.copy(code = value) }

    fun check() {
        val s = _state.value
        if (!s.canCheck) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                servers.finishTwoFactor(s.code)
            } catch (e: TwoFactorNeeded) {
                // Asked again: keep the page.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun cancel() = servers.cancelTwoFactor()
}

@Composable
fun TwoFactorContent(state: TwoFactorState, onCode: (String) -> Unit, onCheck: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    Page(title = stringResource(R.string.two_factor_title), onBack = { if (!state.checking) onCancel() }, closeIsCancel = true) {
        Section(header = null, footer = stringResource(R.string.two_factor_footer)) {
            FormTextField(
                state.code, onCode, stringResource(R.string.code), null,
                // Recovery codes have letters, so not a number pad.
                KeyboardType.Ascii,
                ime = ImeAction.Go,
                onGo = onCheck,
                modifier = Modifier.focusRequester(focus),
            )
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null) {
            ButtonRow(stringResource(R.string.sign_in), onClick = onCheck, enabled = state.canCheck, working = state.checking, color = MaterialTheme.colorScheme.primary)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
