package audio.own.podcastplayer.apple

import audio.own.core.strings.R
import audio.own.kit.ownaudio.Pkce
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.UserFacingException
import audio.own.podcastplayer.app.userMessage
import audio.own.podcastplayer.servers.ServersModel
import audio.own.podcastplayer.servers.TwoFactorNeeded
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Sign in with Apple waiting for the browser to come back. Kept on disk: Android may end the
 * app while the browser is open, and the code that comes back is useless without the verifier.
 */
@Serializable
data class PendingApple(
    val state: String,
    val verifier: String,
    /** The own.audio server's root, as found when the sign-in started. */
    val host: String,
    val name: String,
    /** The saved server this signs in to again; null to add one. */
    val replacing: String? = null,
    /** The screen that started it: it shows the outcome. */
    val origin: String,
    val startedAtMs: Long,
)

interface PendingAppleStore {
    fun load(): PendingApple?
    fun save(pending: PendingApple?)
}

/** One small file, in a folder that isn't backed up; gone once the browser came back. */
class FilePendingAppleStore(private val file: File) : PendingAppleStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): PendingApple? = runCatching { json.decodeFromString(PendingApple.serializer(), file.readText()) }.getOrNull()

    override fun save(pending: PendingApple?) {
        if (pending == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(PendingApple.serializer(), pending))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}

/** Where a Sign in with Apple is; [origin] names the screen it belongs to. */
sealed interface AppleStatus {
    data object Idle : AppleStatus

    /** Asking the server for its start page. */
    data class Starting(val origin: String) : AppleStatus

    /** The page to open in the browser, then open there ([opened]). */
    data class Browser(val origin: String, val url: String, val opened: Boolean) : AppleStatus

    /** The browser came back with a code; trading it for tokens. */
    data class Working(val origin: String) : AppleStatus

    /** Signed in: the screen closes. */
    data class Done(val origin: String) : AppleStatus

    data class Failed(val origin: String, val text: UiText) : AppleStatus
}

val AppleStatus.origin: String?
    get() = when (this) {
        AppleStatus.Idle -> null
        is AppleStatus.Starting -> origin
        is AppleStatus.Browser -> origin
        is AppleStatus.Working -> origin
        is AppleStatus.Done -> origin
        is AppleStatus.Failed -> origin
    }

/** Whether the screen [origin] should show its Apple sign-in as under way. */
fun AppleStatus.busyFor(origin: String): Boolean = this.origin == origin && (this is AppleStatus.Starting || this is AppleStatus.Browser || this is AppleStatus.Working)

/**
 * Sign in with Apple through the browser, for the whole app: the screens start it, the
 * browser's redirect finishes it ([AppleRedirectActivity]), whichever screen or process is
 * there by then. The code is accepted only with the `state` this app made; cancelling (back
 * from the browser, or Apple's own Cancel) ends it without a word.
 */
class AppleSignIn(
    private val servers: ServersModel,
    private val store: PendingAppleStore,
    private val redirectUri: String,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val _status = MutableStateFlow<AppleStatus>(AppleStatus.Idle)
    val status: StateFlow<AppleStatus> = _status.asStateFlow()

    @Volatile private var pending: PendingApple? = null

    /** The app went out of sight after the browser opened: coming back without a redirect is a cancel. */
    @Volatile private var left = false
    private var starting: Job? = null

    init {
        // The process was ended while the browser was open: the sign-in goes on from the file.
        val saved = store.load()
        if (saved != null && !saved.expired()) {
            pending = saved
            left = true
            _status.value = AppleStatus.Browser(saved.origin, url = "", opened = true)
        } else if (saved != null) {
            store.save(null)
        }
    }

    /** Starts a sign-in to the own.audio server at [address]; the browser opens when the server's page is known. */
    fun begin(address: String, name: String, replacing: String?, origin: String) {
        if (_status.value.let { it is AppleStatus.Starting || it is AppleStatus.Working }) return
        starting?.cancel()
        _status.value = AppleStatus.Starting(origin)
        starting = scope.launch {
            try {
                val pkce = Pkce.create(random)
                val state = Pkce.state(random)
                val start = servers.appleStart(address, redirectUri, pkce, state)
                val made = PendingApple(state, pkce.verifier, start.host, name, replacing, origin, clock.millis())
                store.save(made)
                pending = made
                left = false
                _status.value = AppleStatus.Browser(origin, start.url, opened = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("apple", "Sign in with Apple didn't start: ${e.message}")
                _status.value = AppleStatus.Failed(origin, e.userMessage())
            }
        }
    }

    /** The browser is opening the page. */
    fun opened() {
        val now = _status.value as? AppleStatus.Browser ?: return
        left = false
        _status.value = now.copy(opened = true)
    }

    /** Nothing on the device opens web pages. */
    fun noBrowser() {
        val origin = _status.value.origin ?: return
        forget()
        log.error("apple", "no browser for Sign in with Apple")
        _status.value = AppleStatus.Failed(origin, UiText(R.string.error_no_browser))
    }

    fun appLeft() {
        if ((_status.value as? AppleStatus.Browser)?.opened == true) left = true
    }

    /** The app is on screen again. Without a redirect first, the browser was closed: a cancel. */
    fun appReturned() {
        val now = _status.value as? AppleStatus.Browser ?: return
        if (!now.opened || !left) return
        log.info("apple", "Sign in with Apple closed without an answer")
        forget()
        _status.value = AppleStatus.Idle
    }

    /**
     * The browser came back with [code] or [error], and [state]. Checks it at once (before the
     * app's screen returns, so that isn't taken for a cancel), then trades the code.
     */
    fun redirected(code: String?, error: String?, state: String?) {
        val waiting = pending
        if (waiting == null) {
            log.error("apple", "an Apple redirect with no sign-in waiting; ignored")
            return
        }
        // The code works once and only with this verifier: forget both whatever happens next.
        forget()
        val origin = waiting.origin
        when {
            state == null || !constantTimeEquals(state, waiting.state) -> {
                log.error("apple", "an Apple redirect for another sign-in; refused")
                _status.value = AppleStatus.Failed(origin, UiText(R.string.error_apple_state))
            }
            error == "access_denied" -> _status.value = AppleStatus.Idle
            error != null || code.isNullOrEmpty() -> {
                log.error("apple", "Sign in with Apple failed: ${error ?: "no code"}")
                _status.value = AppleStatus.Failed(origin, UiText(R.string.error_apple_failed))
            }
            waiting.expired() -> _status.value = AppleStatus.Failed(origin, UiText(R.string.error_apple_expired))
            else -> {
                _status.value = AppleStatus.Working(origin)
                scope.launch { exchange(waiting, code) }
            }
        }
    }

    /** The screen [origin] has shown the failure or closed. */
    fun dismiss(origin: String) {
        _status.update { if (it.origin == origin && (it is AppleStatus.Failed || it is AppleStatus.Done)) AppleStatus.Idle else it }
    }

    private suspend fun exchange(waiting: PendingApple, code: String) {
        _status.value = try {
            // After the process was ended, the saved servers may still be loading.
            servers.state.first { it.loaded }
            servers.signInWithApple(waiting.host, code, waiting.verifier, waiting.name, waiting.replacing)
            log.info("apple", "signed in with Apple")
            AppleStatus.Done(waiting.origin)
        } catch (e: TwoFactorNeeded) {
            // The code is asked for next, over whichever screen is open.
            AppleStatus.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            log.error("apple", "Sign in with Apple refused: ${e.cause?.message ?: e.message}")
            AppleStatus.Failed(waiting.origin, e.text)
        } catch (e: Exception) {
            log.error("apple", "Sign in with Apple failed: ${e.message}")
            AppleStatus.Failed(waiting.origin, e.userMessage())
        }
    }

    private fun forget() {
        pending = null
        left = false
        store.save(null)
    }

    private fun PendingApple.expired(): Boolean = clock.millis() - startedAtMs > PENDING_FOR_MS

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        /** Longer than anyone takes at Apple; the server's code lives shorter still. */
        const val PENDING_FOR_MS = 30L * 60 * 1000

        const val ORIGIN_ACCOUNT = "account"
        const val ORIGIN_FORM = "form"
        fun originServer(serverId: String) = "server:$serverId"
    }
}

/**
 * Follows [apple] for the screen [origin]: busy while its sign-in runs, a failure shown as the
 * screen's error, [onDone] once signed in. A screen made again after the process was ended
 * still gets the outcome: the status is kept until the screen has taken it.
 */
fun CoroutineScope.followApple(
    apple: AppleSignIn,
    origin: String,
    onBusy: (Boolean) -> Unit,
    onError: (UiText) -> Unit,
    onDone: () -> Unit,
) = launch {
    apple.status.collect { status ->
        onBusy(status.busyFor(origin))
        if (status.origin != origin) return@collect
        when (status) {
            is AppleStatus.Failed -> {
                onError(status.text)
                apple.dismiss(origin)
            }
            is AppleStatus.Done -> {
                onDone()
                apple.dismiss(origin)
            }
            else -> Unit
        }
    }
}
