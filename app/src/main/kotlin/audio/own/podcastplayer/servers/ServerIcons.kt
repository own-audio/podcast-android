package audio.own.podcastplayer.servers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import audio.own.core.servers.ServerRecord
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.app.ServerExtras
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Each server's own icon, as the server itself serves it (Audiobookshelf's, podfetch's…), loaded
 * at run time, as a browser shows a site's icon, and kept on the device: the app ships no one
 * else's artwork. Asked once per launch for a server that has none yet.
 *
 * @param decode the picture as PNG, or null when it isn't one (or is too small to show).
 */
class ServerIcons(
    private val dir: File,
    http: OkHttpClient,
    private val userAgent: String,
    private val log: AppLog,
    private val decode: (ByteArray) -> ByteArray? = ::asPng,
) {
    // Short: an icon is never worth holding a list up for.
    private val quick = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val _icons = MutableStateFlow<Map<String, File>>(emptyMap())
    val icons: StateFlow<Map<String, File>> = _icons.asStateFlow()
    private val asked = HashSet<String>()

    /** From disk first; from the server at [address] when there is none yet and the device is online. */
    suspend fun load(records: List<ServerRecord>, extras: Map<String, ServerExtras>, address: (ServerRecord) -> String?, online: Boolean) = withContext(Dispatchers.IO) {
        for (record in records) {
            val paths = paths(record, extras[record.id])
            if (paths.isEmpty()) continue
            val file = File(dir, "${record.id}.png")
            if (file.exists()) {
                if (_icons.value[record.id] != file) _icons.update { it + (record.id to file) }
                continue
            }
            if (!online || !synchronized(asked) { asked.add(record.id) }) continue
            val base = (address(record) ?: record.host).trimEnd('/')
            for (path in paths) {
                val png = fetch("$base/$path")?.let(decode) ?: continue
                dir.mkdirs()
                file.writeBytes(png)
                _icons.update { it + (record.id to file) }
                break
            }
        }
    }

    fun forget(serverId: String) {
        File(dir, "$serverId.png").delete()
        synchronized(asked) { asked.remove(serverId) }
        _icons.update { it - serverId }
    }

    private fun fetch(url: String): ByteArray? = try {
        quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (response.code != 200 || !(type.startsWith("image/") || type.isEmpty())) null else response.body.bytes().takeIf { it.size < MAX_BYTES }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        log.error("servers", "an icon address is not valid")
        null
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024

        /** Where each kind of server keeps its icon, best first; none for own.audio, whose mark ships with the app. */
        fun paths(record: ServerRecord, extras: ServerExtras?): List<String> = when (record.kind) {
            ServerKinds.AUDIOBOOKSHELF -> listOf("icon192.png", "icon64.png", "favicon.ico")
            ServerKinds.GPODDER -> if (extras?.software == "podfetch") listOf("ui/logo.png", "ui/favicon.ico") else listOf("apple-touch-icon.png", "favicon.ico")
            else -> emptyList()
        }

        /** Any picture Android reads (PNG, an ICO's largest image…) as PNG, when at least 16 px wide. */
        fun asPng(bytes: ByteArray): ByteArray? {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width < 16) return null
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    }
}
