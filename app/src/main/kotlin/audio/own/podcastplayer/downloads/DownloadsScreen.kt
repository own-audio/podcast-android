package audio.own.podcastplayer.downloads

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.downloads.DownloadFailure
import audio.own.kit.feed.Mojibake
import audio.own.podcast.library.SourceRef
import audio.own.podcastplayer.R
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.Artwork
import audio.own.podcastplayer.podcasts.EpisodeActions
import audio.own.podcastplayer.podcasts.PlayButton
import audio.own.podcastplayer.podcasts.PodcastState
import audio.own.podcastplayer.podcasts.SourceLabel
import audio.own.podcastplayer.podcasts.rowUi
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.Section
import audio.own.podcastplayer.ui.TabBar
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

/** One source's downloads; [name] null while everything comes from one source. */
data class DownloadGroup(val name: String?, val entries: List<EpisodeDownload>)

/**
 * The downloads by source, newest first: this device first, then the servers in the switch's
 * order, then what a removed server left behind; one unnamed group while there is only one.
 */
fun PodcastState.downloadGroups(removedServer: String): List<DownloadGroup> {
    val entries = downloads.entries.values.sortedByDescending { it.finishedAt ?: it.queuedAt }
    val bySource = entries.groupBy { it.record.ref?.serverId?.toString() ?: SourceRef.Phone.id }
    if (bySource.size <= 1) return listOf(DownloadGroup(null, entries))
    val out = mutableListOf<DownloadGroup>()
    bySource[SourceRef.Phone.id]?.let { out += DownloadGroup(sourceName(SourceRef.Phone), it) }
    val servers = sources.mapNotNull { it.ref as? SourceRef.Server }.distinct()
    for (server in servers) bySource[server.id]?.let { out += DownloadGroup(sourceName(server), it) }
    val known = setOf(SourceRef.Phone.id) + servers.map { it.id }
    val removed = bySource.filterKeys { it !in known }.values.flatten()
    if (removed.isNotEmpty()) out += DownloadGroup(removedServer, removed)
    return out
}

/**
 * What is on this device (`Episodes/DownloadsView.swift`): Downloading N and Retry N Failed;
 * every episode with its show, the source it came from and its size or progress, grouped by
 * source; a swipe or the menu removes one; Remove All Downloads with the space they take.
 * [onBack] is null for the tab, set for the page Settings opens.
 */
@Composable
fun DownloadsContent(
    state: PodcastState,
    player: PodcastPlayer.State,
    actions: EpisodeActions,
    onRetryFailed: () -> Unit,
    onRemoveAll: () -> Unit,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val title = stringResource(R.string.tab_downloads)
    if (onBack == null) {
        TabBar(title = title, bottomBar = bottomBar) { DownloadsList(state, player, actions, onRetryFailed, onRemoveAll) }
    } else {
        Page(title = title, onBack = onBack, scrolls = false, bottomBar = bottomBar) { DownloadsList(state, player, actions, onRetryFailed, onRemoveAll) }
    }
}

@Composable
private fun DownloadsList(state: PodcastState, player: PodcastPlayer.State, actions: EpisodeActions, onRetryFailed: () -> Unit, onRemoveAll: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var confirmAll by remember { mutableStateOf(false) }
    val held = state.downloads
    val groups = state.downloadGroups(stringResource(R.string.removed_server))
    if (held.entries.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.DownloadForOffline,
            title = stringResource(R.string.nothing_downloaded),
            text = stringResource(R.string.nothing_downloaded_text),
            modifier = Modifier.fillMaxSize().padding(Tokens.Spacing.xl),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (held.downloadingCount > 0 || held.failedCount > 0) {
            item("status") {
                Section(header = null) {
                    if (held.downloadingCount > 0) ValueRow(stringResource(R.string.downloading), held.downloadingCount.toString(), onClick = null)
                    if (held.downloadingCount > 0 && held.failedCount > 0) RowDivider()
                    if (held.failedCount > 0) {
                        ButtonRow(pluralStringResource(R.plurals.retry_failed, held.failedCount, held.failedCount), onClick = onRetryFailed, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        for ((index, group) in groups.withIndex()) {
            group.name?.let { name ->
                item("header-$index") {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.muted,
                        modifier = Modifier.padding(start = Tokens.Spacing.lg, end = Tokens.Spacing.lg, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs).semantics { heading() },
                    )
                }
            }
            items(group.entries, key = { it.id }) { entry ->
                val server = entry.record.ref?.serverId?.toString()
                DownloadRow(entry, state, player, actions, removedServer = server != null && state.sources.none { it.ref.id == server })
            }
        }
        item("remove-all") {
            Section(header = null, footer = stringResource(R.string.on_this_device_size, Formatter.formatShortFileSize(context, held.totalBytes))) {
                ButtonRow(stringResource(R.string.remove_all_downloads), onClick = { confirmAll = true }, color = palette.error)
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.remove_all_downloads_question)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; onRemoveAll() }) { Text(stringResource(R.string.remove_all_downloads), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

/** One download: its picture, show, title, source and size (or how far it got), and Play. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(entry: EpisodeDownload, state: PodcastState, player: PodcastPlayer.State, actions: EpisodeActions, removedServer: Boolean) {
    val item = entry.record.item ?: return
    val palette = LocalPalette.current
    val context = LocalContext.current
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val removeLabel = stringResource(SR.string.remove)
    // A download plays whatever the network does.
    val row = state.rowUi(item, player, canPlay = entry.isDone || !state.isOffline)
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) actions.removeDownload(item)
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.xl),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(Tokens.Spacing.sm))
                Text(removeLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = { actions.open(item) }, onLongClick = { menu = true })
                .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { actions.removeDownload(item); true }) }
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md), verticalAlignment = Alignment.Top) {
                Artwork(entry.record.episode.imageUrl ?: entry.record.show?.imageUrl, 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    entry.record.show?.let { Text(Mojibake.repair(it.title), style = MaterialTheme.typography.labelMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(Mojibake.repair(entry.record.episode.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // Which source it came from: the same show can be on more than one.
                    SourceLabel(item.source, if (removedServer) stringResource(R.string.removed_server) else state.sourceName(item.source))
                    Text(status(entry, context), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = if (entry.status == EpisodeDownload.Status.FAILED) palette.error else palette.muted)
                }
                PlayButton(row, actions)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(removeLabel, color = palette.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                    onClick = { menu = false; actions.removeDownload(item) },
                )
            }
        }
    }
}

private fun status(entry: EpisodeDownload, context: android.content.Context): String = when (entry.status) {
    EpisodeDownload.Status.DOWNLOADING -> context.getString(R.string.downloading_progress, ((entry.fraction ?: 0f) * 100).toInt())
    EpisodeDownload.Status.FAILED -> context.getString(failure(entry.error))
    EpisodeDownload.Status.DONE -> Formatter.formatShortFileSize(context, entry.bytes)
}

/** Why a download failed, in the listener's words. */
fun failure(code: String?): Int = when {
    code == EpisodeDownloads.STREAM_ONLY -> R.string.error_stream_only
    code == DownloadFailure.NOT_AUDIO || code?.startsWith("http_") == true -> R.string.error_no_file
    else -> R.string.download_failed
}
