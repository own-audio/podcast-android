package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.feed.Mojibake
import audio.own.podcast.library.Chapter
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.R
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.ui.TabBar
import audio.own.core.strings.R as SR

/**
 * The first tab: unplayed episodes that came out since each show was followed, from every source
 * (or the one on the switch), newest first. Offline Mode and Settings sit in its bar.
 */
@Composable
fun NewEpisodesContent(
    state: PodcastState,
    player: PodcastPlayer.State,
    actions: EpisodeActions,
    canPlay: (SourcedEpisode) -> Boolean,
    offlineMode: Boolean,
    onOfflineMode: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onSource: (String?) -> Unit,
    onRefresh: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    TabBar(
        title = stringResource(R.string.tab_new_episodes),
        navigationIcon = { if (state.sources.size > 1) SourceMenu(state.sources, state.activeSource, onSource) },
        actions = {
            IconToggleButton(checked = offlineMode, onCheckedChange = onOfflineMode) {
                Icon(if (offlineMode) Icons.Filled.WifiOff else Icons.Filled.Wifi, contentDescription = stringResource(R.string.offline_mode))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(SR.string.settings)) }
        },
        bottomBar = bottomBar,
    ) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            val episodes = state.visibleInbox
            LazyColumn(Modifier.fillMaxSize()) {
                state.offline?.let { reason ->
                    item("offline") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = palette.muted, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(if (reason == OfflineReason.CHOSEN) R.string.offline_mode_on else R.string.no_network),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                            )
                        }
                    }
                }
                items(episodes, key = { it.id }) { item ->
                    EpisodeRow(state.rowUi(item, player, canPlay(item)), actions, inInbox = true, modifier = Modifier.animateItem())
                }
            }
            if (episodes.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = stringResource(R.string.no_new_episodes),
                    text = stringResource(R.string.no_new_episodes_text),
                    modifier = Modifier.fillMaxSize().padding(Tokens.Spacing.xl),
                )
            }
        }
    }
}

/** All Sources, then own.audio servers, This Device, the other servers; the choice is remembered. */
@Composable
fun SourceMenu(sources: List<SourceInfo>, active: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val activeName = sources.firstOrNull { it.ref.id == active }?.name ?: stringResource(R.string.all_sources)
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Dns, contentDescription = activeName) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun Choice(id: String?, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    trailingIcon = { if (id == active) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
            Choice(null, stringResource(R.string.all_sources), Icons.Filled.Layers)
            for (source in sources) {
                Choice(source.ref.id, source.name, if (source.ref == SourceRef.Phone) Icons.Filled.PhoneAndroid else Icons.Filled.Dns)
            }
        }
    }
}

/** One episode: what it is, Play or Resume, Played, Up Next, its chapters and its notes with their links. */
@Composable
fun EpisodeContent(
    ui: EpisodeRowUi,
    sourceName: String,
    chapters: List<Chapter>,
    actions: EpisodeActions,
    onPlayFrom: (Long) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val res = LocalResources.current
    val links = LocalUriHandler.current
    val episode = ui.item.episode
    val progress = ui.progress
    val played = progress?.played == true
    audio.own.podcastplayer.ui.Page(title = "", onBack = onBack, bottomBar = bottomBar) {
        Column(Modifier.padding(horizontal = Tokens.Spacing.lg), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md), verticalAlignment = Alignment.Top) {
                Artwork(episode.imageUrl ?: ui.show?.imageUrl, 96.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                    ui.show?.let { Text(Mojibake.repair(it.title), style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                    Text(Mojibake.repair(episode.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(ProgressText.line(res, episode, progress), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = palette.muted)
                    SourceLabel(ui.item.source, sourceName)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                val resume = progress != null && !progress.played && progress.positionMs > 0
                Button(onClick = { actions.play(ui.item) }, enabled = ui.canPlay, modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(if (resume) R.string.resume else SR.string.play), modifier = Modifier.padding(start = Tokens.Spacing.xs))
                }
                OutlinedButton(onClick = { actions.markPlayed(ui.item, !played) }, modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget)) {
                    Icon(if (played) Icons.Outlined.Circle else Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(if (played) R.string.unplayed else R.string.played), modifier = Modifier.padding(start = Tokens.Spacing.xs))
                }
                DownloadButton(ui, actions)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                if (ui.queued) {
                    OutlinedButton(onClick = { actions.removeFromUpNext(ui.item) }, modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget)) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.in_up_next), modifier = Modifier.padding(start = Tokens.Spacing.xs))
                    }
                } else {
                    OutlinedButton(onClick = { actions.playNext(ui.item) }, modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_next), modifier = Modifier.padding(start = Tokens.Spacing.xs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { actions.playLater(ui.item) }, modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_later), modifier = Modifier.padding(start = Tokens.Spacing.xs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val listed = chapters.filter { it.isInTableOfContents }
            if (listed.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                    Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleMedium)
                    for (chapter in listed) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onPlayFrom(chapter.startMs) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                        ) {
                            Text(ProgressText.clock(chapter.startMs), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = palette.muted)
                            Text(Mojibake.repair(chapter.title), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            episode.description?.takeIf { it.isNotBlank() }?.let { notes ->
                SelectionContainer {
                    Text(linkedText(Mojibake.repair(notes), palette.accent), style = MaterialTheme.typography.bodyLarge, color = palette.foreground)
                }
            }
            episode.link?.let { link ->
                TextButton(onClick = { runCatching { links.openUri(link) } }) {
                    Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.episode_website), modifier = Modifier.padding(start = Tokens.Spacing.xs))
                }
            }
        }
    }
}

