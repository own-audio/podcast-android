package audio.own.podcastplayer

import android.Manifest
import android.app.SearchManager
import android.provider.MediaStore
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import audio.own.podcastplayer.ui.AppRoot
import audio.own.podcastplayer.widget.WidgetSnapshot
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
        // Phones stay upright, as on iOS; tablets and unfolded foldables turn with the device.
        if (resources.configuration.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        setContent { AppRoot(container) }
        // After a rotation the same intent comes back; it was handled the first time.
        if (savedInstanceState == null) handle(intent)
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

    override fun onResume() {
        super.onResume()
        // Back from the browser without its answer: Sign in with Apple was closed.
        container.appleSignIn.appReturned()
    }

    override fun onStop() {
        container.appleSignIn.appLeft()
        container.podcasts.setForeground(false)
        super.onStop()
    }

    /**
     * A keyboard on a tablet or a Chromebook: Space plays and pauses, Ctrl+← and Ctrl+→ skip,
     * wherever the app is. A text field or the player takes them first.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val player = container.player
        if (player.state.value.item != null) {
            when {
                keyCode == KeyEvent.KEYCODE_SPACE && !event.isCtrlPressed -> { player.toggle(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.isCtrlPressed -> { player.skipBack(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.isCtrlPressed -> { player.skipForward(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * `feed://`, `podcast://` and `pcast://` links: Add a Show with the address. The widget's
     * `ownpodcast://play/<id>` plays that episode; the Continue Listening shortcut and the
     * assistant's "play ‹show›" play what they name.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        val podcasts = container.podcasts
        when (intent.action) {
            ACTION_CONTINUE -> container.mainScope.launch { podcasts.continueListening() }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                container.mainScope.launch { podcasts.playFromSearch(query) }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val episode = WidgetSnapshot.episodeId(data)
                when {
                    episode != null -> container.mainScope.launch { podcasts.playLink(episode) }
                    data.scheme in FEED_SCHEMES -> podcasts.openFeedLink(data.toString())
                }
            }
        }
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
        const val TABLET_MIN_WIDTH_DP = 600
        const val ACTION_CONTINUE = "audio.own.podcastplayer.CONTINUE_LISTENING"
    }
}
