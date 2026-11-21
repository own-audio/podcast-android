package audio.own.kit.gpodder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The gPodder API (v2) with HTTP basic authentication on every request, which every server that
 * speaks it accepts (gpodder.net also hands out a session cookie; nothing here needs it).
 *
 * @param host the server's root, e.g. `https://gpodder.example` or `https://example.org/gpodder/`.
 * @param userAgent `own.audio Podcasts/<version> (Android <release>; <model>)`.
 */
class GPodderClient(
    host: String,
    val username: String,
    private val password: String,
    client: OkHttpClient,
    private val userAgent: String = "own.audio Podcasts",
) {
    val host: HttpUrl = host.toHttpUrl()

    // No cookies: a session cookie from an earlier sign-in would otherwise outrank the
    // credentials on servers that honour it first (podfetch), so a wrong password could pass
    // after a right one. The credentials are added per hop, so they survive a same-host redirect
    // (http to https) and are never sent to another host.
    private val http = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == this.host.host) {
                chain.proceed(request.newBuilder().header("Authorization", Credentials.basic(username, password, Charsets.UTF_8)).build())
            } else {
                chain.proceed(request.newBuilder().removeHeader("Authorization").build())
            }
        }
        .build()

    /** Checks the name and password. */
    suspend fun login() {
        try {
            send("POST", listOf("auth", username, "login.json"), body = EMPTY)
        } catch (_: GPodderException.Forbidden) {
            throw GPodderException.Unauthorized()
        }
    }

    /**
     * Registers (or renames) this phone as a device, which subscriptions are kept per. [type] is
     * one of the API's device types: desktop, laptop, mobile, server, other.
     */
    suspend fun registerDevice(id: String, caption: String, type: String = "mobile") {
        send("POST", listOf("devices", username, "$id.json"), body = encode(DeviceBody.serializer(), DeviceBody(caption, type)))
    }

    /**
     * What changed in the account's subscriptions on every device since [since], where the server
     * keeps such a list (gpodder.net, podfetch); null where it does not. On servers that keep
     * subscriptions per device, this is how shows added in another app arrive.
     */
    suspend fun allSubscriptionChanges(since: Long): SubscriptionChanges? = try {
        decode(SubscriptionChanges.serializer(), send("GET", listOf("subscriptions", "$username.json"), mapOf("since" to since.toString())))
    } catch (error: GPodderException.Http) {
        if (error.status == 404 || error.status == 400 || error.status == 405) null else throw error
    } catch (_: GPodderException.Decoding) {
        null
    }

    /** What changed in the device's subscriptions since [since] (0 for everything). */
    suspend fun subscriptionChanges(device: String, since: Long): SubscriptionChanges =
        decode(SubscriptionChanges.serializer(), send("GET", listOf("subscriptions", username, "$device.json"), mapOf("since" to since.toString())))

    /** Answers the new `since` and the URLs the server rewrote, which the caller applies. */
    suspend fun uploadSubscriptionChanges(device: String, add: List<String>, remove: List<String>): UploadAnswer =
        decode(
            UploadAnswer.serializer(),
            send("POST", listOf("subscriptions", username, "$device.json"), body = encode(SubscriptionBody.serializer(), SubscriptionBody(add, remove))),
        )

    /** Every device's episode actions since [since]. */
    suspend fun episodeActions(since: Long): EpisodeActions =
        decode(EpisodeActions.serializer(), send("GET", listOf("episodes", "$username.json"), mapOf("since" to since.toString())))

    /** Uploads one batch; callers split larger sets into [ACTIONS_PER_UPLOAD]. */
    suspend fun uploadEpisodeActions(actions: List<EpisodeAction>): UploadAnswer =
        decode(UploadAnswer.serializer(), send("POST", listOf("episodes", "$username.json"), body = encode(ListSerializer(EpisodeAction.serializer()), actions)))

    // MARK: transport

    @Serializable
    private data class DeviceBody(val caption: String, val type: String)

    @Serializable
    private data class SubscriptionBody(val add: List<String>, val remove: List<String>)

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        canonical(json.encodeToJsonElement(serializer, value)).toString().toRequestBody(JSON_TYPE)

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T = try {
        json.decodeFromString(serializer, bytes.decodeToString())
    } catch (error: SerializationException) {
        throw GPodderException.Decoding(error)
    } catch (error: IllegalArgumentException) {
        throw GPodderException.Decoding(error)
    }

    private suspend fun send(method: String, path: List<String>, query: Map<String, String> = emptyMap(), body: RequestBody? = null): ByteArray {
        val url = host.newBuilder().apply {
            // The host's own path (a server under a prefix) stays; its trailing empty segment goes.
            encodedPath("/")
            host.pathSegments.filter { it.isNotEmpty() }.forEach(::addPathSegment)
            addPathSegment("api")
            addPathSegment("2")
            path.forEach(::addPathSegment)
            query(null)
            query.toSortedMap().forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", userAgent)
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (error: IOException) {
            throw GPodderException.Network(error)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                when (it.code) {
                    401 -> throw GPodderException.Unauthorized()
                    403 -> throw GPodderException.Forbidden()
                    !in 200..299 -> throw GPodderException.Http(it.code)
                }
                try {
                    it.body.bytes()
                } catch (error: IOException) {
                    throw GPodderException.Network(error)
                }
            }
        }
    }

    companion object {
        /** gpodder.net takes at most a few hundred episode actions per call. */
        const val ACTIONS_PER_UPLOAD = 200

        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            isLenient = true
        }

        /** Object keys sorted at every level, so the same actions always give the same bytes. */
        internal fun canonical(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            else -> element
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
