package audio.own.podcastplayer.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.apple.AppleSignIn
import audio.own.podcastplayer.apple.followApple
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.ServersModel
import audio.own.podcastplayer.servers.TwoFactorNeeded
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerFormState(
    val address: String = "",
    val username: String = "",
    val password: String = "",
    /** One of [ServerKinds]; null finds out from the address. */
    val kind: String? = null,
    val connecting: Boolean = false,
    val tryingDemo: Boolean = false,
    val error: UiText? = null,
    /** The web client id when the own.audio server at the typed address offers Google. */
    val googleClientId: String? = null,
    /** The own.audio server at the typed address runs Sign in with Apple in the browser. */
    val appleWeb: Boolean = false,
    val appleWorking: Boolean = false,
    /** A sign-in finished: the form closes (Add Server) or the tabs take over (welcome). */
    val done: Boolean = false,
) {
    val canConnect: Boolean get() = address.isNotBlank() && username.isNotEmpty() && password.isNotEmpty() && !busy

    /** Only own.audio signs in with Google or Apple. */
    val mayOfferGoogle: Boolean get() = kind == null || kind == ServerKinds.OWN_AUDIO
    val busy: Boolean get() = connecting || tryingDemo || appleWorking
}

/** Add a Server, and the welcome screen's demo. */
class ServerFormViewModel(
    private val servers: ServersModel,
    private val log: AppLog,
    private val googleAvailable: Boolean,
    private val lookupDelayMs: Long = 600,
    private val apple: AppleSignIn? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerFormState())
    val state: StateFlow<ServerFormState> = _state.asStateFlow()
    private var lookup: Job? = null

    init {
        viewModelScope.launch { servers.signedIn.collect { _state.update { it.copy(done = true, connecting = false) } } }
        apple?.let {
            viewModelScope.followApple(
                it, AppleSignIn.ORIGIN_FORM,
                onBusy = { busy -> _state.update { s -> s.copy(appleWorking = busy) } },
                onError = { text -> _state.update { s -> s.copy(error = text) } },
                onDone = { _state.update { s -> s.copy(done = true) } },
            )
        }
    }

    fun setAddress(value: String) {
        _state.update { it.copy(address = value, googleClientId = null, appleWeb = false) }
        lookUpGoogle()
    }

    fun setKind(value: String?) {
        _state.update { it.copy(kind = value, googleClientId = null, appleWeb = false, error = null) }
        lookUpGoogle()
    }

    private fun lookUpGoogle() {
        lookup?.cancel()
        val value = _state.value.address
        if ((!googleAvailable && apple == null) || !_state.value.mayOfferGoogle || value.trim().length <= 3) return
        // Asked once typing pauses: whether an own.audio server answers there, and whether it takes Google or Apple.
        lookup = viewModelScope.launch {
            delay(lookupDelayMs)
            val options = servers.signInOptions(value)
            _state.update {
                if (it.address != value) it else it.copy(googleClientId = options.googleClientId.takeIf { googleAvailable }, appleWeb = apple != null && options.appleWeb)
            }
        }
    }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun connect() {
        val s = _state.value
        if (!s.canConnect) return
        run({ it.copy(connecting = true) }, { it.copy(connecting = false) }) { servers.signIn(s.address, s.username, s.password, name = "", kind = s.kind) }
    }

    fun tryDemo() {
        if (_state.value.busy) return
        run({ it.copy(tryingDemo = true) }, { it.copy(tryingDemo = false) }) { servers.addDemo() }
    }

    fun signInWithGoogle(idToken: String) {
        val s = _state.value
        run({ it.copy(connecting = true) }, { it.copy(connecting = false) }) { servers.signInWithGoogle(s.address, idToken, "") }
    }

    /** Sign in with Apple to the own.audio server at the typed address, in the browser. */
    fun signInWithApple() {
        val apple = apple ?: return
        val s = _state.value
        if (s.address.isBlank() || s.busy) return
        _state.update { it.copy(error = null) }
        apple.begin(s.address, "", null, AppleSignIn.ORIGIN_FORM)
    }

    /** Credential Manager said no for a reason other than the listener cancelling: no button for this server. */
    fun googleFailed(reason: String) {
        log.error("google", "sign-in with Google unavailable: $reason")
        _state.update { it.copy(googleClientId = null) }
    }

    private fun run(start: (ServerFormState) -> ServerFormState, end: (ServerFormState) -> ServerFormState, work: suspend () -> Unit) {
        _state.update { start(it).copy(error = null) }
        viewModelScope.launch {
            try {
                work()
            } catch (e: TwoFactorNeeded) {
                // The code is asked for next.
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.error("servers", "adding a server failed: ${e.message}")
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update(end)
            }
        }
    }
}
