package audio.own.podcastplayer.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.ui.Page
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import java.io.File

/**
 * What the app wrote to its log since it started — failed requests, sign-ins, addresses — to read
 * and to share as a text file with a bug report. Addresses are logged without their query.
 */
@Composable
fun LogsContent(lines: List<AppLog.Line>, onBack: () -> Unit, onShare: () -> Unit) {
    val palette = LocalPalette.current
    Page(
        title = stringResource(R.string.logs),
        onBack = onBack,
        scrolls = false,
        actions = {
            if (lines.isNotEmpty()) IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_log)) }
        },
    ) {
        if (lines.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Filled.Article, stringResource(R.string.nothing_logged), stringResource(R.string.nothing_logged_text))
        } else {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(lines.asReversed()) { line ->
                        Column(Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)) {
                            Text(
                                "${AppLog.clock(line.at)} · ${line.category}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.level == AppLog.Level.ERROR) palette.error else palette.muted,
                            )
                            Text(line.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        HorizontalDivider(color = palette.border)
                    }
                }
            }
        }
    }
}

/** The log as a text file through the share sheet; the file is readable only through the grant. */
fun shareLog(context: Context, log: AppLog, header: String) {
    val file = log.export(File(context.cacheDir, "shared/own-podcast-log.txt"), header)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "own.podcast log")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_log)))
}
