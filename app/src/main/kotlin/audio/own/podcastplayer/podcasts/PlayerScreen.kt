package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import audio.own.core.designsystem.components.MiniPlayerBar
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.feed.Mojibake
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.SpeedText
import audio.own.podcast.library.UpNext
import audio.own.podcastplayer.R
import audio.own.podcastplayer.ui.asHeading
import audio.own.podcastplayer.playback.PodcastPlayer
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Forward
import audio.own.core.strings.R as SR

/** What the player's controls do; [PodcastModel] and its player in the app. */
interface PlayerActions {
    fun toggle() {}
    fun skipBack() {}
    fun skipForward() {}
    fun seekTo(positionMs: Long) {}
    fun setRate(rate: Float) {}
    fun sleepAfter(preset: SleepTimer.Preset) {}
    fun sleepAtEndOfChapter() {}
    fun sleepAtEndOfEpisode() {}
    fun extendSleep() {}
    fun cancelSleep() {}
    fun stop() {}
    fun playQueued(entry: UpNext.Entry) {}
    fun removeQueued(entry: UpNext.Entry) {}
    fun moveQueued(from: Int, to: Int) {}
    fun clearQueue() {}
    fun output() {}

    companion object {
        val None = object : PlayerActions {}
    }
}

enum class PlayerSheet { SPEED, SLEEP, CHAPTERS, UP_NEXT }

/** A skip button's picture: the numbered one where Material has it, else the arrow and the number. */
@Composable
fun SkipIcon(back: Boolean, seconds: Int, size: androidx.compose.ui.unit.Dp, tint: androidx.compose.ui.graphics.Color = LocalPalette.current.foreground) {
    val numbered: ImageVector? = when {
        back && seconds == 5 -> Icons.Filled.Replay5
        back && seconds == 10 -> Icons.Filled.Replay10
        back && seconds == 30 -> Icons.Filled.Replay30
        !back && seconds == 5 -> Icons.Filled.Forward5
        !back && seconds == 10 -> Icons.Filled.Forward10
        !back && seconds == 30 -> Icons.Filled.Forward30
        else -> null
    }
    if (numbered != null) {
        Icon(numbered, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(if (back) Icons.Filled.Replay else Icons.Filled.Forward, contentDescription = null, tint = tint, modifier = Modifier.size(size))
            Text(
                "$seconds",
                color = tint,
                // Part of the picture: it keeps the icon's size whatever the font scale, or at
                // 200 % the number covers the arrow.
                fontSize = with(LocalDensity.current) { (size * 0.26f).toSp() },
                lineHeight = with(LocalDensity.current) { (size * 0.3f).toSp() },
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = size * 0.12f),
            )
        }
    }
}

/**
 * The full player: Close and Stop, the picture with the sleep badge, the episode and show, the
 * current chapter as a button, the scrubber with the time left at the speed, the transport, and
 * the tools: Speed, Sleep, Chapters, Up Next, Output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    player: PodcastPlayer.State,
    upNext: List<UpNext.Entry>,
    actions: PlayerActions,
    onClose: () -> Unit,
    showOutput: Boolean = true,
    initialSheet: PlayerSheet? = null,
    /** Settings' Play New Episodes After Up Next. */
    playsOn: Boolean = true,
) {
    val palette = LocalPalette.current
    var sheet by remember { mutableStateOf(initialSheet) }
    val focus = remember { FocusRequester() }
    Scaffold(
        // A keyboard on a tablet or a Chromebook: Space plays and pauses, Ctrl+← and Ctrl+→ skip,
        // Esc closes the player. A sheet open over it takes the keys itself.
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || sheet != null || player.item == null && event.key != Key.Escape) return@onKeyEvent false
                when {
                    event.key == Key.Spacebar -> { actions.toggle(); true }
                    event.key == Key.DirectionLeft && event.isCtrlPressed -> { actions.skipBack(); true }
                    event.key == Key.DirectionRight && event.isCtrlPressed -> { actions.skipForward(); true }
                    event.key == Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.close)) } },
                actions = {
                    if (player.item != null) {
                        IconButton(onClick = { actions.stop(); onClose() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val item = player.item
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.titleMedium, color = palette.muted)
            }
            return@Scaffold
        }
        val large = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
        val openSheet: (PlayerSheet) -> Unit = { sheet = it }
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            // A tablet on its side: the picture beside the controls, so Speed, Sleep and Up Next
            // are on screen without scrolling. The same rule as book-android's player.
            if (maxWidth >= SIDE_BY_SIDE_MIN_WIDTH && maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize().testTag("player.sideBySide"), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl, Alignment.CenterVertically),
                    ) {
                        PlayerPicture(player, if (large) 140.dp else 280.dp)
                        PlayerTitles(player, openSheet)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl, Alignment.CenterVertically),
                    ) {
                        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PlayerControls(player, upNext, actions, showOutput, openSheet)
                        }
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
                ) {
                    PlayerPicture(player, if (large) 140.dp else 280.dp)
                    PlayerTitles(player, openSheet)
                    PlayerControls(player, upNext, actions, showOutput, openSheet)
                }
            }
        }
    }
    when (sheet) {
        PlayerSheet.SPEED -> SpeedSheet(player.rate, actions::setRate) { sheet = null }
        PlayerSheet.SLEEP -> SleepSheet(player, actions) { sheet = null }
        PlayerSheet.CHAPTERS -> ChaptersSheet(player, actions) { sheet = null }
        PlayerSheet.UP_NEXT -> UpNextSheet(upNext, actions, playsOn) { sheet = null }
        null -> Unit
    }
}

/** The picture with the sleep timer's badge. */
@Composable
private fun PlayerPicture(player: PodcastPlayer.State, size: androidx.compose.ui.unit.Dp) {
    val palette = LocalPalette.current
    val item = player.item ?: return
    Box(Modifier.padding(top = Tokens.Spacing.sm)) {
        Artwork(item.episode.imageUrl ?: player.show?.imageUrl, size)
        player.sleepRemainingSecs?.let { remaining ->
            Surface(
                Modifier.align(Alignment.TopEnd).padding(Tokens.Spacing.sm),
                shape = CircleShape,
                color = palette.backgroundAlt.copy(alpha = 0.92f),
            ) {
                Row(Modifier.padding(horizontal = Tokens.Spacing.sm, vertical = Tokens.Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bedtime, contentDescription = stringResource(R.string.sleep_timer), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(ProgressText.clock((remaining * 1000).toLong()), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The episode, the show and the current chapter as a button. */
@Composable
private fun PlayerTitles(player: PodcastPlayer.State, onSheet: (PlayerSheet) -> Unit) {
    val palette = LocalPalette.current
    val item = player.item ?: return
    Column(Modifier.padding(horizontal = Tokens.Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
        Text(Mojibake.repair(item.episode.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading())
        player.show?.let { Text(Mojibake.repair(it.title), style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (player.hasChapters) {
            TextButton(onClick = { onSheet(PlayerSheet.CHAPTERS) }) {
                Text(player.currentChapter?.title ?: stringResource(R.string.chapters), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The scrubber, the transport and the tools. */
@Composable
private fun PlayerControls(player: PodcastPlayer.State, upNext: List<UpNext.Entry>, actions: PlayerActions, showOutput: Boolean, onSheet: (PlayerSheet) -> Unit) {
    val palette = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
        Scrubber(player, actions)
        Transport(player, actions)
        Tools(player, upNext.isNotEmpty(), showOutput, onSheet = onSheet, onOutput = actions::output)
        if (player.problem == PodcastPlayer.Problem.CANT_PLAY) {
            Text(stringResource(R.string.error_cant_play), color = palette.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Tokens.Spacing.xl))
        }
    }
}

/** The narrowest window, in landscape, that puts the picture beside the controls. */
private val SIDE_BY_SIDE_MIN_WIDTH = 840.dp

@Composable
private fun Scrubber(player: PodcastPlayer.State, actions: PlayerActions) {
    val palette = LocalPalette.current
    val res = LocalResources.current
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = maxOf(player.durationMs, 1)
    val shown = scrubbing?.toLong() ?: player.positionMs.coerceIn(0, duration)
    val description = stringResource(R.string.position_of, ProgressText.clock(shown), ProgressText.clock(player.durationMs))
    Column(Modifier.padding(horizontal = Tokens.Spacing.xl), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Slider(
            value = shown.toFloat(),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { actions.seekTo(it.toLong()) }
                scrubbing = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = player.durationMs > 0,
            modifier = Modifier.semantics { stateDescription = description },
        )
        Row(Modifier.fillMaxWidth()) {
            val digits = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(ProgressText.clock(shown), style = digits, color = palette.muted)
            Spacer(Modifier.weight(1f))
            if (player.durationMs > 0) Text(ProgressText.leftAtSpeed(res, player.durationMs - shown, player.rate), style = digits, color = palette.muted)
        }
        if (player.waitingForSignal) {
            // Not an error: it plays on by itself when the signal returns.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.WifiOff, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.no_signal), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        } else {
            val saved = player.savedAheadMs?.let { it / 60_000 }?.takeIf { it > 0 }
            if (saved != null) {
                // How long the episode plays on with no signal: worth knowing before the metro.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.DownloadForOffline, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
                    Text(pluralStringResource(R.plurals.minutes_saved, saved.toInt(), saved.toInt()), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = palette.muted)
                }
            }
        }
    }
}

@Composable
private fun Transport(player: PodcastPlayer.State, actions: PlayerActions) {
    val haptics = LocalHapticFeedback.current
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxl), verticalAlignment = Alignment.CenterVertically) {
        val back = stringResource(R.string.skip_back_seconds, player.skip.backSecs)
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); actions.skipBack() }, modifier = Modifier.size(56.dp).semantics { contentDescription = back }) {
            SkipIcon(back = true, seconds = player.skip.backSecs, size = 36.dp)
        }
        val playLabel = stringResource(if (player.isPlaying) SR.string.pause else SR.string.play)
        // The label is on the button, so it is read while the ring of a load stands in for the icon.
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); actions.toggle() }, modifier = Modifier.size(80.dp).semantics { contentDescription = playLabel }) {
            if (player.isLoading) {
                CircularProgressIndicator(Modifier.size(48.dp))
            } else {
                Icon(if (player.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, contentDescription = null, tint = palette.foreground, modifier = Modifier.size(72.dp))
            }
        }
        val forward = stringResource(R.string.skip_forward_seconds, player.skip.forwardSecs)
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); actions.skipForward() }, modifier = Modifier.size(56.dp).semantics { contentDescription = forward }) {
            SkipIcon(back = false, seconds = player.skip.forwardSecs, size = 36.dp)
        }
    }
}

@Composable
private fun Tools(player: PodcastPlayer.State, queueNotEmpty: Boolean, showOutput: Boolean, onSheet: (PlayerSheet) -> Unit, onOutput: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.sm)) {
        val speed = SpeedText.text(player.rate)
        Tool(stringResource(R.string.speed), stringResource(R.string.speed_label, SpeedText.number(player.rate)), tinted = player.rate != 1f, onClick = { onSheet(PlayerSheet.SPEED) }) {
            Text(speed, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
        Tool(stringResource(R.string.sleep), stringResource(R.string.sleep_timer), tinted = player.sleeping, onClick = { onSheet(PlayerSheet.SLEEP) }) {
            Icon(if (player.sleeping) Icons.Filled.Bedtime else Icons.Outlined.Bedtime, contentDescription = null)
        }
        if (player.hasChapters) {
            Tool(stringResource(R.string.chapters), stringResource(R.string.chapters), tinted = false, onClick = { onSheet(PlayerSheet.CHAPTERS) }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
            }
        }
        Tool(stringResource(R.string.up_next), stringResource(R.string.up_next), tinted = queueNotEmpty, onClick = { onSheet(PlayerSheet.UP_NEXT) }) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
        }
        if (showOutput) {
            Tool(stringResource(R.string.output), stringResource(R.string.output), tinted = false, onClick = onOutput) {
                Icon(Icons.Filled.SpeakerGroup, contentDescription = null)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tool(caption: String, label: String, tinted: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val color = if (tinted) palette.accent else palette.foreground
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = Tokens.minTarget)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(vertical = Tokens.Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        ) {
            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { icon() }
            // Shrinks to fit its share of the row rather than end in "…" in Czech or with larger text.
            val style = MaterialTheme.typography.labelSmall
            Text(
                caption,
                style = style,
                color = color,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
                modifier = Modifier.testTag("player.tool.label"),
            )
        }
    }
}

// region Sheets

/** Speed: 0.05 steps either way, with the common speeds one tap away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(rate: Float, onRate: (Float) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = AppBrand.radii.sheetTop, topEnd = AppBrand.radii.sheetTop)) {
        SpeedBody(rate, onRate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedBody(rate: Float, onRate: (Float) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
        Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
        Text(SpeedText.text(rate), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
            FilledIconButton(onClick = { onRate(rate - 0.05f) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.RemoveCircle, contentDescription = stringResource(R.string.slower)) }
            FilledIconButton(onClick = { onRate(rate + 0.05f) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.AddCircle, contentDescription = stringResource(R.string.faster)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm), maxItemsInEachRow = 4) {
            for (preset in SPEED_PRESETS) {
                val chosen = kotlin.math.abs(rate - preset) < 0.01f
                OutlinedButton(
                    onClick = { onRate(preset) },
                    colors = if (chosen) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = palette.accent.copy(alpha = 0.15f), contentColor = palette.accent) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.widthIn(min = 72.dp).heightIn(min = Tokens.minTarget),
                ) { Text(SpeedText.text(preset), fontWeight = FontWeight.SemiBold) }
            }
        }
        Text(stringResource(R.string.speed_footnote), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
    }
}

/** The sleep timer: a countdown, the end of the chapter, or the end of the episode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(player: PodcastPlayer.State, actions: PlayerActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = AppBrand.radii.sheetTop, topEnd = AppBrand.radii.sheetTop)) {
        SleepBody(player, actions, onDismiss)
    }
}

@Composable
fun SleepBody(player: PodcastPlayer.State, actions: PlayerActions, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl)) {
        Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm))
        if (player.sleeping) {
            val remaining = player.sleepRemainingSecs
            when {
                remaining != null -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md)) {
                        Text(stringResource(R.string.stops_in), modifier = Modifier.weight(1f))
                        Text(ProgressText.clock((remaining * 1000).toLong()), color = palette.muted, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                    }
                    SheetRow(stringResource(R.string.add_10_minutes)) { actions.extendSleep() }
                }
                player.sleepAtEndOfChapter -> Text(stringResource(R.string.stops_end_of_chapter), modifier = Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md))
                else -> Text(stringResource(R.string.stops_end_of_episode), modifier = Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md))
            }
            SheetRow(stringResource(SR.string.turn_off), color = palette.error) { actions.cancelSleep(); onDismiss() }
            HorizontalDivider(color = palette.border)
        }
        for (preset in SleepTimer.Preset.entries) {
            SheetRow(stringResource(R.string.minutes_count, preset.minutes)) { actions.sleepAfter(preset); onDismiss() }
        }
        if (player.hasChapters) SheetRow(stringResource(R.string.end_of_chapter)) { actions.sleepAtEndOfChapter(); onDismiss() }
        SheetRow(stringResource(R.string.end_of_episode)) { actions.sleepAtEndOfEpisode(); onDismiss() }
    }
}

@Composable
private fun SheetRow(label: String, color: androidx.compose.ui.graphics.Color = LocalPalette.current.foreground, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md),
    )
}

/** The episode's chapters, the current one marked; a tap plays from there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(player: PodcastPlayer.State, actions: PlayerActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = AppBrand.radii.sheetTop, topEnd = AppBrand.radii.sheetTop)) {
        ChaptersBody(player, actions, onDismiss)
    }
}

@Composable
fun ChaptersBody(player: PodcastPlayer.State, actions: PlayerActions, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val current = player.currentChapter
    val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(player.chapters.indexOf(current) - 2, 0))
    Column {
        Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm))
        LazyColumn(state = list, modifier = Modifier.padding(bottom = Tokens.Spacing.xl)) {
            itemsIndexed(player.chapters) { _, chapter ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.minTarget)
                        .clickable { actions.seekTo(chapter.startMs); if (!player.isPlaying) actions.toggle(); onDismiss() }
                        .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    if (chapter == current) Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.playing), tint = palette.accent, modifier = Modifier.size(18.dp))
                    Text(Mojibake.repair(chapter.title), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ProgressText.clock(chapter.startMs), color = palette.muted, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

/**
 * What plays after this episode, across every source: a tap plays one now, the arrows reorder,
 * the bin removes. When it runs out, New Episodes play on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(entries: List<UpNext.Entry>, actions: PlayerActions, playsOn: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = AppBrand.radii.sheetTop, topEnd = AppBrand.radii.sheetTop)) {
        UpNextBody(entries, actions, playsOn, onDismiss)
    }
}

@Composable
fun UpNextBody(entries: List<UpNext.Entry>, actions: PlayerActions, playsOn: Boolean = true, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(bottom = Tokens.Spacing.xl)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.up_next), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().weight(1f))
            if (entries.isNotEmpty()) TextButton(onClick = actions::clearQueue) { Text(stringResource(R.string.clear_up_next), color = palette.error) }
        }
        if (entries.isEmpty()) {
            // The empty state fills the height it is given, and a sheet offers the whole screen's:
            // without a scroll container it centred itself below the visible half of the sheet.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                audio.own.core.designsystem.components.EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = stringResource(R.string.nothing_up_next),
                    text = stringResource(R.string.nothing_up_next_text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    // At accessibility font sizes the buttons go under the words, as the episode rows do.
                    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
                    val words: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier) {
                            entry.show?.let { Text(Mojibake.repair(it.title), style = MaterialTheme.typography.labelMedium, color = palette.muted, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis) }
                            Text(Mojibake.repair(entry.episode.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = if (stacked) 4 else 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val buttons: @Composable () -> Unit = {
                        IconButton(onClick = { actions.moveQueued(index, index - 1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up)) }
                        IconButton(onClick = { actions.moveQueued(index, index + 1) }, enabled = index < entries.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down)) }
                        IconButton(onClick = { actions.removeQueued(entry) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(SR.string.remove)) }
                    }
                    val row = Modifier.fillMaxWidth().clickable { actions.playQueued(entry); onDismiss() }.padding(start = Tokens.Spacing.xl, end = Tokens.Spacing.sm, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm)
                    if (stacked) {
                        Column(row, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                                Artwork(entry.episode.imageUrl ?: entry.show?.imageUrl, 44.dp)
                                Spacer(Modifier.weight(1f))
                                buttons()
                            }
                            words(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                            Artwork(entry.episode.imageUrl ?: entry.show?.imageUrl, 44.dp)
                            words(Modifier.weight(1f))
                            buttons()
                        }
                    }
                }
            }
            if (playsOn) {
                Text(stringResource(R.string.then_new_episodes), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm))
            }
        }
    }
}

// endregion

/** Above the navigation bar while an episode is loaded: picture, title, time left, play/pause, skip. */
@Composable
fun MiniPlayer(player: PodcastPlayer.State, onOpen: () -> Unit, onToggle: () -> Unit, onSkipForward: () -> Unit, modifier: Modifier = Modifier) {
    val item = player.item ?: return
    val res = LocalResources.current
    val subtitle = when {
        player.waitingForSignal -> stringResource(R.string.no_signal)
        player.durationMs > 0 -> ProgressText.leftAtSpeed(res, player.durationMs - player.positionMs, player.rate)
        else -> player.show?.title
    }
    val cover = item.episode.imageUrl ?: player.show?.imageUrl
    MiniPlayerBar(
        cover = cover,
        placeholder = Icons.Filled.Mic,
        title = Mojibake.repair(item.episode.title),
        subtitle = subtitle,
        isPlaying = player.isPlaying,
        isLoading = player.isLoading,
        progress = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f,
        onToggle = onToggle,
        secondaryIcon = skipVector(player.skip.forwardSecs),
        secondaryLabel = stringResource(R.string.skip_forward_seconds, player.skip.forwardSecs),
        onSecondary = onSkipForward,
        onOpen = onOpen,
        // Clears the navigation bar wherever nothing else does: with the tabs as a rail, and on the
        // pages over the tabs. The bottom bar on phones has taken the inset already.
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = Tokens.Spacing.xs),
        progressAtTop = false,
    )
}

private fun skipVector(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Filled.Forward5
    10 -> Icons.Filled.Forward10
    30 -> Icons.Filled.Forward30
    else -> Icons.Filled.Forward
}

val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
