package audio.own.podcastplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import androidx.core.graphics.scale
import coil3.toBitmap
import java.io.ByteArrayOutputStream

/**
 * Pictures outside Compose: the notification's and the lock screen's, through the app's Coil
 * cache and at [NOTIFICATION_PX], not the multi-megabyte original many feeds link to.
 */
class Artwork(private val context: Context) {
    suspend fun notificationBitmap(uri: Uri): Bitmap? = bitmap(uri.toString(), NOTIFICATION_PX)

    /**
     * A small square JPEG of the picture at [url], for the widget and the car: they read files,
     * never the network.
     */
    suspend fun smallJpeg(url: String, px: Int): ByteArray? {
        val bitmap = bitmap(url, px) ?: return null
        val square = bitmap.scale(px, px)
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private suspend fun bitmap(url: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(px)
            // The notification copies the pixels across processes: a hardware bitmap can't go.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    companion object {
        const val NOTIFICATION_PX = 900
        private const val JPEG_QUALITY = 80
    }
}
