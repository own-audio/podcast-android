package audio.own.podcastplayer.servers

import audio.own.core.servers.AddressCheck
import audio.own.core.servers.AddressMemory
import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.AddressRace
import audio.own.core.servers.AddressScope
import audio.own.core.servers.ConnectionMode
import audio.own.core.servers.NetworkState
import audio.own.core.servers.ServerAddress
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.ServerStore
import audio.own.core.servers.StoredTokens
import audio.own.core.servers.TypedAddress
import audio.own.core.strings.R
import audio.own.kit.ownaudio.AnswerCache
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.SignInState
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.TokenSessions
import audio.own.kit.ownaudio.TokenStore
import audio.own.kit.ownaudio.Tokens
import audio.own.kit.ownaudio.deleteAccount
import audio.own.kit.ownaudio.discover
import audio.own.kit.ownaudio.finishTwoFactor
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.register
import audio.own.kit.ownaudio.revokeAllSessions
import audio.own.kit.ownaudio.signIn
import audio.own.kit.ownaudio.signOut
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.AppPreferences
import audio.own.podcastplayer.app.UserFacingException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** The server kinds of the podcast app, as stored in [ServerRecord.kind]. */
object ServerKinds {
    const val OWN_AUDIO = "own_audio"
    const val AUDIOBOOKSHELF = "audiobookshelf"
    const val GPODDER = "gpodder"

    /** In the order Add a Server offers them. */
    val all = listOf(OWN_AUDIO, AUDIOBOOKSHELF, GPODDER)
}

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data class Connected(val url: String) : ConnectionState
    data object Unreachable : ConnectionState
}

/** What "Check Now" found, and when, so the button visibly did something. */
data class ConnectionCheck(val status: Status, val at: Instant) {
    enum class Status { OK, REJECTED, UNREACHABLE }
}

/** An own.audio sign-in waiting for its second factor. */
data class PendingTwoFactor(
    val host: String,
    val mfaToken: String,
    val name: String,
    val discovery: Discovery,
    /** The saved server this sign-in is for, when signing in again. */
    val replacing: String?,
)

data class ServersState(
    val loaded: Boolean = false,
    val servers: List<ServerRecord> = emptyList(),
    val activeId: String? = null,
    val connections: Map<String, ConnectionState> = emptyMap(),
    val checks: Map<String, ConnectionCheck> = emptyMap(),
    val checking: Set<String> = emptySet(),
    val pendingTwoFactor: PendingTwoFactor? = null,
) {
    val active: ServerRecord? get() = servers.firstOrNull { it.id == activeId }

    fun server(id: String): ServerRecord? = servers.firstOrNull { it.id == id }

    /** The servers that refuse the saved sign-in: their page offers to sign in again. */
    fun isRefused(id: String): Boolean {
        val record = server(id) ?: return false
        return !record.auth.isSignedIn || checks[id]?.status == ConnectionCheck.Status.REJECTED
    }
}

/**
 * The configured servers: adding one (detection, sign-in, two-factor, Google), signing in again,
 * renaming, the address in use, removing one and deleting the account on it. The record is saved
 * only after a sign-in succeeded.
 */
@OptIn(FlowPreview::class)
class ServersModel(
    private val store: ServerStore,
    private val sessions: TokenSessions,
    private val http: OkHttpClient,
    private val userAgent: String,
    private val device: OwnAudioClient.Device,
    private val preferences: AppPreferences,
    private val detector: ServerDetector,
    private val addressMemory: AddressMemory,
    private val network: StateFlow<NetworkState>,
    private val cache: AnswerCache?,
    private val clearCache: () -> Unit,
    private val isOffline: () -> Boolean,
    private val acceptLanguage: () -> String?,
    private val log: AppLog,
    private val scope: CoroutineScope,
    /** own.audio's hosted service, where the account sheet signs up and in. */
    val hostedService: String = HOSTED_SERVICE,
    private val demoServer: String = DEMO_SERVER,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow(ServersState())
    val state: StateFlow<ServersState> = _state.asStateFlow()

    private val _signedIn = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A server's id each time a sign-in finishes, two-factor included: the sign-in screens close. */
    val signedIn: SharedFlow<String> = _signedIn.asSharedFlow()

    suspend fun load() {
        val servers = store.loadAll()
        val saved = preferences.activeServerId()
        val active = saved?.takeIf { id -> servers.any { it.id == id } } ?: servers.firstOrNull()?.id
        _state.update { it.copy(loaded = true, servers = servers, activeId = active) }
        log.info("servers", "${servers.size} server(s) saved")
        scope.launch { chooseAddresses() }
        // After a network change settles, the fastest address may be another one.
        scope.launch {
            network.drop(1).distinctUntilChangedBy { it.signature }.debounce(NETWORK_SETTLE_MS).collect {
                if (it.connected) chooseAddresses()
            }
        }
    }

    // region Clients

    /** own.audio's client for a saved server, at the address in use; null for other kinds. */
    fun client(serverId: String): OwnAudioClient? {
        val record = _state.value.server(serverId) ?: return null
        if (record.kind != ServerKinds.OWN_AUDIO || record.auth !is ServerAuth.OwnAudio) return null
        return OwnAudioClient(
            baseUrl = baseUrl(record),
            http = http,
            userAgent = userAgent,
            tokens = session(record),
            acceptLanguage = acceptLanguage(),
            cache = cache,
            cacheIdentity = record.serverIdentity ?: record.id,
            isOffline = isOffline,
        )
    }

    private fun baseUrl(record: ServerRecord): String = when (val mode = record.connection) {
        is ConnectionMode.Fixed -> mode.url
        ConnectionMode.Automatic -> (_state.value.connections[record.id] as? ConnectionState.Connected)?.url
            ?: addressMemory.get(record.id, network.value.signature)
            ?: record.host
    }

    private fun session(record: ServerRecord): TokenSession = sessions.session(record.id) {
        val stored = (record.auth as? ServerAuth.OwnAudio)?.tokens
        TokenSession(stored?.toTokens(), persistTokens(record.id))
    }

    private fun persistTokens(serverId: String) = object : TokenStore {
        override suspend fun save(tokens: Tokens?) {
            store.updateTokens(serverId, tokens?.toStored())
            if (tokens == null) log.error("servers", "the server refused the saved sign-in")
            reload()
        }
    }

    private fun unsigned(host: String) = OwnAudioClient(baseUrl = host, http = http, userAgent = userAgent, acceptLanguage = acceptLanguage())

    // endregion

    // region Adding a server

    /**
     * Detects the server at [address] (only of [kind] when one was chosen) and signs in with
     * [username] and [password]. own.audio answering `mfa_required` leaves a [PendingTwoFactor]
     * and throws [TwoFactorNeeded].
     */
    suspend fun signIn(address: String, username: String, password: String, name: String, replacing: String? = null, kind: String? = null) {
        when (val found = detector.detect(address, kind)) {
            // TODO(P3): Audiobookshelf podcast libraries. TODO(P5): gPodder sync accounts.
            is Detected.Audiobookshelf, is Detected.GPodder -> throw UserFacingException(R.string.error_later_version)
            is Detected.OwnAudio -> {
                val tokens = try {
                    unsigned(found.host).signIn(username.trim(), password, device)
                } catch (p: OwnAudioException.Problem) {
                    if (p.code == "mfa_required" && p.mfaToken != null) {
                        _state.update { it.copy(pendingTwoFactor = PendingTwoFactor(found.host, p.mfaToken!!, name, found.discovery, replacing)) }
                        throw TwoFactorNeeded
                    }
                    throw signInProblem(p)
                } catch (e: OwnAudioException.Network) {
                    throw UserFacingException(R.string.error_unreachable, e)
                }
                finishOwnAudio(tokens, found.host, found.discovery, name, replacing)
            }
        }
    }

    suspend fun finishTwoFactor(code: String) {
        val pending = _state.value.pendingTwoFactor ?: return
        val tokens = try {
            unsigned(pending.host).finishTwoFactor(pending.mfaToken, code.trim())
        } catch (p: OwnAudioException.Problem) {
            throw UserFacingException(if (p.status == 429) R.string.error_rate_limited else R.string.error_wrong_code, p)
        } catch (e: OwnAudioException.Network) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        _state.update { it.copy(pendingTwoFactor = null) }
        finishOwnAudio(tokens, pending.host, pending.discovery, pending.name, pending.replacing)
    }

    fun cancelTwoFactor() {
        _state.update { it.copy(pendingTwoFactor = null) }
    }

    /** A new account on own.audio's hosted service, then signed in to it. */
    suspend fun createAccount(name: String, email: String, password: String) {
        try {
            unsigned(hostedService).register(email.trim(), password, name.trim())
        } catch (p: OwnAudioException.Problem) {
            throw UserFacingException(
                when (p.code) {
                    "invalid_email" -> R.string.error_invalid_email
                    "weak_password" -> R.string.error_weak_password
                    "invalid_display_name" -> R.string.error_missing_name
                    "registration_closed" -> R.string.error_registration_closed
                    "email_taken" -> R.string.error_email_taken
                    else -> R.string.error_not_created
                },
                p,
            )
        } catch (e: OwnAudioException.Network) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        log.info("servers", "own.audio account created")
        signIn(hostedService, email, password, "")
    }

    /** The public demo, signed in with the account its discovery offers to anyone. */
    suspend fun addDemo() {
        val discovery = try {
            detector.client(demoServer).discover()
        } catch (e: OwnAudioException) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        val demo = discovery.demo ?: throw UserFacingException(R.string.demo_unavailable)
        signIn(demoServer, demo.email, demo.password, DEMO_NAME)
    }

    /** A Google id token for the own.audio server at [address], exchanged for the API's tokens. */
    suspend fun signInWithGoogle(address: String, idToken: String, name: String, replacing: String? = null) {
        val found = detector.detect(address, ServerKinds.OWN_AUDIO) as? Detected.OwnAudio ?: throw UserFacingException(R.string.error_not_own_audio)
        if (googleClientId(found.discovery) == null) throw UserFacingException(R.string.error_provider_off)
        val tokens = try {
            unsigned(found.host).signIn(provider = "google", idToken = idToken, device = device)
        } catch (p: OwnAudioException.Problem) {
            if (p.code == "mfa_required" && p.mfaToken != null) {
                _state.update { it.copy(pendingTwoFactor = PendingTwoFactor(found.host, p.mfaToken!!, name, found.discovery, replacing)) }
                throw TwoFactorNeeded
            }
            if (p.code == "identity_conflict") throw UserFacingException(R.string.error_identity_conflict, p)
            throw signInProblem(p)
        } catch (e: OwnAudioException.Network) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        finishOwnAudio(tokens, found.host, found.discovery, name, replacing)
    }

    /**
     * The web client id to ask Google for a token with, when the own.audio server at [address]
     * offers Google; null otherwise (and for anything that isn't own.audio).
     */
    suspend fun googleClientId(address: String): String? {
        val candidates = TypedAddress.candidates(address).filter { it.toHttpUrlOrNull() != null }
        for (host in candidates) {
            val found = detector.discovery(host) ?: continue
            return googleClientId(found)
        }
        return null
    }

    suspend fun googleClientIdFor(serverId: String): String? {
        val record = _state.value.server(serverId) ?: return null
        return detector.discovery(record.host)?.let(::googleClientId)
    }

    private fun googleClientId(discovery: Discovery): String? =
        discovery.auth.clients.google?.web?.takeIf { "google" in discovery.auth.providers && it.isNotBlank() }

    private fun signInProblem(p: OwnAudioException.Problem): UserFacingException = UserFacingException(
        when {
            p.code == "invalid_grant" || p.code == "invalid_credentials" -> R.string.error_rejected
            p.status == 429 -> R.string.error_rate_limited
            else -> R.string.error_rejected
        },
        p,
    )

    /** Saves a server signed in through own.audio's API, or puts the sign-in on the saved one. */
    private suspend fun finishOwnAudio(tokens: Tokens, host: String, discovery: Discovery, name: String, replacing: String?) {
        val probe = OwnAudioClient(baseUrl = host, http = http, userAgent = userAgent, tokens = TokenSession(tokens), acceptLanguage = acceptLanguage())
        val me = try {
            probe.me()
        } catch (e: OwnAudioException.Network) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        val learned = discovery.addresses.map { ServerAddress.of(it.url, AddressOrigin.SERVER, AddressScope.fromWire(it.scope)) }
        val record = ServerRecord(
            id = UUID.randomUUID().toString(),
            kind = ServerKinds.OWN_AUDIO,
            displayName = name.trim().ifEmpty { discovery.name.ifBlank { "own.audio" } },
            host = host.trimEnd('/'),
            auth = ServerAuth.OwnAudio(me.email, tokens.toStored()),
            serverIdentity = discovery.id,
            userId = me.id,
            addedAtEpochMs = clock.millis(),
        ).learning(learned)
        if (replacing != null) {
            replaceSignIn(replacing, record)
        } else {
            store.add(record)
            sessions.remove(record.id)
            session(record)
            _state.update { it.copy(servers = it.servers + record, activeId = record.id) }
            preferences.setActiveServerId(record.id)
            log.info("servers", "added ${record.displayName}")
            scope.launch { chooseAddress(record.id) }
        }
        _signedIn.tryEmit(replacing ?: record.id)
    }

    /** Signs in to a saved server again; it keeps its id, so downloads and addresses stay. */
    suspend fun signInAgain(serverId: String, username: String, password: String) {
        val record = _state.value.server(serverId) ?: return
        signIn(record.host, username, password, record.displayName, replacing = serverId)
    }

    suspend fun signInAgainWithGoogle(serverId: String, idToken: String) {
        val record = _state.value.server(serverId) ?: return
        signInWithGoogle(record.host, idToken, record.displayName, replacing = serverId)
    }

    private suspend fun replaceSignIn(id: String, fresh: ServerRecord) {
        val saved = _state.value.server(id) ?: return
        val same = saved.kind == fresh.kind &&
            if (saved.serverIdentity != null && fresh.serverIdentity != null) saved.serverIdentity == fresh.serverIdentity else saved.host == fresh.host
        if (!same) throw UserFacingException(R.string.error_different_server)
        val updated = saved.copy(auth = fresh.auth, serverIdentity = fresh.serverIdentity ?: saved.serverIdentity, userId = fresh.userId)
            .learning(fresh.addresses.filter { it.origin == AddressOrigin.SERVER })
        // A new session for the new tokens; the old one would refresh with a dead token.
        sessions.remove(id)
        store.update(updated, signIn = true)
        reload()
        _state.update { it.copy(checks = it.checks - id) }
        log.info("servers", "signed in again to ${saved.displayName}")
        scope.launch { chooseAddress(id) }
    }

    // endregion

    // region Changing and removing

    suspend fun select(id: String) {
        if (_state.value.server(id) == null) return
        _state.update { it.copy(activeId = id) }
        preferences.setActiveServerId(id)
    }

    suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.rename(id, trimmed)
        reload()
    }

    /** Saves a change to a record's name, addresses or connection; its tokens stay as stored. */
    suspend fun update(record: ServerRecord) {
        store.update(record)
        reload()
        chooseAddress(record.id)
    }

    suspend fun addAddress(id: String, typed: String) {
        val record = _state.value.server(id) ?: return
        val trimmed = typed.trim()
        val url = (if ("://" in trimmed) trimmed else "http://$trimmed").trimEnd('/')
        if (url.toHttpUrlOrNull() == null) throw UserFacingException(R.string.error_invalid_address)
        if (record.candidateAddresses.any { ServerAddress.normalized(it.url) == url }) return
        update(record.copy(addresses = record.addresses + ServerAddress.of(url, AddressOrigin.USER)))
    }

    suspend fun removeAddress(id: String, url: String) {
        val record = _state.value.server(id) ?: return
        val connection = (record.connection as? ConnectionMode.Fixed)?.takeIf { it.url == url }?.let { ConnectionMode.Automatic } ?: record.connection
        update(record.copy(addresses = record.addresses.filterNot { it.url == url }, connection = connection))
    }

    suspend fun setConnection(id: String, mode: ConnectionMode) {
        val record = _state.value.server(id) ?: return
        update(record.copy(connection = mode))
    }

    /** Ends the session on the server too, then forgets the server and what was kept for it. */
    suspend fun remove(id: String) {
        val client = client(id)
        if (client != null) scope.launch { runCatching { client.signOut() } }
        sessions.remove(id)
        store.remove(id)
        // Saved answers are filed by server; letting the others be fetched again is simpler than
        // picking them apart.
        clearCache()
        val remaining = store.loadAll()
        val active = _state.value.activeId.takeIf { a -> remaining.any { it.id == a } } ?: remaining.firstOrNull()?.id
        _state.update { it.copy(servers = remaining, activeId = active, connections = it.connections - id, checks = it.checks - id) }
        preferences.setActiveServerId(active)
        log.info("servers", "removed a server")
    }

    /**
     * Deletes the own.audio account, then the server from this device. A password account
     * confirms with its password; one that signs in with Google sends none.
     */
    suspend fun deleteAccount(id: String, password: String?) {
        val client = client(id) ?: return
        try {
            client.deleteAccount(password?.takeIf { it.isNotEmpty() })
        } catch (p: OwnAudioException.Problem) {
            throw UserFacingException(
                when (p.code) {
                    "last_admin" -> R.string.delete_account_last_admin
                    "demo_read_only" -> R.string.delete_account_demo
                    "password_required" -> R.string.delete_account_password_required
                    "invalid_credentials" -> R.string.delete_account_wrong_password
                    else -> R.string.delete_account_refused
                },
                p,
            )
        }
        log.info("servers", "account deleted")
        remove(id)
    }

    /** Every device out, this one too: the server then needs a fresh sign-in. */
    suspend fun signOutEverywhere(id: String) {
        val client = client(id) ?: return
        client.revokeAllSessions()
        reload()
    }

    private suspend fun reload() {
        val servers = store.loadAll()
        _state.update { it.copy(servers = servers) }
    }

    // endregion

    // region Addresses and the connection

    suspend fun chooseAddresses() = coroutineScope {
        _state.value.servers.map { async { chooseAddress(it.id) } }.awaitAll()
    }

    /**
     * Races the server's addresses and keeps the fastest that answers as the same server; a
     * fixed address is only checked. Addresses the server names are learned on the way.
     */
    suspend fun chooseAddress(id: String) {
        val record = _state.value.server(id) ?: return
        if (record.kind != ServerKinds.OWN_AUDIO) return
        _state.update { it.copy(connections = it.connections + (id to ConnectionState.Checking)) }
        val candidates = when (val mode = record.connection) {
            is ConnectionMode.Fixed -> listOf(mode.url)
            ConnectionMode.Automatic -> record.candidateAddresses.map { it.url }
        }
        val winner = AddressRace.race(record, candidates, record.serverIdentity) { url -> check(url) }
        if (winner == null) {
            log.error("servers", "no address of ${record.displayName} answers")
            _state.update { it.copy(connections = it.connections + (id to ConnectionState.Unreachable)) }
            return
        }
        addressMemory.set(id, network.value.signature, winner.url)
        _state.update { it.copy(connections = it.connections + (id to ConnectionState.Connected(winner.url))) }
        val learned = record.learning(winner.addresses)
        val adopted = if (winner.serverIdentity != null && winner.serverIdentity != record.serverIdentity) learned.copy(serverIdentity = winner.serverIdentity) else learned
        if (adopted != record) {
            store.update(adopted)
            reload()
        }
    }

    private suspend fun check(url: String): AddressCheck? = withTimeoutOrNull(AddressRace.TIMEOUT_MS) {
        val found = detector.discovery(url) ?: return@withTimeoutOrNull null
        AddressCheck(url, found.id, found.addresses.map { ServerAddress.of(it.url, AddressOrigin.SERVER, AddressScope.fromWire(it.scope)) })
    }

    /** Picks the address again, then asks the server whether it accepts the sign-in. */
    suspend fun checkNow(id: String) {
        if (id in _state.value.checking) return
        _state.update { it.copy(checking = it.checking + id) }
        try {
            chooseAddress(id)
            val status = if (_state.value.connections[id] is ConnectionState.Unreachable) {
                ConnectionCheck.Status.UNREACHABLE
            } else {
                try {
                    client(id)?.me()
                    ConnectionCheck.Status.OK
                } catch (e: OwnAudioException.SignInRefused) {
                    ConnectionCheck.Status.REJECTED
                } catch (e: OwnAudioException.SignedOut) {
                    ConnectionCheck.Status.REJECTED
                } catch (p: OwnAudioException.Problem) {
                    if (p.status == 401) ConnectionCheck.Status.REJECTED else ConnectionCheck.Status.UNREACHABLE
                } catch (e: OwnAudioException) {
                    ConnectionCheck.Status.UNREACHABLE
                }
            }
            _state.update { it.copy(checks = it.checks + (id to ConnectionCheck(status, clock.instant()))) }
        } finally {
            _state.update { it.copy(checking = it.checking - id) }
        }
    }

    /** Work that must finish even when the screen that asked for it closes. */
    fun scopeLaunch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { log.error("servers", "background work failed: ${it.message}") } }
    }

    /** Whether the server's token session gave up (for screens that want to know at once). */
    fun signInState(id: String): SignInState? = _state.value.server(id)?.let { session(it).state.value }

    // endregion

    companion object {
        /** own.audio's hosted service, where someone without a server can make an account. */
        const val HOSTED_SERVICE = "https://api.own.audio"

        /** The public demo; its discovery offers a shared account. */
        const val DEMO_SERVER = "https://demopi.own.audio"
        const val DEMO_NAME = "own.audio demo"
        const val NETWORK_SETTLE_MS = 3_000L
    }
}

/** Not a failure: the code is asked for next ([ServersState.pendingTwoFactor]). */
object TwoFactorNeeded : Exception("two-factor code needed") {
    private fun readResolve(): Any = TwoFactorNeeded
}

internal fun Tokens.toStored() = StoredTokens(accessToken, refreshToken, expiresAt.toEpochMilli())

internal fun StoredTokens.toTokens() = Tokens(access, refresh ?: "", Instant.ofEpochMilli(expiresAtEpochMs ?: 0))
