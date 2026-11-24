package audio.own.podcastplayer

import audio.own.core.servers.InMemoryAddressMemory
import audio.own.core.servers.NetworkState
import audio.own.core.servers.SecretBox
import audio.own.core.servers.ServerStore
import audio.own.kit.ownaudio.Grants
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.Pkce
import audio.own.kit.ownaudio.TokenSessions
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.MemoryPreferences
import audio.own.podcastplayer.servers.ServerDetector
import audio.own.podcastplayer.servers.ServersModel
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * A pretend own.audio server: discovery, sign-in (password, two-factor, Google), the account,
 * household, sessions and statistics, answering as the real one does. Change its fields to make
 * it answer differently.
 */
class FakeOwnAudio : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    var identity = "srv-1"
    var revision = 17
    var google = true
    /** Sign in with Apple in the browser, advertised in discovery. */
    var appleWeb = true
    /** One-time Apple codes the browser could bring back, each with the PKCE challenge it was made for. */
    val appleCodes: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    var demo: Pair<String, String>? = null
    var password = "right password"
    var twoFactorFor: String? = null
    var deleteProblem: String? = null
    var meStatus = 200
    var refreshRefused = false
    var role = "admin"
    var extra: (RecordedRequest) -> MockResponse? = { null }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return extra(request) ?: answer(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path == "/.well-known/own-audio" -> json(discovery())
            path == "/api/v2/auth/token" -> token(form(request))
            path == "/api/v2/auth/revoke" -> json("{}")
            path == "/api/v2/auth/register" -> json(ME)
            path == "/api/v2/me" && request.method == "GET" -> if (meStatus == 200) json(ME.replace("\"admin\"", "\"$role\"")) else problem("unauthorized", meStatus)
            path == "/api/v2/me" && request.method == "DELETE" -> deleteProblem?.let { problem(it, if (it == "last_admin") 409 else 403) } ?: MockResponse.Builder().code(204).build()
            path == "/api/v2/me/sessions" && request.method == "GET" -> json(SESSIONS)
            path.startsWith("/api/v2/me/sessions") -> MockResponse.Builder().code(204).build()
            path == "/api/v2/family" -> json(FAMILY.replace("\"role\":\"admin\"", "\"role\":\"$role\""))
            path == "/api/v2/family/members" -> json(MEMBERS)
            path == "/api/v2/family/storage" -> json("""{"total_bytes":3000,"music_bytes":2000,"audiobooks_bytes":1000,"podcasts_bytes":0}""")
            path == "/api/v2/family/billing" -> problem("feature_unavailable", 501)
            path == "/api/v2/family/invites" && request.method == "GET" -> json("[]")
            path == "/api/v2/family/invites" && request.method == "POST" -> json(INVITE)
            path.startsWith("/api/v2/family/invites/") -> MockResponse.Builder().code(204).build()
            path == "/api/v2/family/join" -> json(FAMILY.replace("Home", "Other"))
            path == "/api/v2/me/stats" -> json(STATS)
            path == "/api/v2/family/stats" -> json("""[{"user_id":"u1","listened_ms":600000}]""")
            path == "/api/v2/me/settings" -> json("""{"stats_visible_to_admin":${request.method == "PATCH"}}""")
            path.startsWith("/api/v2/tracks/") -> json(TRACK)
            path.startsWith("/api/v2/books/") -> problem("not_found", 404)
            else -> problem("not_found", 404)
        }
    }

    private fun discovery(): String {
        val providers = if (google) "[\"google\"]" else "[]"
        val clients = if (google) "{\"google\":{\"web\":\"web-client-id\"}}" else "{}"
        val demoPart = demo?.let { ",\"demo\":{\"email\":\"${it.first}\",\"password\":\"${it.second}\"}" } ?: ""
        return """{"id":"$identity","name":"own.audio","edition":"foss","version":"1.0.5",
            "api":[{"version":2,"path":"/api/v2","revision":$revision,"status":"stable"}],
            "auth":{"password":true,"providers":$providers,"grants":["password","refresh_token"],"clients":$clients,"apple_web":$appleWeb},
            "modules":{},"addresses":[{"url":"http://192.168.1.10:8080","scope":"lan"}]$demoPart}"""
    }

    private fun token(form: Map<String, String>): MockResponse = when (form["grant_type"]) {
        "password" -> when {
            form["password"] != password -> problem("invalid_grant", 400)
            form["username"] == twoFactorFor -> problem("mfa_required", 403, ",\"mfa_token\":\"mfa-1\",\"mfa_methods\":[\"totp\"]")
            else -> json(TOKENS)
        }
        "urn:own-audio:params:oauth:grant-type:mfa-otp" -> if (form["otp"] == "123456") json(TOKENS) else problem("invalid_grant", 400)
        "urn:ietf:params:oauth:grant-type:jwt-bearer" -> if (form["assertion"] == "good-id-token") json(TOKENS) else problem("identity_conflict", 409)
        Grants.APPLE_CODE -> {
            val challenge = form["code"]?.let { appleCodes.remove(it) }
            when {
                challenge == null || form["code_verifier"]?.let(Pkce::challenge) != challenge -> problem("invalid_grant", 400)
                form["code"] == MFA_APPLE_CODE -> problem("mfa_required", 403, ",\"mfa_token\":\"mfa-1\",\"mfa_methods\":[\"totp\"]")
                else -> json(TOKENS)
            }
        }
        "refresh_token" -> if (refreshRefused) problem("invalid_grant", 400) else json(TOKENS)
        else -> problem("unsupported_grant_type", 400)
    }

    override fun close() = server.close()

    companion object {
        /** An Apple code whose account has two-factor sign-in. */
        const val MFA_APPLE_CODE = "apple-mfa"
        const val TOKENS = """{"access_token":"a1","token_type":"Bearer","expires_in":3600,"refresh_token":"r1","scope":""}"""
        const val ME = """{"id":"u1","email":"a@example.com","display_name":"Alex","family":{"id":"f1","role":"admin","can_upload":true}}"""
        const val FAMILY = """{"id":"f1","name":"Home","me":{"user_id":"u1","role":"admin"}}"""
        const val MEMBERS = """[{"user_id":"u2","display_name":"Sam","role":"member"},{"user_id":"u1","display_name":"Alex","role":"admin"},{"user_id":"u3","display_name":"Kim","pending":true}]"""
        const val INVITE = """{"id":"i1","kind":"link","code":"ABCD-1234","expires_at":"2026-12-01T00:00:00Z","join_url":"https://example.com/join/ABCD-1234"}"""
        const val SESSIONS = """[
            {"id":"s1","device_kind":"ios","device_name":"iPhone","signed_in_at":"2026-11-01T10:00:00Z","last_used_at":"2026-11-20T10:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":false},
            {"id":"s2","device_kind":"android","device_name":"Pixel","signed_in_at":"2026-11-02T10:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":true},
            {"id":"s3","device_kind":"web","signed_in_at":"2026-11-03T10:00:00Z","last_used_at":"2026-11-21T09:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":false}]"""
        const val STATS = """{"from":"2026-10-22T00:00:00Z","to":"2026-11-21T00:00:00Z","group":"day","listened_ms":7800000,"plays":12,
            "by_kind":[{"kind":"track","listened_ms":7800000,"plays":12}],
            "buckets":[{"start":"2026-11-20","listened_ms":3600000},{"start":"2026-11-21","listened_ms":4200000}],
            "top":[{"kind":"track","id":"t1","listened_ms":3600000,"plays":5},{"kind":"book","id":"b9","listened_ms":60000,"plays":1}],
            "streak_days":4,"finished_books":0}"""
        const val TRACK = """{"id":"t1","title":"One","credits":[],"genre_ids":[],"duration_ms":1000,"visibility":"family","owner_id":"u1","created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""

        fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

        fun problem(code: String, status: Int, extra: String = ""): MockResponse =
            json("""{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"$extra}""", status, "application/problem+json")

        fun form(request: RecordedRequest): Map<String, String> = (request.body?.utf8() ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

/** Stands in for the Keystore: the bytes as they are. */
object PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

/** A [ServersModel] wired to temporary files and the given servers. */
class ModelHarness(hosted: String = "http://127.0.0.1:9", demo: String = "http://127.0.0.1:9") : AutoCloseable {
    val dir: File = Files.createTempDirectory("servers").toFile()
    val store = ServerStore(File(dir, "servers.bin"), PlainBox)
    val log = AppLog(null, echo = false)
    val http = OkHttpClient()
    val preferences = MemoryPreferences()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var cleared = 0
    val model = ServersModel(
        store = store,
        sessions = TokenSessions(),
        http = http,
        userAgent = "own.audio Podcasts/1.0 (Android 16; Test)",
        device = OwnAudioClient.Device("Test phone"),
        preferences = preferences,
        detector = ServerDetector(http, "own.audio Podcasts/1.0 (Android 16; Test)", log, timeoutMs = 3_000),
        addressMemory = InMemoryAddressMemory(),
        network = MutableStateFlow(NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi")),
        cache = null,
        clearCache = { cleared++ },
        isOffline = { false },
        acceptLanguage = { "en" },
        log = log,
        scope = scope,
        hostedService = hosted,
        demoServer = demo,
    )

    init {
        runBlocking { model.load() }
    }

    override fun close() {
        dir.deleteRecursively()
    }
}

/** Waits, in real time, until [condition] holds: the servers answer on their own threads. */
suspend fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    withTimeout(timeoutMs) { while (!condition()) delay(10) }
}

/** View models run on Dispatchers.Main; tests give it one plain thread. */
fun mainThread() = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

/** Downloads that only remember what they were asked; [finish] makes one done. */
class FakeDownloader : audio.own.podcastplayer.downloads.EpisodeDownloader {
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val _state = MutableStateFlow(audio.own.podcastplayer.downloads.DownloadsState(loaded = true))
    override val state: kotlinx.coroutines.flow.StateFlow<audio.own.podcastplayer.downloads.DownloadsState> = _state
    var clock = 1_000L

    fun put(entry: audio.own.podcastplayer.downloads.EpisodeDownload) {
        _state.value = _state.value.copy(entries = _state.value.entries + (entry.id to entry))
    }

    fun finish(id: String, bytes: Long, lastPlayedAt: Long? = null) {
        val entry = _state.value.entries.getValue(id)
        put(entry.copy(status = audio.own.podcastplayer.downloads.EpisodeDownload.Status.DONE, bytes = bytes, finishedAt = clock++, lastPlayedAt = lastPlayedAt))
    }

    override fun localFile(id: String): android.net.Uri? = null

    override suspend fun download(item: audio.own.podcast.library.SourcedEpisode, show: audio.own.podcast.library.Show?, automatic: Boolean) {
        calls += "download ${item.id}" + if (automatic) " auto" else ""
        if (item.id in _state.value.entries) return
        put(
            audio.own.podcastplayer.downloads.EpisodeDownload(
                id = item.id,
                record = audio.own.podcastplayer.downloads.DownloadedEpisode(item.source.id, item.episode, show, automatic),
                status = audio.own.podcastplayer.downloads.EpisodeDownload.Status.DOWNLOADING,
                queuedAt = clock++,
            ),
        )
    }

    override suspend fun remove(ids: Collection<String>) {
        for (id in ids) {
            calls += "remove $id"
            _state.value = _state.value.copy(entries = _state.value.entries - id)
        }
    }

    override suspend fun removeAll(source: String?) {
        calls += "removeAll $source"
        _state.value = _state.value.copy(entries = _state.value.entries.filterValues { source != null && it.record.source != source })
    }

    override suspend fun retryFailed() {
        calls += "retryFailed"
    }

    override suspend fun notePlayed(id: String) {
        calls += "played $id"
    }

    override suspend fun wifiOnlyChanged() {
        calls += "wifiOnly"
    }
}
