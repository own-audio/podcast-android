package audio.own.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R

/** Loading, failed or loaded — the iOS `LoadStateView`. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val message: String?) : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
}

@Composable
fun <T> LoadStateView(state: LoadState<T>, onRetry: () -> Unit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadState.Failed -> EmptyState(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.couldnt_load),
            text = state.message,
            actionLabel = stringResource(R.string.try_again),
            onAction = onRetry,
            modifier = modifier,
        )
        is LoadState.Loaded -> content(state.value)
    }
}

/** A large symbol, a title and an explanation: the iOS `ContentUnavailableView`. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Box(modifier.fillMaxSize().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = palette.muted, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (text != null) Text(text, color = palette.muted, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = Tokens.Spacing.sm)) { Text(actionLabel) }
            }
        }
    }
}

/** The shared "That Didn't Work" dialog. */
@Composable
fun ErrorAlert(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
