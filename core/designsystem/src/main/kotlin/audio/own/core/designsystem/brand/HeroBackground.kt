package audio.own.core.designsystem.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import audio.own.core.designsystem.R
import audio.own.core.designsystem.theme.LocalPalette

/** The full-bleed artwork behind the welcome screen, veiled so the form on it stays legible. */
@Composable
fun HeroBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        Image(
            painterResource(R.drawable.own_audio_hero),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(LocalPalette.current.background.copy(alpha = 0.72f)))
    }
}
