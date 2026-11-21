package audio.own.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.strings.R

sealed interface DownloadState {
    data object None : DownloadState
    data class Downloading(val fraction: Float?) : DownloadState
    data object Downloaded : DownloadState
    data class Failed(val message: String?) : DownloadState
}

/** Arrow → ring with progress → check. The check asks before removing; a failure retries. */
@Composable
fun DownloadButton(state: DownloadState, onDownload: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var confirm by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    when (state) {
        DownloadState.None -> IconButton(onClick = onDownload, enabled = enabled, modifier = modifier) {
            Icon(Icons.Outlined.Download, stringResource(R.string.download))
        }
        is DownloadState.Downloading -> IconButton(onClick = onRemove, modifier = modifier) {
            Box(contentAlignment = Alignment.Center) {
                if (state.fraction == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        DownloadState.Downloaded -> IconButton(onClick = { confirm = true }, modifier = modifier) {
            Icon(Icons.Filled.CheckCircle, stringResource(R.string.downloaded), tint = palette.accent)
        }
        is DownloadState.Failed -> IconButton(onClick = onDownload, modifier = modifier) {
            Icon(Icons.Filled.ErrorOutline, stringResource(R.string.try_again), tint = palette.error)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.remove_download_question)) },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Downloads a whole album, playlist, series or show: "Downloading 3 of 10", then a filled arrow. */
@Composable
fun SetDownloadButton(done: Int, total: Int, downloading: Boolean, onDownload: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val complete = total > 0 && done >= total
    Box(modifier) {
        IconButton(onClick = { if (complete) menu = true else if (!downloading) onDownload() }) {
            when {
                downloading -> CircularProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                complete -> Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.downloaded), tint = palette.accent)
                else -> Icon(Icons.Filled.Download, stringResource(R.string.download))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove_these_downloads)) }, onClick = { menu = false; onRemove() })
        }
    }
}

/** "Downloading 3 of 10", for the line under a set's title. */
@Composable
fun downloadingText(done: Int, total: Int): String = stringResource(R.string.downloading_n_of_m, done, total)
