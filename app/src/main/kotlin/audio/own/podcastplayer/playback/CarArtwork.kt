package audio.own.podcastplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import audio.own.podcastplayer.OwnAudioApp
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures for Android Auto's lists. The car reads a row's artwork through a content URI rather
 * than from the web, so each picture the browse tree names is handed out as
 * `content://<app>.artwork/art?u=<address>` and served small from the app's cache. Only addresses
 * the tree itself named are served: other apps can't make this one fetch what they like.
 */
class CarArtwork(private val authority: String, private val folder: File) {
    private val named: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun uri(url: String?): Uri? {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        named += url
        return Uri.Builder().scheme("content").authority(authority).appendPath("art").appendQueryParameter("u", url).build()
    }

    fun isNamed(url: String): Boolean = url in named

    /** The cached file for [url], made on first use. */
    fun file(url: String): File = File(folder, sha1(url) + ".jpg")

    private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val PX = 256
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}

class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val container = (context?.applicationContext as? OwnAudioApp)?.container ?: throw FileNotFoundException("not ready")
        val url = uri.getQueryParameter("u") ?: throw FileNotFoundException("no picture")
        val car = container.carArtwork
        if (!car.isNamed(url)) throw FileNotFoundException("not one of the app's pictures")
        val file = car.file(url)
        if (!file.exists()) {
            val bytes = runBlocking(Dispatchers.IO) { withTimeoutOrNull(CarArtwork.LOAD_TIMEOUT_MS) { container.artwork.smallJpeg(url, CarArtwork.PX) } }
                ?: throw FileNotFoundException("picture unavailable")
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
