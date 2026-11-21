package audio.own.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens

/**
 * The line above the mini player while only downloads play. Each app passes its own words
 * ("No network · downloaded music only", …) and, when offline by choice, the button that ends it.
 */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.md),
        shape = RoundedCornerShape(12.dp),
        color = palette.backgroundAlt.copy(alpha = 0.95f),
    ) {
        Row(
            Modifier.padding(start = Tokens.Spacing.md, end = Tokens.Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        ) {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = palette.muted, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.weight(1f).padding(vertical = Tokens.Spacing.sm))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
