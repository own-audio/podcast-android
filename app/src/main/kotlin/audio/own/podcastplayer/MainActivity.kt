package audio.own.podcastplayer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import audio.own.podcastplayer.ui.AppRoot

/** The one activity. AppCompat, so the in-app language applies below Android 13 too. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as OwnAudioApp).container
        setContent { AppRoot(container) }
    }
}
