package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.contentDescription
import audio.own.podcastplayer.downloads.EpisodeDownload

import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.feed.Mojibake
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.R
import audio.own.podcastplayer.playback.PodcastPlayer
import coil3.request.ImageRequest
import coil3.request.crossfade
import audio.own.core.strings.R as SR

/** A show's or an episode's picture, square, with the microphone while it loads or when there is none. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = AppBrand.radii.cover) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // A few fixed sizes, so a grid laying itself out doesn't fetch the same picture again.
    val px = with(density) { size.toPx() }.let { if (it <= 192) 192 else if (it <= 480) 480 else 900 }
    val request = remember(url, px) {
        url?.let { ImageRequest.Builder(context).data(it).size(px).crossfade(AppBrand.COVER_CROSSFADE_MS).build() }
    }
    CoverArt(request, size, Icons.Filled.Mic, modifier, radius = radius)
}

/** Which source something came from, small, under a title. */
@Composable
fun SourceLabel(source: SourceRef, name: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (source == SourceRef.Phone) Icons.Filled.PhoneAndroid else Icons.Filled.Dns, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a row or a page can do with an episode; the model in the app, nothing in previews. */
interface EpisodeActions {
    fun open(item: SourcedEpisode) {}
    fun play(item: SourcedEpisode) {}
    fun togglePlayback() {}
    fun markPlayed(item: SourcedEpisode, played: Boolean) {}
    fun playNext(item: SourcedEpisode) {}
    fun playLater(item: SourcedEpisode) {}
    fun removeFromUpNext(item: SourcedEpisode) {}
    fun dismiss(item: SourcedEpisode) {}
    fun download(item: SourcedEpisode) {}
    fun removeDownload(item: SourcedEpisode) {}
    fun retryDownload(item: SourcedEpisode) {}

    companion object {
        val None = object : EpisodeActions {}
    }
}

/** What a row shows about an episode, from the lists and the player. */
data class EpisodeRowUi(
    val item: SourcedEpisode,
    val show: Show?,
    val progress: ListeningProgress?,
    val queued: Boolean,
    val current: Boolean,
    val playing: Boolean,
    val canPlay: Boolean,
    /** On the device or on its way; null when it isn't. */
    val download: EpisodeDownload? = null,
    /** Nothing new can be downloaded now. */
    val offline: Boolean = false,
)

fun PodcastState.rowUi(item: SourcedEpisode, player: PodcastPlayer.State, canPlay: Boolean = !isOffline) = EpisodeRowUi(
    item = item,
    show = show(item) ?: downloads.entries[item.id]?.record?.show,
    progress = progress[item.id],
    queued = isQueued(item),
    current = player.item?.id == item.id,
    playing = player.item?.id == item.id && player.isPlaying,
    canPlay = canPlay,
    download = downloads.entries[item.id],
    offline = isOffline,
)

/**
 * One episode in a list: the picture, the show, the title, when and how long, and a play button.
 * Swiping right marks it played (or unplayed), left takes it out of New Episodes; the same and
 * more are in its menu, on a long press or from TalkBack's actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(ui: EpisodeRowUi, actions: EpisodeActions, modifier: Modifier = Modifier, showsShowTitle: Boolean = true, inInbox: Boolean = false) {
    val palette = LocalPalette.current
    val played = ui.progress?.played == true
    val swipe = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val playedLabel = stringResource(if (played) R.string.mark_unplayed else R.string.mark_played)
    val playNextLabel = stringResource(R.string.play_next)
    val playLaterLabel = stringResource(R.string.play_later)
    val removeQueuedLabel = stringResource(R.string.remove_from_up_next)
    val dismissLabel = stringResource(R.string.remove_from_new_episodes)
    val downloadLabel = stringResource(SR.string.download)
    val removeDownloadLabel = stringResource(SR.string.remove_download)
    val cancelDownloadLabel = stringResource(R.string.cancel_download)
    val retryDownloadLabel = stringResource(R.string.try_download_again)
    audio.own.podcastplayer.ui.SwipeActions(swipe) { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> actions.markPlayed(ui.item, !played)
            SwipeToDismissBoxValue.EndToStart -> actions.dismiss(ui.item)
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromEndToStart = inInbox,
        backgroundContent = {
            val direction = swipe.dismissDirection
            val (color, icon, label) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(palette.accent, if (played) Icons.Outlined.Circle else Icons.Filled.CheckCircle, stringResource(if (played) R.string.unplayed else R.string.played))
                SwipeToDismissBoxValue.EndToStart -> Triple(palette.muted, Icons.Filled.MoveToInbox, stringResource(R.string.dismiss))
                SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, "")
            }
            Row(
                Modifier.fillMaxSize().background(color).padding(horizontal = Tokens.Spacing.xl),
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(Tokens.Spacing.sm))
                    Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .combinedClickable(
                    onClick = { actions.open(ui.item) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .semantics {
                    customActions = buildList {
                        add(CustomAccessibilityAction(playedLabel) { actions.markPlayed(ui.item, !played); true })
                        if (ui.queued) {
                            add(CustomAccessibilityAction(removeQueuedLabel) { actions.removeFromUpNext(ui.item); true })
                        } else {
                            add(CustomAccessibilityAction(playNextLabel) { actions.playNext(ui.item); true })
                            add(CustomAccessibilityAction(playLaterLabel) { actions.playLater(ui.item); true })
                        }
                        when (ui.download?.status) {
                            null -> if (!ui.offline) add(CustomAccessibilityAction(downloadLabel) { actions.download(ui.item); true })
                            EpisodeDownload.Status.FAILED -> {
                                if (!ui.offline) add(CustomAccessibilityAction(retryDownloadLabel) { actions.retryDownload(ui.item); true })
                                add(CustomAccessibilityAction(removeDownloadLabel) { actions.removeDownload(ui.item); true })
                            }
                            EpisodeDownload.Status.DOWNLOADING -> add(CustomAccessibilityAction(cancelDownloadLabel) { actions.removeDownload(ui.item); true })
                            EpisodeDownload.Status.DONE -> add(CustomAccessibilityAction(removeDownloadLabel) { actions.removeDownload(ui.item); true })
                        }
                        if (inInbox) add(CustomAccessibilityAction(dismissLabel) { actions.dismiss(ui.item); true })
                    }
                }
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)
                .alpha(if (ui.canPlay) 1f else 0.45f),
        ) {
            val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
            val artworkUrl = ui.item.episode.imageUrl ?: ui.show?.imageUrl
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(artworkUrl, 56.dp)
                        Spacer(Modifier.weight(1f))
                        PlayButton(ui, actions)
                    }
                    Words(ui, showsShowTitle, lineLimit = Int.MAX_VALUE)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md), verticalAlignment = Alignment.Top) {
                    Artwork(artworkUrl, 56.dp)
                    Words(ui, showsShowTitle, lineLimit = 2, modifier = Modifier.weight(1f))
                    PlayButton(ui, actions)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(stringResource(SR.string.play), Icons.Filled.PlayArrow) { menu = false; actions.play(ui.item) }
                MenuItem(playedLabel, if (played) Icons.Outlined.Circle else Icons.Filled.CheckCircle) { menu = false; actions.markPlayed(ui.item, !played) }
                if (ui.queued) {
                    MenuItem(removeQueuedLabel, Icons.Filled.RemoveCircleOutline) { menu = false; actions.removeFromUpNext(ui.item) }
                } else {
                    MenuItem(playNextLabel, Icons.AutoMirrored.Filled.PlaylistPlay) { menu = false; actions.playNext(ui.item) }
                    MenuItem(playLaterLabel, Icons.AutoMirrored.Filled.PlaylistAdd) { menu = false; actions.playLater(ui.item) }
                }
                when (ui.download?.status) {
                    null -> MenuItem(downloadLabel, Icons.Filled.Download, enabled = !ui.offline) { menu = false; actions.download(ui.item) }
                    EpisodeDownload.Status.FAILED -> {
                        MenuItem(retryDownloadLabel, Icons.Filled.Refresh, enabled = !ui.offline) { menu = false; actions.retryDownload(ui.item) }
                        MenuItem(removeDownloadLabel, Icons.Filled.Delete, destructive = true) { menu = false; actions.removeDownload(ui.item) }
                    }
                    EpisodeDownload.Status.DOWNLOADING -> MenuItem(cancelDownloadLabel, Icons.Filled.Cancel, destructive = true) { menu = false; actions.removeDownload(ui.item) }
                    EpisodeDownload.Status.DONE -> MenuItem(removeDownloadLabel, Icons.Filled.Delete, destructive = true) { menu = false; actions.removeDownload(ui.item) }
                }
                if (inInbox) MenuItem(dismissLabel, Icons.Filled.MoveToInbox) { menu = false; actions.dismiss(ui.item) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) LocalPalette.current.error else androidx.compose.material3.LocalContentColor.current
    DropdownMenuItem(
        text = { Text(label, color = if (destructive) tint else Color.Unspecified) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (destructive) tint else androidx.compose.material3.LocalContentColor.current) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** Beside an episode's date: a ring while downloading, an arrow when it is on the device. */
@Composable
fun DownloadBadge(download: EpisodeDownload?) {
    val palette = LocalPalette.current
    when (download?.status) {
        EpisodeDownload.Status.DONE ->
            Icon(Icons.Filled.DownloadForOffline, contentDescription = stringResource(SR.string.downloaded), tint = palette.accent, modifier = Modifier.size(14.dp))
        EpisodeDownload.Status.DOWNLOADING -> {
            val label = stringResource(R.string.downloading)
            val fraction = download.fraction
            val modifier = Modifier.size(11.dp).semantics { contentDescription = label }
            if (fraction != null) {
                CircularProgressIndicator(progress = { fraction.coerceAtLeast(0.02f) }, modifier = modifier, strokeWidth = 1.5.dp, trackColor = palette.accent.copy(alpha = 0.25f))
            } else {
                CircularProgressIndicator(modifier = modifier, strokeWidth = 1.5.dp)
            }
        }
        EpisodeDownload.Status.FAILED ->
            Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.download_failed), tint = palette.error, modifier = Modifier.size(14.dp))
        null -> Unit
    }
}

/**
 * The download button on an episode's page: an arrow, a ring while downloading, a check when
 * done (a tap asks before removing it), a retry after a failure.
 */
@Composable
fun DownloadButton(ui: EpisodeRowUi, actions: EpisodeActions, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    val download = ui.download
    val label = when (download?.status) {
        EpisodeDownload.Status.DONE -> stringResource(SR.string.downloaded)
        EpisodeDownload.Status.DOWNLOADING -> stringResource(R.string.downloading_percent, ((download.fraction ?: 0f) * 100).toInt())
        EpisodeDownload.Status.FAILED -> stringResource(R.string.download_failed_try_again)
        null -> stringResource(SR.string.download)
    }
    androidx.compose.material3.OutlinedIconButton(
        onClick = {
            when (download?.status) {
                EpisodeDownload.Status.DONE -> confirm = true
                EpisodeDownload.Status.DOWNLOADING -> Unit
                EpisodeDownload.Status.FAILED -> actions.retryDownload(ui.item)
                null -> actions.download(ui.item)
            }
        },
        enabled = !(ui.offline && (download == null || download.status == EpisodeDownload.Status.FAILED)),
        modifier = modifier.size(Tokens.minTarget).semantics { contentDescription = label },
        colors = androidx.compose.material3.IconButtonDefaults.outlinedIconButtonColors(contentColor = LocalPalette.current.accent),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalPalette.current.border),
    ) {
        when (download?.status) {
            EpisodeDownload.Status.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = null)
            EpisodeDownload.Status.DOWNLOADING -> {
                val fraction = download.fraction
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction.coerceAtLeast(0.02f) }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                }
            }
            EpisodeDownload.Status.FAILED -> Icon(Icons.Filled.Refresh, contentDescription = null)
            null -> Icon(Icons.Filled.Download, contentDescription = null)
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(SR.string.remove_download_question)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false; actions.removeDownload(ui.item) }) {
                    Text(stringResource(SR.string.remove_download), color = LocalPalette.current.error)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

@Composable
private fun Words(ui: EpisodeRowUi, showsShowTitle: Boolean, lineLimit: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val res = LocalResources.current
    val played = ui.progress?.played == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showsShowTitle && ui.show != null) {
            Text(Mojibake.repair(ui.show.title), style = MaterialTheme.typography.labelMedium, color = palette.muted, maxLines = if (lineLimit == 2) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            Mojibake.repair(ui.item.episode.title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (played) palette.muted else palette.foreground,
            maxLines = lineLimit,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.queued) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = stringResource(R.string.in_up_next), tint = palette.accent, modifier = Modifier.size(14.dp))
            }
            DownloadBadge(ui.download)
            Text(
                ProgressText.line(res, ui.item.episode, ui.progress),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = palette.muted,
                maxLines = if (lineLimit == 2) 1 else Int.MAX_VALUE,
            )
        }
    }
}

/** Plays the episode, or pauses it when it is the one playing. */
@Composable
fun PlayButton(ui: EpisodeRowUi, actions: EpisodeActions) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            if (ui.current) actions.togglePlayback() else actions.play(ui.item)
        },
        enabled = ui.canPlay,
    ) {
        Icon(
            if (ui.playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
            contentDescription = stringResource(if (ui.playing) SR.string.pause else SR.string.play),
            tint = LocalPalette.current.accent,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** At this font scale and above, rows put the words under the picture, as iOS does at accessibility sizes. */
const val STACKED_FONT_SCALE = 1.6f
