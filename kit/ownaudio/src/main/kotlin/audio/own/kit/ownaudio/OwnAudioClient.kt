package audio.own.kit.ownaudio

import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Where answers to reads are kept, so a screen seen once opens again with no network. The key
 * names the server, the path and the query, never a token.
 */
interface AnswerCache {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
}

/**
 * A client for own.audio's own API, `/api/v2`, at [baseUrl] (the server's root, the address
 * discovery chose).
 *
 * @param http the app's client; this adds the User-Agent, Accept-Language and the bearer.
 * @param userAgent `own.audio Music/<version> (Android <release>; <model>)`. The hosted
 *   service's edge refuses requests without a proper one.
 * @param acceptLanguage the app's locale, so mail the server sends comes in the right language.
 * @param tokens the account's session; `null` before signing in (only the public calls work).
 * @param cacheIdentity what answers are filed under: the server's id, whichever address.
 * @param isOffline when true, reads are answered from [cache] or fail with
 *   [OwnAudioException.Offline] without touching the network.
 */
class OwnAudioClient(
    baseUrl: String,
    val http: OkHttpClient,
    val userAgent: String,
    val tokens: TokenSession? = null,
    val acceptLanguage: String? = null,
    val cache: AnswerCache? = null,
    val cacheIdentity: String? = null,
    val isOffline: () -> Boolean = { false },
    /** One request's whole time limit; streams have their own. */
    val requestTimeoutMs: Long = 20_000,
    internal val clock: Clock = Clock.systemUTC(),
) {
    /** The server's root, without a trailing slash. */
    val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** What the session list shows for this device. */
    data class Device(val name: String, val kind: String = "android")

    // region Building requests

    internal fun url(path: String, query: Query = Query()): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments(API).apply {
            path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        }
        query.sorted().forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    internal fun rootUrl(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path.trim('/')).build()

    internal fun base(url: HttpUrl, accept: String = JSON): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", accept)
        .apply { acceptLanguage?.let { header("Accept-Language", it) } }

    // endregion

    // region Calls without an account

    internal suspend fun <T> public(request: Request, serializer: KSerializer<T>): T = decode(request, serializer, execute(request))

    // endregion

    // region Signed-in calls

    /** GET [path] under `/api/v2`, decoded. */
    suspend fun <T> get(path: String, serializer: KSerializer<T>, query: Query = Query()): T =
        decode(path, serializer, raw("GET", path, query))

    /** A request with an optional JSON body, decoded. */
    suspend fun <T, B> call(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        body: B?,
        bodySerializer: KSerializer<B>,
        query: Query = Query(),
        idempotencyKey: String? = null,
    ): T = decode(path, serializer, raw(method, path, query, body?.let { CanonicalJson.encode(bodySerializer, it) }, idempotencyKey))

    internal suspend inline fun <reified T> getJson(path: String, query: Query = Query()): T = get(path, serializer<T>(), query)

    internal suspend inline fun <reified T, reified B> send(method: String, path: String, body: B?, query: Query = Query(), idempotencyKey: String? = null): T =
        call(method, path, serializer<T>(), body, serializer<B>(), query, idempotencyKey)

    /** A request whose answer has no body that matters (`204`). */
    internal suspend inline fun <reified B> sendOnly(method: String, path: String, body: B?, query: Query = Query()) {
        raw(method, path, query, body?.let { CanonicalJson.encode(serializer<B>(), it) }, null)
    }

    internal suspend fun sendOnly(method: String, path: String, query: Query = Query()) {
        raw(method, path, query, null, null)
    }

    /**
     * The bytes of a request with the access token; a `401` is answered once with a refresh and
     * one more try. Offline, a kept answer to a read or [OwnAudioException.Offline] without
     * touching the network; online, a network failure falls back to the kept answer.
     */
    suspend fun raw(method: String, path: String, query: Query = Query(), jsonBody: String? = null, idempotencyKey: String? = null): ByteArray {
        val key = if (method == "GET" && keeps(path)) cacheKey(path, query) else null
        val kept = { key?.let { cache?.get(it) } }
        if (isOffline()) return kept() ?: throw OwnAudioException.Offline
        return try {
            val bytes = authorized { token ->
                base(url(path, query)).method(method, requestBody(method, jsonBody)).header("Authorization", "Bearer $token")
                    .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
                    .build()
            }
            if (key != null) cache?.put(key, bytes)
            bytes
        } catch (e: OwnAudioException.Network) {
            kept() ?: throw e
        }
    }

    /** Runs [build]'s request with a good access token, refreshing once on a `401`. */
    internal suspend fun authorized(timeoutMs: Long = requestTimeoutMs, build: (String) -> Request): ByteArray {
        val session = tokens ?: throw OwnAudioException.SignedOut
        val token = session.accessToken(::refreshTokens)
        return try {
            execute(build(token), timeoutMs)
        } catch (p: OwnAudioException.Problem) {
            if (p.status != 401) throw p
            val fresh = session.refresh(token, ::refreshTokens)
            execute(build(fresh.accessToken), timeoutMs)
        } catch (e: OwnAudioException.Http) {
            if (e.status != 401) throw e
            val fresh = session.refresh(token, ::refreshTokens)
            execute(build(fresh.accessToken), timeoutMs)
        }
    }

    internal suspend fun refreshTokens(refreshToken: String): Tokens =
        tokenGrant(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))

    // endregion

    // region Answer cache

    internal fun cacheKey(path: String, query: Query): String {
        val params = query.sorted().joinToString("&") { "${it.first}=${it.second}" }
        return "${cacheIdentity ?: baseUrl.toString()}|$API/${path.trim('/')}?$params"
    }

    // endregion

    // region Transport

    // The body is read after the call resumes, on the caller's thread: screens call from the main
    // thread, where reading a large answer throws NetworkOnMainThreadException.
    internal suspend fun execute(request: Request, timeoutMs: Long = requestTimeoutMs): ByteArray = withContext(Dispatchers.IO) {
        val response = http.newCall(request).also { it.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }.await()
        response.use {
            val bytes = try {
                it.body.bytes()
            } catch (e: IOException) {
                throw OwnAudioException.Network(e)
            }
            if (!it.isSuccessful) throw problem(it, bytes)
            bytes
        }
    }

    internal fun problem(response: Response, bytes: ByteArray): OwnAudioException {
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        val body = try {
            OwnAudioJson.decodeFromString(ProblemBody.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (body == null) return OwnAudioException.Http(response.code)
        val code = body.code ?: body.error ?: return OwnAudioException.Http(response.code)
        return OwnAudioException.Problem(
            status = body.status ?: response.code,
            code = code,
            detail = body.detail ?: body.errorDescription,
            mfaToken = body.mfaToken,
            mfaMethods = body.mfaMethods,
            feature = body.feature,
            errors = body.errors,
            retryAfterSeconds = retryAfter,
        )
    }

    internal fun <T> decode(what: Any, serializer: KSerializer<T>, bytes: ByteArray): T = try {
        if (bytes.isEmpty() && serializer.descriptor.serialName == "kotlin.Unit") {
            @Suppress("UNCHECKED_CAST")
            Unit as T
        } else {
            OwnAudioJson.decodeFromString(serializer, bytes.decodeToString())
        }
    } catch (e: SerializationException) {
        throw OwnAudioException.Decoding(e)
    } catch (e: IllegalArgumentException) {
        throw OwnAudioException.Decoding(e)
    }

    internal fun decodeObject(bytes: ByteArray): JsonObject = decode("object", JsonObject.serializer(), bytes)

    internal fun <T> decodeElement(serializer: KSerializer<T>, element: JsonElement): T = try {
        OwnAudioJson.decodeFromJsonElement(serializer, element)
    } catch (e: SerializationException) {
        throw OwnAudioException.Decoding(e)
    } catch (e: IllegalArgumentException) {
        throw OwnAudioException.Decoding(e)
    }

    // endregion

    companion object {
        internal const val API = "api/v2"
        internal const val JSON = "application/json"
        internal val JSON_TYPE = "application/json".toMediaType()
        internal val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()

        /**
         * Reads whose answers are kept. Search, random picks and the podcast directory are
         * left out: an old answer to those would mislead rather than help. Not `me` itself:
         * the connection check asks it and must not be answered from here.
         */
        fun keeps(path: String): Boolean {
            val p = path.trim('/')
            val first = p.substringBefore('/')
            return when (first) {
                "artists", "contributors", "albums", "tracks", "genres", "books", "authors", "narrators", "hosts",
                "series", "collections", "book-tags", "playlists", "smart-playlists", "episodes", "release-groups",
                -> !p.endsWith("/random") && !p.endsWith("/lyrics")
                "podcasts" -> !p.startsWith("podcasts/directory")
                "family" -> p == "family" || p == "family/members" || p == "family/storage" || p == "family/billing"
                "me" -> p.startsWith("me/favourites") || p.startsWith("me/ratings") || p.startsWith("me/played") ||
                    p.startsWith("me/feedback") || p == "me/podcasts/unplayed"
                else -> false
            }
        }

        /** `application/x-www-form-urlencoded`, keys sorted, RFC 3986 unreserved left as they are. */
        fun formEncode(fields: Map<String, String?>): String = fields.entries
            .filter { it.value != null }
            .sortedBy { it.key }
            .joinToString("&") { "${percent(it.key)}=${percent(it.value!!)}" }

        private fun percent(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                    append(ch)
                } else {
                    append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                }
            }
        }

        private fun requestBody(method: String, json: String?): RequestBody? = when {
            json != null -> json.toRequestBody(JSON_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
    }
}

/** Query parameters; a `null` value is left out. Sent sorted, so equal queries are equal URLs. */
class Query(vararg pairs: Pair<String, String?>) {
    private val items = pairs.toMutableList()

    fun add(name: String, value: String?): Query = apply { items += name to value }
    fun add(name: String, value: Int?): Query = add(name, value?.toString())
    fun add(name: String, values: List<String>): Query = add(name, values.takeIf { it.isNotEmpty() }?.joinToString(","))

    internal fun sorted(): List<Pair<String, String>> =
        items.mapNotNull { (k, v) -> v?.let { k to it } }.sortedWith(compareBy({ it.first }, { it.second }))
}

/** Waits for an OkHttp call without holding a thread; cancelling the coroutine cancels it. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(OwnAudioException.Network(e))
        }
    })
}
