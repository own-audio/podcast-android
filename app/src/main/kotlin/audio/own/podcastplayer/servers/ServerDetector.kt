package audio.own.podcastplayer.servers

import audio.own.core.servers.TypedAddress
import audio.own.core.strings.R
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.discover
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.UserFacingException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What answered at a typed address. */
sealed interface Detected {
    val host: String

    /** An own.audio server new enough for this app (`/api/v2` revision 16 or later). */
    data class OwnAudio(override val host: String, val discovery: Discovery) : Detected

    /** An Audiobookshelf server; its podcast libraries come with P3. */
    data class Audiobookshelf(override val host: String) : Detected

    /** A gPodder sync server; [software] is "podfetch" when it said so. Sync comes with P5. */
    data class GPodder(override val host: String, val software: String?) : Detected
}

/**
 * Finds out what kind of server is at a typed address before anything is saved. Each candidate
 * (https first, then http) is asked, in this order: own.audio's discovery; podfetch's
 * `/api/v1/sys/config` (podfetch's own path, not own.audio's v1); Audiobookshelf's `/status`;
 * and a gPodder device list, which a gPodder server refuses without a sign-in. A host where
 * nothing answers at all is skipped. A wrong guess is given up on after [timeoutMs].
 */
class ServerDetector(
    http: OkHttpClient,
    private val userAgent: String,
    private val log: AppLog,
    private val timeoutMs: Long = 6_000,
) {
    // OkHttp keeps no cookies unless given a jar, so a probe leaves nothing behind.
    private val quick = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).followRedirects(true).build()

    fun client(host: String): OwnAudioClient = OwnAudioClient(baseUrl = host, http = quick, userAgent = userAgent, requestTimeoutMs = timeoutMs)

    /** The own.audio discovery at [host], or null when nothing like it answers there. */
    suspend fun discovery(host: String): Discovery? = try {
        client(host).discover()
    } catch (e: OwnAudioException) {
        null
    }

    /**
     * @param kind one of [ServerKinds], or null to find out.
     * @throws UserFacingException with "That doesn't look like a server address." for nothing
     *   to try or nothing of the kind there, "Couldn't reach the server…" when no candidate
     *   answered, and the too-old message for an own.audio server without a new enough `/api/v2`.
     */
    suspend fun detect(typed: String, kind: String? = null): Detected {
        val candidates = TypedAddress.candidates(typed).filter { it.toHttpUrlOrNull() != null }
        if (candidates.isEmpty()) throw UserFacingException(R.string.error_invalid_address)
        var answered = false
        for (host in candidates) {
            if (kind == null || kind == ServerKinds.OWN_AUDIO) {
                try {
                    val found = client(host).discover()
                    found.requireV2()
                    log.info("servers", "own.audio ${found.version} at ${hostOnly(host)}")
                    return Detected.OwnAudio(host, found)
                } catch (e: OwnAudioException.TooOld) {
                    throw UserFacingException(R.string.error_too_old, e)
                } catch (e: OwnAudioException.Network) {
                    continue
                } catch (e: OwnAudioException) {
                    answered = true
                }
                if (kind == ServerKinds.OWN_AUDIO) continue
            }
            when (val probe = otherKind(host, kind)) {
                is Probe.Found -> {
                    log.info("servers", "${probe.detected::class.simpleName} at ${hostOnly(host)}")
                    return probe.detected
                }
                Probe.Answered -> answered = true
                null -> Unit
            }
        }
        throw UserFacingException(if (answered) R.string.error_invalid_address else R.string.error_unreachable)
    }

    private sealed interface Probe {
        data class Found(val detected: Detected) : Probe
        data object Answered : Probe
    }

    /** Steps 2–4; null when nothing answered at all. */
    private suspend fun otherKind(host: String, kind: String?): Probe? = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/')
        var answered = false
        if (kind == null || kind == ServerKinds.GPODDER) {
            val config = get("$base/api/v1/sys/config")
            if (config != null) answered = true
            if (config?.json()?.let { "rssFeed" in it && "basicAuth" in it } == true) {
                return@withContext Probe.Found(Detected.GPodder(host, "podfetch"))
            }
        }
        if (kind == null || kind == ServerKinds.AUDIOBOOKSHELF) {
            val status = get("$base/status")
            if (status != null) answered = true
            val json = status?.json()
            if (json != null && ((json["app"] as? JsonPrimitive)?.content == "audiobookshelf" || ("serverVersion" in json && "isInit" in json))) {
                return@withContext Probe.Found(Detected.Audiobookshelf(host))
            }
        }
        if (kind == null || kind == ServerKinds.GPODDER) {
            val code = status("$base/api/2/devices/own-audio-probe.json")
            if (code != null) answered = true
            if (code == 401 || code == 403) return@withContext Probe.Found(Detected.GPodder(host, null))
        }
        if (answered) Probe.Answered else null
    }

    /** The body of an answer ("" when not 2xx or too big for a probe's); null when nothing answered. */
    private fun get(url: String): String? = call(url) { response ->
        if (!response.isSuccessful) "" else response.body.string().takeIf { it.length < 64_000 } ?: ""
    }

    private fun status(url: String): Int? = call(url) { it.code }

    private fun <T> call(url: String, read: (Response) -> T): T? = try {
        quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use(read)
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun String.json(): JsonObject? = try {
        if (isBlank()) null else Json.parseToJsonElement(this) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    private fun hostOnly(url: String) = url.toHttpUrlOrNull()?.host ?: url
}
