package audio.own.podcastplayer.google

import android.content.Context

/** The `foss` build has no Google Play services: no Google button anywhere. */
object GoogleSignIn {
    const val isAvailable = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun idToken(context: Context, serverClientId: String): GoogleResult = GoogleResult.Failed("not in this build")
}
