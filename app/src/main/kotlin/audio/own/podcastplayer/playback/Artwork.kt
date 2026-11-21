package audio.own.podcastplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * Pictures outside Compose: the notification's and the lock screen's, through the app's Coil
 * cache and at [NOTIFICATION_PX], not the multi-megabyte original many feeds link to.
 */
class Artwork(private val context: Context) {
    suspend fun notificationBitmap(uri: Uri): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(uri.toString())
            .size(NOTIFICATION_PX)
            // The notification copies the pixels across processes: a hardware bitmap can't go.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    companion object {
        const val NOTIFICATION_PX = 900
    }
}
