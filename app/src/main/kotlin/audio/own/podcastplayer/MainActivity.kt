package audio.own.podcastplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import audio.own.podcastplayer.ui.AppRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The one activity. AppCompat, so the in-app language applies below Android 13 too. */
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private val container get() = (application as OwnAudioApp).container

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppRoot(container) }
        handle(intent)
        askForNotificationsOnFirstPlay()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        container.podcasts.setForeground(true)
    }

    override fun onStop() {
        container.podcasts.setForeground(false)
        super.onStop()
    }

    /** `feed://`, `podcast://` and `pcast://` links: Add a Show with the address. */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme !in FEED_SCHEMES) return
        container.podcasts.openFeedLink(data.toString())
    }

    /**
     * The media notification shows what plays; Android 13 asks before any notification. Asked the
     * first time an episode plays, never at launch, and only once.
     */
    private fun askForNotificationsOnFirstPlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            if (container.preferences.notificationsAsked()) return@launch
            container.player.state.first { it.isPlaying }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            container.preferences.setNotificationsAsked()
        }
    }

    private companion object {
        val FEED_SCHEMES = setOf("feed", "podcast", "pcast")
    }
}
