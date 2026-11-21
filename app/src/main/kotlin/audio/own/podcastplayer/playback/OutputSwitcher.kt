package audio.own.podcastplayer.playback

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build

/**
 * The system's own output picker (speaker, headphones, Bluetooth, cast-capable speakers): the
 * Android counterpart of the AirPlay button. Android 14 opens it directly; Android 11 to 13 by
 * asking System UI for its media output dialog. Older versions have none to open.
 */
object OutputSwitcher {
    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun show(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intent = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
            .setPackage("com.android.systemui")
            .putExtra("package_name", context.packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendBroadcast(intent)
        return true
    }
}
