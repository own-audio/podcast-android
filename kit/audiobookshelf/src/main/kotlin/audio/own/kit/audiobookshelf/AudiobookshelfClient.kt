package audio.own.kit.audiobookshelf

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A client for Audiobookshelf's REST API (2.x; tokens as of 2.26).
 *
 * With [cache], reads of the library are kept and answer again offline ([isOffline]) or when
 * the server can't be reached. [cacheIdentity] is what answers are filed under: the server,
 * whichever address it is reached at.
 */
class AudiobookshelfClient(
    val host: HttpUrl,
    val tokens: TokenSession,
    val http: AudiobookshelfHttp,
    private val cache: AnswerCache? = null,
    private val cacheIdentity: String? = null,
    private val isOffline: () -> Boolean = { false },
) {
    companion object {
        /**
         * A typed address made into a URL: `host:port` and `host` get `http://`, because most
         * home servers sit on a LAN with no TLS; a full `http(s)://…` is kept.
         */
        fun urlFromTyped(text: String): HttpUrl? {
            val trimmed = text.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            return withScheme.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() }
        }

        /** What the server at [host] is, before signing in; [AudiobookshelfError.Decoding] when it is not Audiobookshelf. */
        suspend fun status(host: HttpUrl, http: AudiobookshelfHttp): ServerStatus {
            val request = http.request().url(host.under("status")).get().build()
            val status = decode(http, request, ServerStatus.serializer())
            if (status.app != "audiobookshelf") throw AudiobookshelfError.Decoding
            return status
        }

        /** Signs in with a name and password; [AudiobookshelfError.InvalidCredentials] when refused. */
        suspend fun signIn(host: HttpUrl, username: String, password: String, http: AudiobookshelfHttp): SignIn {
            val body = buildJsonObject {
                put("password", password)
                put("username", username)
            }
            val request = http.request()
                .url(host.under("login"))
                // Without this the refresh token goes into an httpOnly cookie and the body says null.
                .header("x-return-tokens", "true")
                .post(jsonBody(body))
                .build()
            try {
                return decode(http, request, SignInAnswer.serializer()).signIn()
            } catch (e: AudiobookshelfError.Http) {
                if (e.status == 401 || e.status == 403) throw AudiobookshelfError.InvalidCredentials
                throw e
            }
        }

        internal suspend fun refresh(host: HttpUrl, refreshToken: String, http: AudiobookshelfHttp): Tokens {
            val request = http.request()
                .url(host.under("auth/refresh"))
                .header("x-refresh-token", refreshToken)
                .post(EMPTY_BODY)
                .build()
            return decode(http, request, SignInAnswer.serializer()).signIn().tokens
        }

        /**
         * Reads whose answers are kept: the library and what is in it. Not search (an old
         * answer would mislead), not `api/me` or `api/authorize` (the connection check asks
         * those), not sessions.
         */
        internal fun keeps(path: String): Boolean {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.firstOrNull() != "api" || parts.size < 2) return false
            return when (parts[1]) {
                "libraries" -> parts.last() != "search"
                "items", "series", "authors", "collections" -> true
                else -> false
            }
        }

        private suspend fun <T> decode(http: AudiobookshelfHttp, request: Request, de: DeserializationStrategy<T>): T {
            val data = http.data(request)
            return decodeBytes(http, request.method, request.url.encodedPath, data, de)
        }

        internal fun <T> decodeBytes(http: AudiobookshelfHttp, method: String, path: String, data: ByteArray, de: DeserializationStrategy<T>): T =
            try {
                AbsJson.decodeFromString(de, data.decodeToString())
            } catch (e: SerializationException) {
                http.log.error("$method $path: unreadable answer: ${e.message?.take(300)}")
                throw AudiobookshelfError.Decoding
            } catch (e: IllegalArgumentException) {
                http.log.error("$method $path: unreadable answer: ${e.message?.take(300)}")
                throw AudiobookshelfError.Decoding
            }

        private val JSON = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        internal fun jsonBody(body: JsonElement): RequestBody = AbsJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON)
    }

    // ---- Signed in

    /** Ends this device's session on the server; the tokens are forgotten either way. */
    suspend fun signOut() {
        tokens.current?.let { current ->
            val request = http.request()
                .url(host.under("logout"))
                .header("Authorization", "Bearer ${current.accessToken}")
                .apply { current.refreshToken?.let { header("x-refresh-token", it) } }
                .post(EMPTY_BODY)
                .build()
            try {
                http.data(request)
            } catch (_: AudiobookshelfError) {
                // Signing out locally must not depend on the server agreeing.
            }
        }
        tokens.signedOut()
    }

    internal suspend fun <T> get(path: String, de: DeserializationStrategy<T>, query: Map<String, String?> = emptyMap()): T =
        call("GET", path, de, query, null)

    /** A request with a JSON body, decoded. */
    internal suspend fun <T> call(method: String, path: String, de: DeserializationStrategy<T>, query: Map<String, String?> = emptyMap(), body: JsonElement?): T {
        val data = raw(method, path, query, body)
        return decodeBytes(http, method, "/$path", data, de)
    }

    /** A request whose answer does not matter (many answer plain text `OK`). */
    internal suspend fun send(method: String, path: String, query: Map<String, String?> = emptyMap(), body: JsonElement? = null) {
        raw(method, path, query, body)
    }

    /**
     * The bytes of a request, with the access token; a `401` is answered once with a refresh.
     * Offline, a saved answer to a read or [AudiobookshelfError.Offline] without touching the
     * network; online, a network failure falls back to the saved answer.
     */
    internal suspend fun raw(method: String, path: String, query: Map<String, String?>, body: JsonElement?): ByteArray {
        val key = if (method == "GET" && keeps(path)) cacheKey(path, query) else null
        val saved = { key?.let { cache?.data(it) } }
        if (isOffline()) return saved() ?: throw AudiobookshelfError.Offline
        return try {
            fetch(method, path, query, body).also { data -> key?.let { cache?.store(data, it) } }
        } catch (e: AudiobookshelfError.Network) {
            saved() ?: throw e
        }
    }

    internal fun cacheKey(path: String, query: Map<String, String?>): String {
        val params = query.mapNotNull { (k, v) -> v?.let { "$k=$it" } }.sorted().joinToString("&")
        return "${cacheIdentity ?: host.toString()}|$path?$params"
    }

    private suspend fun fetch(method: String, path: String, query: Map<String, String?>, body: JsonElement?): ByteArray {
        val url = url(path, query)
        val requestBody = when {
            body != null -> jsonBody(body)
            method == "POST" || method == "PATCH" || method == "PUT" -> EMPTY_BODY
            else -> null
        }
        fun build(token: String) = http.request()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, requestBody)
            .build()

        val token = tokens.accessToken(refresher())
        return try {
            http.data(build(token))
        } catch (e: AudiobookshelfError.Http) {
            if (e.status != 401) throw e
            // The access token was revoked or expired early: one refresh, one more try. With no
            // refresh token (a pre-2.26 server) the session is simply over. If another request
            // already refreshed meanwhile, its tokens are taken instead of spending the new
            // refresh token a second time.
            val fresh = tokens.refresh(stale = token, refresh = refresher())
            http.data(build(fresh.accessToken))
        }
    }

    internal fun refresher(): suspend (String) -> Tokens = { refreshToken -> refresh(host, refreshToken, http) }

    internal fun url(path: String, query: Map<String, String?>): HttpUrl {
        val builder = host.newBuilder().addPathSegments(path)
        query.entries.filter { it.value != null }.sortedBy { it.key }.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    // ---- Media and covers

    /**
     * The access token good now, for URLs a player, a download or an image loader fetches on
     * its own; they carry it as `?token=` so the URL alone is enough and caches key on it
     * consistently.
     */
    suspend fun freshToken(): String = tokens.accessToken(refresher())

    /**
     * A new access token after [stale] was refused (a `401` on a media URL mid-book), shared
     * with any other caller doing the same.
     */
    suspend fun tokenAfterRefusal(stale: String): String = tokens.refresh(stale = stale, refresh = refresher()).accessToken

    /** One audio file of an item, with ranges (answers 206); [token] from [freshToken]. */
    fun fileUrl(itemId: String, ino: String, token: String): HttpUrl = host.newBuilder()
        .addPathSegments("api/items")
        .addPathSegment(itemId)
        .addPathSegment("file")
        .addPathSegment(ino)
        .addQueryParameter("token", token)
        .build()

    /** A `contentUrl` from a play session (a file path, or an HLS playlist), made absolute and given the token. */
    fun mediaUrl(contentUrl: String, token: String): HttpUrl = host.newBuilder()
        .addEncodedPathSegments(contentUrl.removePrefix("/"))
        .addQueryParameter("token", token)
        .build()

    /**
     * An item's cover. Covers need no token on 2.37 (checked), but one is sent when known so a
     * server that requires it still answers.
     */
    fun coverUrl(itemId: String, width: Int = 600, token: String? = null): HttpUrl = host.newBuilder()
        .addPathSegments("api/items")
        .addPathSegment(itemId)
        .addPathSegment("cover")
        .addQueryParameter("width", width.toString())
        .addQueryParameter("format", "jpeg")
        .apply { token?.let { addQueryParameter("token", it) } }
        .build()
}

/** [path] (several segments, e.g. `api/libraries`) under the server's root, which may itself have a path. */
internal fun HttpUrl.under(path: String): HttpUrl = newBuilder().addPathSegments(path).build()
