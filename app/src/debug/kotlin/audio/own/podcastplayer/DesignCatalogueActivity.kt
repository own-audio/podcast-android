package audio.own.podcastplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import audio.own.core.designsystem.components.DesignCatalogue
import audio.own.core.designsystem.theme.OwnAudioTheme

class DesignCatalogueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OwnAudioTheme { DesignCatalogue(Modifier.fillMaxSize().safeDrawingPadding()) } }
    }
}
