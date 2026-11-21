package audio.own.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R

/**
 * The floating bar above the navigation bar. Music draws its progress line along the top with the
 * buffered part lighter; Books and Podcasts along the bottom ([progressAtTop]).
 */
@Composable
fun MiniPlayerBar(
    cover: Any?,
    placeholder: ImageVector,
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    secondaryIcon: ImageVector,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    secondaryEnabled: Boolean = true,
    progressAtTop: Boolean = true,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(AppBrand.radii.miniPlayer)
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.sm),
        shape = shape,
        color = palette.backgroundAlt.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.clip(shape)) {
            Row(
                Modifier.clickable(onClick = onOpen).padding(Tokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(cover, 40.dp, placeholder, radius = AppBrand.radii.miniCover)
                Column(Modifier.weight(1f).padding(horizontal = Tokens.Spacing.md)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onToggle) {
                    when {
                        isLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        isPlaying -> Icon(Icons.Filled.Pause, stringResource(R.string.pause))
                        else -> Icon(Icons.Filled.PlayArrow, stringResource(R.string.play))
                    }
                }
                IconButton(onClick = onSecondary, enabled = secondaryEnabled) { Icon(secondaryIcon, secondaryLabel) }
            }
            val line = Modifier.fillMaxWidth().height(2.dp).align(if (progressAtTop) Alignment.TopStart else Alignment.BottomStart)
            Box(line) {
                Box(Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).height(2.dp).background(palette.accent.copy(alpha = 0.3f)))
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(palette.accent))
            }
        }
    }
}
