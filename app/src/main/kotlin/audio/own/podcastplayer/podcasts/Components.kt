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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CheckCircle
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
import audio.own.podcastplayer.playback.EpisodePlayer
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
)

fun PodcastState.rowUi(item: SourcedEpisode, player: EpisodePlayer.State, canPlay: Boolean = !isOffline) = EpisodeRowUi(
    item = item,
    show = show(item),
    progress = progress[item.id],
    queued = isQueued(item),
    current = player.item?.id == item.id,
    playing = player.item?.id == item.id && player.isPlaying,
    canPlay = canPlay,
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
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val playedLabel = stringResource(if (played) R.string.mark_unplayed else R.string.mark_played)
    val playNextLabel = stringResource(R.string.play_next)
    val playLaterLabel = stringResource(R.string.play_later)
    val removeQueuedLabel = stringResource(R.string.remove_from_up_next)
    val dismissLabel = stringResource(R.string.remove_from_new_episodes)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromEndToStart = inInbox,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> actions.markPlayed(ui.item, !played)
                SwipeToDismissBoxValue.EndToStart -> actions.dismiss(ui.item)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { swipe.reset() }
        },
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
                if (inInbox) MenuItem(dismissLabel, Icons.Filled.MoveToInbox) { menu = false; actions.dismiss(ui.item) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
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
