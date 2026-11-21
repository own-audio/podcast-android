package audio.own.core.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.LocalReduceMotion
import audio.own.core.strings.R

// Private content gets no badge at all: "private" means "not shared with the family", never a
// lock. Family-shared content gets the people icon.

@Composable
fun FamilyBadge(modifier: Modifier = Modifier) {
    Icon(Icons.Filled.Group, stringResource(R.string.shared_with_family), tint = LocalPalette.current.muted, modifier = modifier.size(14.dp))
}

@Composable
fun DownloadBadge(modifier: Modifier = Modifier) {
    Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.downloaded), tint = LocalPalette.current.muted, modifier = modifier.size(14.dp))
}

/** Three bars that bounce while playing and rest when paused or when animations are off. */
@Composable
fun NowPlayingIndicator(playing: Boolean, modifier: Modifier = Modifier) {
    val color = LocalPalette.current.accent
    val moving = playing && !LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 360).map { delay ->
        val v by t.animateFloat(0.3f, if (moving) 1f else 0.3f, infiniteRepeatable(tween(450, delay), RepeatMode.Reverse), label = "bar$delay")
        v
    }
    val label = stringResource(R.string.now_playing)
    Canvas(modifier.size(14.dp).semantics { contentDescription = label }) {
        val w = size.width / 5
        phases.forEachIndexed { i, h ->
            val height = size.height * (if (moving) h else listOf(0.5f, 0.9f, 0.6f)[i])
            drawRoundRect(color, Offset(i * 2 * w, size.height - height), Size(w, height), CornerRadius(w / 2))
        }
    }
}
