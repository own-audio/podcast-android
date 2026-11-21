package audio.own.kit.ownaudio

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where one server's tokens live between launches. */
interface TokenStore {
    /**
     * Called with every rotated pair before it is used, and with `null` when the account is
     * signed out or its sign-in was refused. Write it durably: the old refresh token is dead.
     */
    suspend fun save(tokens: Tokens?)
}

/** Whether a server's account can make calls. */
enum class SignInState {
    SIGNED_IN,
    SIGNED_OUT,
    /** The server refused the refresh token: "Sign In Again" on the server's page. */
    SIGN_IN_REFUSED,
}

/**
 * The tokens of one signed-in account, refreshed one at a time.
 *
 * A refresh token works once: the server rotates it on every use and treats a second use as
 * theft, signing the device out. So requests that find the access token expired at the same
 * moment share one refresh, and the new pair is stored before anyone uses it. Keep one session
 * per server for the life of the process ([TokenSessions]), however often clients are rebuilt.
 */
class TokenSession(
    initial: Tokens?,
    private val store: TokenStore? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    @Volatile private var tokens: Tokens? = initial

    private val _state = MutableStateFlow(if (initial == null) SignInState.SIGNED_OUT else SignInState.SIGNED_IN)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    val current: Tokens? get() = tokens

    /**
     * An access token that is good now, refreshing first when it expires within a minute.
     * [refresh] trades a refresh token for a new pair (the server's `refresh_token` grant).
     */
    suspend fun accessToken(refresh: suspend (String) -> Tokens): String {
        val now = tokens ?: throw notUsable()
        if (isFresh(now)) return now.accessToken
        return refresh(now.accessToken, refresh).accessToken
    }

    /**
     * Refreshes once for everyone who asks with the same [stale] access token: whoever waits
     * behind a refresh gets its result instead of spending the new refresh token again.
     */
    suspend fun refresh(stale: String?, refresh: suspend (String) -> Tokens): Tokens = mutex.withLock {
        val held = tokens ?: throw notUsable()
        if (held.accessToken != stale && isFresh(held)) return held
        val fresh = try {
            refresh(held.refreshToken)
        } catch (problem: OwnAudioException.Problem) {
            if (problem.code == "invalid_grant" || problem.code == "invalid_credentials") {
                tokens = null
                _state.value = SignInState.SIGN_IN_REFUSED
                store?.save(null)
                throw OwnAudioException.SignInRefused
            }
            throw problem
        }
        try {
            store?.save(fresh)
        } finally {
            tokens = fresh
        }
        fresh
    }

    /** A fresh sign-in: the only way new tokens replace held ones besides a refresh. */
    suspend fun signedIn(fresh: Tokens) = mutex.withLock {
        store?.save(fresh)
        tokens = fresh
        _state.value = SignInState.SIGNED_IN
    }

    /** Forgets the tokens (signed out here, everywhere, or the account deleted). */
    suspend fun signedOut() = mutex.withLock {
        tokens = null
        _state.value = SignInState.SIGNED_OUT
        store?.save(null)
    }

    private fun isFresh(t: Tokens): Boolean = Duration.between(clock.instant(), t.expiresAt) > MARGIN

    private fun notUsable(): OwnAudioException =
        if (_state.value == SignInState.SIGN_IN_REFUSED) OwnAudioException.SignInRefused else OwnAudioException.SignedOut

    companion object {
        /** Refresh this long before the access token expires, so none dies on the way. */
        val MARGIN: Duration = Duration.ofSeconds(60)
    }
}

/** One [TokenSession] per server id, kept across rebuilt clients. */
class TokenSessions {
    private val sessions = ConcurrentHashMap<String, TokenSession>()

    fun session(serverId: String, create: () -> TokenSession): TokenSession = sessions.computeIfAbsent(serverId) { create() }

    fun remove(serverId: String) {
        sessions.remove(serverId)
    }
}
