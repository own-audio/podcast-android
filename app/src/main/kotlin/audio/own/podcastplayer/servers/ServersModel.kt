package audio.own.podcastplayer.servers

import audio.own.core.servers.AddressCheck
import audio.own.core.servers.AddressMemory
import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.AddressRace
import audio.own.core.servers.AddressScope
import audio.own.core.servers.ConnectionMode
import audio.own.core.servers.NetworkState
import audio.own.core.servers.Reachability
import audio.own.core.servers.ServerAddress
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerLibraryRef
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.ServerStore
import audio.own.core.servers.StoredTokens
import audio.own.core.servers.TypedAddress
import audio.own.core.servers.UnreachableRetry
import audio.own.core.strings.R
import audio.own.podcastplayer.R as AppR
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfError
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.NetworkLog
import audio.own.kit.audiobookshelf.me as absMe
import audio.own.kit.audiobookshelf.podcastLibraries
import audio.own.kit.gpodder.GPodderClient
import audio.own.kit.gpodder.GPodderException
import audio.own.kit.ownaudio.AnswerCache
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.Pkce
import audio.own.kit.ownaudio.SignInState
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.TokenSessions
import audio.own.kit.ownaudio.TokenStore
import audio.own.kit.ownaudio.Tokens
import audio.own.kit.ownaudio.appleStartUrl
import audio.own.kit.ownaudio.deleteAccount
import audio.own.kit.ownaudio.discover
import audio.own.kit.ownaudio.finishTwoFactor
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.register
import audio.own.kit.ownaudio.revokeAllSessions
import audio.own.kit.ownaudio.signIn
import audio.own.kit.ownaudio.signInWithApple
import audio.own.kit.ownaudio.signOut
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.AppPreferences
import audio.own.podcastplayer.app.ServerExtras
import audio.own.podcastplayer.app.UserFacingException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

private val ConnectionState.reachability: Reachability get() = when (this) {
    ConnectionState.Checking -> Reachability.CHECKING
    is ConnectionState.Connected -> Reachability.REACHABLE
    ConnectionState.Unreachable -> Reachability.UNREACHABLE
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
    /** What the app keeps beside each record: podfetch, may add shows, hidden libraries, last sync. */
    val extras: Map<String, ServerExtras> = emptyMap(),
    /** The own.audio modules each server has on, learned with its address. */
    val modules: Map<String, Set<String>> = emptyMap(),
) {
    fun extras(id: String): ServerExtras = extras[id] ?: ServerExtras()

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
    /** Whether the app is on screen: unreachable servers are asked again only then. */
    private val foreground: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _state = MutableStateFlow(ServersState())
    val state: StateFlow<ServersState> = _state.asStateFlow()

    private val retry = UnreachableRetry(
        scope = scope,
        foreground = foreground,
        network = network,
        reachability = state.map { s -> s.connections.mapValues { (_, c) -> c.reachability } }.distinctUntilChanged(),
        canAsk = { !isOffline() },
    ) { id ->
        chooseAddress(id)
        _state.value.connections[id] is ConnectionState.Connected
    }

    private val _signedIn = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A server's id each time a sign-in finishes, two-factor included: the sign-in screens close. */
    val signedIn: SharedFlow<String> = _signedIn.asSharedFlow()

    suspend fun load() {
        val servers = store.loadAll()
        val saved = preferences.activeServerId()
        val active = saved?.takeIf { id -> servers.any { it.id == id } } ?: servers.firstOrNull()?.id
        val extras = preferences.serverExtras.first()
        _state.update { it.copy(loaded = true, servers = servers, activeId = active, extras = extras) }
        scope.launch { preferences.serverExtras.collect { held -> _state.update { it.copy(extras = held) } } }
        log.info("servers", "${servers.size} server(s) saved")
        scope.launch { chooseAddresses() }
        // After a network change settles, the fastest address may be another one.
        scope.launch {
            network.drop(1).distinctUntilChangedBy { it.signature }.debounce(NETWORK_SETTLE_MS).collect {
                if (it.connected) chooseAddresses()
            }
        }
        retry.start()
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

    private val absSessions = ConcurrentHashMap<String, audio.own.kit.audiobookshelf.TokenSession>()

    /** Audiobookshelf's HTTP side, shared by every client: its failures go to the app's log. */
    private val absHttp = AudiobookshelfHttp(http, userAgent, log = NetworkLog { log.error("audiobookshelf", it) })

    private val absCache = cache?.let { answers ->
        object : audio.own.kit.audiobookshelf.AnswerCache {
            override fun data(key: String): ByteArray? = answers.get("abs|$key")

            override fun store(data: ByteArray, key: String) = answers.put("abs|$key", data)
        }
    }

    /** Audiobookshelf's client for a saved server, at the address in use; null for other kinds. */
    fun audiobookshelf(serverId: String): AudiobookshelfClient? {
        val record = _state.value.server(serverId) ?: return null
        if (record.kind != ServerKinds.AUDIOBOOKSHELF || record.auth !is ServerAuth.Audiobookshelf) return null
        val url = baseUrl(record).toHttpUrlOrNull() ?: return null
        return AudiobookshelfClient(url, absSession(record), absHttp, absCache, record.id, isOffline)
    }

    /** One token session per account for as long as the app runs: its refresh token is single-use. */
    private fun absSession(record: ServerRecord) = absSessions.getOrPut(record.id) {
        val stored = (record.auth as? ServerAuth.Audiobookshelf)?.tokens
        val keeper = audio.own.kit.audiobookshelf.TokenStore { tokens ->
            store.updateTokens(record.id, tokens?.toStored())
            if (tokens == null) log.error("servers", "Audiobookshelf refused the saved sign-in")
            reload()
        }
        audio.own.kit.audiobookshelf.TokenSession(stored?.toAbsTokens(), keeper)
    }

    /** The gPodder client for a saved account, at the address in use; null for other kinds. */
    fun gpodder(serverId: String): GPodderClient? {
        val record = _state.value.server(serverId) ?: return null
        val auth = record.auth as? ServerAuth.GPodder ?: return null
        return GPodderClient(baseUrl(record), auth.userName, auth.password, http, userAgent)
    }

    /** The address requests go to now: the one chosen on this network, the fixed one, or the one added. */
    fun address(serverId: String): String? = _state.value.server(serverId)?.let(::baseUrl)

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
        val found = detector.detect(address, kind)
        // Refused before signing in, so the server gets no session and asks for no code.
        if (replacing == null) refuseIfAdded(found.kind, found.host, (found as? Detected.OwnAudio)?.discovery?.id, username)
        when (found) {
            is Detected.Audiobookshelf -> finishAudiobookshelf(found.host, username, password, name, replacing)
            is Detected.GPodder -> finishGPodder(found.host, found.software, username, password, name, replacing)
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
    suspend fun googleClientId(address: String): String? = signInOptions(address).googleClientId

    suspend fun googleClientIdFor(serverId: String): String? = signInOptionsFor(serverId).googleClientId

    /** The sign-ins besides a password that the own.audio server at [address] offers, from one look at its discovery. */
    suspend fun signInOptions(address: String): SignInOptions {
        val candidates = TypedAddress.candidates(address).filter { it.toHttpUrlOrNull() != null }
        for (host in candidates) {
            val found = detector.discovery(host) ?: continue
            return signInOptions(found)
        }
        return SignInOptions()
    }

    suspend fun signInOptionsFor(serverId: String): SignInOptions {
        val record = _state.value.server(serverId) ?: return SignInOptions()
        return detector.discovery(record.host)?.let(::signInOptions) ?: SignInOptions()
    }

    private fun signInOptions(discovery: Discovery) = SignInOptions(googleClientId(discovery), discovery.supportsAppleWeb)

    private fun googleClientId(discovery: Discovery): String? =
        discovery.auth.clients.google?.web?.takeIf { "google" in discovery.auth.providers && it.isNotBlank() }

    // region Sign in with Apple, through the browser

    /**
     * The own.audio server at [address] and its page that starts Sign in with Apple, for the
     * browser. Nothing is saved until [signInWithApple] gets the code back.
     */
    suspend fun appleStart(address: String, redirectUri: String, pkce: Pkce, state: String): AppleStart {
        val found = detector.detect(address, ServerKinds.OWN_AUDIO) as? Detected.OwnAudio ?: throw UserFacingException(R.string.error_not_own_audio)
        if (!found.discovery.supportsAppleWeb) throw UserFacingException(R.string.error_provider_off)
        return AppleStart(found.host, unsigned(found.host).appleStartUrl(redirectUri, pkce.challenge, state).toString())
    }

    /** The one-time code the browser brought back from [appleStart]'s [host], exchanged for the API's tokens. */
    suspend fun signInWithApple(host: String, code: String, codeVerifier: String, name: String, replacing: String? = null) {
        val found = detector.detect(host, ServerKinds.OWN_AUDIO) as? Detected.OwnAudio ?: throw UserFacingException(R.string.error_not_own_audio)
        val tokens = try {
            unsigned(found.host).signInWithApple(code, codeVerifier, device)
        } catch (p: OwnAudioException.Problem) {
            if (p.code == "mfa_required" && p.mfaToken != null) {
                _state.update { it.copy(pendingTwoFactor = PendingTwoFactor(found.host, p.mfaToken!!, name, found.discovery, replacing)) }
                throw TwoFactorNeeded
            }
            if (p.code == "identity_conflict") throw UserFacingException(R.string.error_identity_conflict, p)
            // The code lives a few minutes and works once: a fresh start gets a new one.
            if (p.code == "invalid_grant") throw UserFacingException(R.string.error_apple_expired, p)
            throw signInProblem(p)
        } catch (e: OwnAudioException.Network) {
            throw UserFacingException(R.string.error_unreachable, e)
        }
        finishOwnAudio(tokens, found.host, found.discovery, name, replacing)
    }

    // endregion

    private fun signInProblem(p: OwnAudioException.Problem): UserFacingException = UserFacingException(
        when {
            p.code == "invalid_grant" || p.code == "invalid_credentials" -> R.string.error_rejected
            p.code == "registration_closed" -> R.string.error_registration_closed
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
        saveSignedIn(record, null, replacing) { probe.signOut() }
    }

    /**
     * Signs in to Audiobookshelf; the account must see at least one podcast library. Whether it
     * may upload says whether shows can be added there.
     */
    private suspend fun finishAudiobookshelf(host: String, username: String, password: String, name: String, replacing: String?) {
        val url = host.toHttpUrlOrNull() ?: throw UserFacingException(R.string.error_invalid_address)
        val signIn = try {
            AudiobookshelfClient.signIn(url, username.trim(), password, absHttp)
        } catch (e: AudiobookshelfError) {
            throw absProblem(e)
        }
        val libraries = try {
            AudiobookshelfClient(url, audio.own.kit.audiobookshelf.TokenSession(signIn.tokens), absHttp).podcastLibraries()
        } catch (e: AudiobookshelfError) {
            throw absProblem(e)
        }
        if (libraries.isEmpty()) throw UserFacingException(AppR.string.error_no_podcast_library)
        val record = ServerRecord(
            id = UUID.randomUUID().toString(),
            kind = ServerKinds.AUDIOBOOKSHELF,
            displayName = name.trim().ifEmpty { url.host },
            host = host.trimEnd('/'),
            auth = ServerAuth.Audiobookshelf(signIn.user.username, signIn.tokens.toStored()),
            libraries = libraries.sortedBy { it.displayOrder ?: 0 }.map { ServerLibraryRef(it.id, it.name) },
            userId = signIn.user.id,
            addedAtEpochMs = clock.millis(),
        )
        saveSignedIn(record, ServerExtras(canAddShows = signIn.user.permissions.upload), replacing)
    }

    private fun absProblem(e: AudiobookshelfError) = UserFacingException(
        when (e) {
            AudiobookshelfError.InvalidCredentials, AudiobookshelfError.SignedOut -> R.string.error_rejected
            is AudiobookshelfError.RateLimited -> R.string.error_rate_limited
            is AudiobookshelfError.Network, AudiobookshelfError.Offline -> R.string.error_unreachable
            is AudiobookshelfError.Http -> if (e.status == 401 || e.status == 403) R.string.error_rejected else R.string.error_generic
            AudiobookshelfError.Decoding -> R.string.error_unreadable
        },
        e,
    )

    /**
     * Signs in to a gPodder server and registers this device on the account; the password is
     * kept, as the protocol signs every request with it.
     */
    private suspend fun finishGPodder(host: String, software: String?, username: String, password: String, name: String, replacing: String?) {
        val user = username.trim()
        val device = gpodderDevice()
        val client = GPodderClient(host, user, password, http, userAgent)
        try {
            client.login()
            client.registerDevice(device, this.device.name, "mobile")
        } catch (e: GPodderException) {
            throw gpodderProblem(e)
        }
        val record = ServerRecord(
            id = UUID.randomUUID().toString(),
            kind = ServerKinds.GPODDER,
            displayName = name.trim().ifEmpty { host.toHttpUrlOrNull()?.host ?: host },
            host = host.trimEnd('/'),
            auth = ServerAuth.GPodder(user, password, device),
            addedAtEpochMs = clock.millis(),
        )
        saveSignedIn(record, ServerExtras(software = software), replacing)
    }

    /** This install's device on gPodder accounts: the same on every account, for the life of the install. */
    suspend fun gpodderDevice(): String = "own-audio-podcasts-" + preferences.installId().replace("-", "").take(8)

    /**
     * Saves a newly signed-in server, or puts the sign-in on the saved one it replaces. A new one
     * the app already has is refused, and [abandon] ends the session the sign-in started.
     */
    private suspend fun saveSignedIn(record: ServerRecord, extras: ServerExtras?, replacing: String?, abandon: suspend () -> Unit = {}) {
        if (replacing == null) {
            try {
                refuseIfAdded(record.kind, record.host, record.serverIdentity, record.auth.userName)
            } catch (e: UserFacingException) {
                scope.launch { runCatching { abandon() } }
                throw e
            }
        }
        if (replacing != null) {
            replaceSignIn(replacing, record)
            if (extras != null) {
                val held = _state.value.extras(replacing)
                preferences.setServerExtras(replacing, held.copy(software = extras.software ?: held.software, canAddShows = extras.canAddShows))
            }
        } else {
            store.add(record)
            sessions.remove(record.id)
            absSessions.remove(record.id)
            if (extras != null) preferences.setServerExtras(record.id, extras)
            _state.update { it.copy(servers = it.servers + record, activeId = record.id, extras = if (extras != null) it.extras + (record.id to extras) else it.extras) }
            preferences.setActiveServerId(record.id)
            log.info("servers", "added ${record.displayName}")
            scope.launch { chooseAddress(record.id) }
        }
        _signedIn.tryEmit(replacing ?: record.id)
    }

    /**
     * Refuses a second copy of an account the app has: the same kind, the same server (its
     * identity, or its address for a kind without one) and the same user. Another user on that
     * server is allowed; signing in again goes through the saved server's page.
     */
    private fun refuseIfAdded(kind: String, host: String, identity: String?, userName: String) {
        val user = userName.trim()
        val added = _state.value.servers.any { saved ->
            saved.kind == kind &&
                saved.auth.userName.trim().equals(user, ignoreCase = true) &&
                if (saved.serverIdentity != null && identity != null) {
                    saved.serverIdentity == identity
                } else {
                    (listOf(saved.host) + saved.addresses.map { it.url }).any { addressKey(it) == addressKey(host) }
                }
        }
        if (added) throw UserFacingException(AppR.string.error_already_added)
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
        val updated = saved.copy(
            auth = fresh.auth,
            serverIdentity = fresh.serverIdentity ?: saved.serverIdentity,
            userId = fresh.userId ?: saved.userId,
            libraries = fresh.libraries ?: saved.libraries,
        ).learning(fresh.addresses.filter { it.origin == AddressOrigin.SERVER })
        // A new session for the new tokens; the old one would refresh with a dead token.
        sessions.remove(id)
        absSessions.remove(id)
        store.update(updated, signIn = true)
        reload()
        _state.update { it.copy(checks = it.checks - id) }
        log.info("servers", "signed in again to ${saved.displayName}")
        scope.launch { chooseAddress(id) }
    }

    // endregion

    // region Changing and removing

    /**
     * The listener is about to use the server (its page, switching to it): if it didn't answer,
     * it is asked again now rather than at the next step of the backoff.
     */
    fun aboutToUse(id: String) = retry.nudge(id)

    suspend fun select(id: String) {
        if (_state.value.server(id) == null) return
        retry.nudge(id)
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
        val abs = audiobookshelf(id)
        if (abs != null) scope.launch { runCatching { abs.signOut() } }
        sessions.remove(id)
        absSessions.remove(id)
        store.remove(id)
        preferences.setServerExtras(id, null)
        // Saved answers are filed by server; letting the others be fetched again is simpler than
        // picking them apart.
        clearCache()
        val remaining = store.loadAll()
        val active = _state.value.activeId.takeIf { a -> remaining.any { it.id == a } } ?: remaining.firstOrNull()?.id
        _state.update { it.copy(servers = remaining, activeId = active, connections = it.connections - id, checks = it.checks - id, extras = it.extras - id, modules = it.modules - id) }
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

    /** An Audiobookshelf library shown or hidden in New Episodes, Shows and the switch. */
    suspend fun setLibraryHidden(id: String, libraryId: String, hidden: Boolean) {
        val held = _state.value.extras(id)
        val next = held.copy(hiddenLibraries = if (hidden) held.hiddenLibraries + libraryId else held.hiddenLibraries - libraryId)
        _state.update { it.copy(extras = it.extras + (id to next)) }
        preferences.setServerExtras(id, next)
    }

    /** The podcast libraries the server lists now, kept on its record when they changed. */
    suspend fun updateLibraries(id: String, libraries: List<ServerLibraryRef>) {
        val record = _state.value.server(id) ?: return
        if (record.libraries == libraries) return
        store.update(record.copy(libraries = libraries))
        reload()
    }

    /** A gPodder account's sync finished. */
    suspend fun noteSynced(id: String, atEpochMs: Long) {
        if (_state.value.server(id) == null) return
        val next = _state.value.extras(id).copy(lastSyncedAtMs = atEpochMs)
        _state.update { it.copy(extras = it.extras + (id to next)) }
        preferences.setServerExtras(id, next)
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
        _state.update { it.copy(connections = it.connections + (id to ConnectionState.Checking)) }
        val candidates = when (val mode = record.connection) {
            is ConnectionMode.Fixed -> listOf(mode.url)
            ConnectionMode.Automatic -> record.candidateAddresses.map { it.url }
        }
        val discoveries = ConcurrentHashMap<String, Discovery>()
        val identity = if (record.kind == ServerKinds.OWN_AUDIO) record.serverIdentity else null
        val winner = AddressRace.race(record, candidates, identity) { url ->
            when (record.kind) {
                ServerKinds.OWN_AUDIO -> check(url, discoveries)
                ServerKinds.AUDIOBOOKSHELF -> withTimeoutOrNull(AddressRace.TIMEOUT_MS) { AddressCheck(url, null).takeIf { detector.answersAsAudiobookshelf(url) } }
                else -> withTimeoutOrNull(AddressRace.TIMEOUT_MS) { AddressCheck(url, null).takeIf { detector.answersAsGPodder(url) } }
            }
        }
        if (winner == null) {
            log.error("servers", "no address of ${record.displayName} answers")
            _state.update { it.copy(connections = it.connections + (id to ConnectionState.Unreachable)) }
            return
        }
        addressMemory.set(id, network.value.signature, winner.url)
        // The modules arrive with the address, so a source built for it is built once, complete.
        val modules = discoveries[winner.url]?.let { found -> found.modules.keys.filter(found::has).toSet() }
        _state.update {
            it.copy(
                connections = it.connections + (id to ConnectionState.Connected(winner.url)),
                modules = if (modules != null) it.modules + (id to modules) else it.modules,
            )
        }
        val learned = record.learning(winner.addresses)
        val adopted = if (winner.serverIdentity != null && winner.serverIdentity != record.serverIdentity) learned.copy(serverIdentity = winner.serverIdentity) else learned
        if (adopted != record) {
            store.update(adopted)
            reload()
        }
    }

    private suspend fun check(url: String, discoveries: MutableMap<String, Discovery>): AddressCheck? = withTimeoutOrNull(AddressRace.TIMEOUT_MS) {
        val found = detector.discovery(url) ?: return@withTimeoutOrNull null
        discoveries[url] = found
        AddressCheck(url, found.id, found.addresses.map { ServerAddress.of(it.url, AddressOrigin.SERVER, AddressScope.fromWire(it.scope)) })
    }

    /** Picks the address again, then asks the server whether it accepts the sign-in. */
    suspend fun checkNow(id: String) {
        if (id in _state.value.checking) return
        _state.update { it.copy(checking = it.checking + id) }
        try {
            chooseAddress(id)
            val kind = _state.value.server(id)?.kind
            val status = if (_state.value.connections[id] is ConnectionState.Unreachable) {
                ConnectionCheck.Status.UNREACHABLE
            } else if (kind == ServerKinds.AUDIOBOOKSHELF) {
                try {
                    audiobookshelf(id)?.absMe()
                    ConnectionCheck.Status.OK
                } catch (e: AudiobookshelfError) {
                    val refused = e is AudiobookshelfError.SignedOut || e is AudiobookshelfError.InvalidCredentials || (e is AudiobookshelfError.Http && e.status == 401)
                    if (refused) ConnectionCheck.Status.REJECTED else ConnectionCheck.Status.UNREACHABLE
                }
            } else if (kind == ServerKinds.GPODDER) {
                try {
                    gpodder(id)?.login()
                    ConnectionCheck.Status.OK
                } catch (e: GPodderException.Unauthorized) {
                    ConnectionCheck.Status.REJECTED
                } catch (e: GPodderException) {
                    ConnectionCheck.Status.UNREACHABLE
                }
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
/** Google's web client id when the server offers Google, and whether it runs Sign in with Apple in the browser. */
data class SignInOptions(val googleClientId: String? = null, val appleWeb: Boolean = false)

/** Where a Sign in with Apple starts: the server's root as found, and the page to open in the browser. */
data class AppleStart(val host: String, val url: String)

object TwoFactorNeeded : Exception("two-factor code needed") {
    private fun readResolve(): Any = TwoFactorNeeded
}

internal fun Tokens.toStored() = StoredTokens(accessToken, refreshToken, expiresAt.toEpochMilli())

internal fun StoredTokens.toTokens() = Tokens(access, refresh ?: "", Instant.ofEpochMilli(expiresAtEpochMs ?: 0))

internal fun audio.own.kit.audiobookshelf.Tokens.toStored() =
    StoredTokens(accessToken, refreshToken, expiresAtMillis.takeIf { it != audio.own.kit.audiobookshelf.Tokens.NEVER })

internal fun StoredTokens.toAbsTokens() =
    audio.own.kit.audiobookshelf.Tokens(access, refresh, expiresAtEpochMs ?: audio.own.kit.audiobookshelf.Tokens.NEVER)

/** The server kind of what was found at an address. */
internal val Detected.kind: String
    get() = when (this) {
        is Detected.OwnAudio -> ServerKinds.OWN_AUDIO
        is Detected.Audiobookshelf -> ServerKinds.AUDIOBOOKSHELF
        is Detected.GPodder -> ServerKinds.GPODDER
    }

/**
 * What names a server in an address: its host, a port other than the scheme's own, and the path.
 * The scheme, the host's case and a trailing slash don't count.
 */
internal fun addressKey(address: String): String {
    val typed = address.trim()
    val url = (if ("://" in typed) typed else "http://$typed").toHttpUrlOrNull() ?: return typed.trimEnd('/').lowercase()
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return url.host + port + url.encodedPath.trimEnd('/')
}

/** What the listener is told when a gPodder server refuses. */
internal fun gpodderProblem(e: GPodderException): UserFacingException = UserFacingException(
    when {
        e is GPodderException.Unauthorized -> R.string.error_rejected
        // podfetch's admin account may not sync.
        e is GPodderException.Http && e.status == 409 -> AppR.string.error_podfetch_admin
        e is GPodderException.Forbidden -> R.string.error_not_allowed
        e is GPodderException.Network -> R.string.error_unreachable
        else -> R.string.error_generic
    },
    e,
)
