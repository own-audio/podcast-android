package audio.own.podcastplayer.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.core.designsystem.components.LoadStateView
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.components.SwitchRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.core.strings.R
import audio.own.kit.ownaudio.ListeningStats
import audio.own.podcastplayer.app.forView
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.ui.NoteRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningContent(
    state: ListeningState,
    onBack: () -> Unit,
    onRange: (StatsRange) -> Unit,
    onRetry: () -> Unit,
    onShared: (Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    Page(title = stringResource(R.string.listening), onBack = onBack, scrolls = false) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm)) {
            StatsRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = state.range == range,
                    onClick = { onRange(range) },
                    shape = SegmentedButtonDefaults.itemShape(index, StatsRange.entries.size),
                    icon = {},
                ) { Text(stringResource(range.title), maxLines = 1) }
            }
        }
        LoadStateView(state.load.forView(), onRetry = onRetry) { listening ->
            val stats = listening.stats
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xxl)) {
                Section(header = null, footer = stringResource(R.string.listening_footer)) {
                    Line(stringResource(R.string.listened), duration(stats.listenedMs))
                    RowDivider()
                    Line(stringResource(R.string.days_in_a_row), stats.streakDays.toString())
                    if (stats.finishedBooks > 0) {
                        RowDivider()
                        Line(stringResource(R.string.audiobooks_finished), stats.finishedBooks.toString())
                    }
                }
                if (stats.buckets.any { it.listenedMs > 0 }) {
                    Section(header = null) { Chart(stats.buckets) }
                }
                Section(header = stringResource(R.string.by_kind)) {
                    val kinds = stats.byKind.filter { it.listenedMs > 0 }
                    if (kinds.isEmpty()) NoteRow(stringResource(R.string.nothing_yet_in_period))
                    kinds.forEachIndexed { index, kind ->
                        if (index > 0) RowDivider(start = 52.dp)
                        Line(stringResource(kindTitle(kind.kind)), duration(kind.listenedMs), kindIcon(kind.kind))
                    }
                }
                if (listening.top.isNotEmpty()) {
                    Section(header = stringResource(R.string.most_played)) {
                        listening.top.forEachIndexed { index, item ->
                            if (index > 0) RowDivider(start = 52.dp)
                            Line(item.title ?: stringResource(R.string.deleted), duration(item.listenedMs), kindIcon(item.kind), muted = item.title == null)
                        }
                    }
                }
                val family = state.family
                if (family != null) {
                    Section(header = stringResource(R.string.family), footer = stringResource(R.string.listening_family_footer)) {
                        family.forEachIndexed { index, member ->
                            if (index > 0) RowDivider()
                            Line(member.name, member.listenedMs?.let { duration(it) } ?: stringResource(R.string.not_shared))
                        }
                    }
                } else {
                    Section(header = null, footer = stringResource(R.string.share_with_admin_footer)) {
                        SwitchRow(stringResource(R.string.share_with_admin), checked = state.shared, onCheckedChange = { if (!state.savingShared) onShared(it) })
                    }
                }
            }
        }
    }
    state.error?.let { ErrorAlert(it.text(), onDismissError) }
}

@Composable
private fun Line(label: String, value: String, icon: ImageVector? = null, muted: Boolean = false) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = palette.muted, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.weight(1f), color = if (muted) palette.muted else palette.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, color = palette.muted, style = MaterialTheme.typography.bodyMedium.tabular())
    }
}

/** Minutes per bucket as bars, drawn by hand: no chart library for one chart. */
@Composable
private fun Chart(buckets: List<ListeningStats.Bucket>) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = LocalPalette.current.border
    val label = stringResource(R.string.minutes_per_day)
    val max = (buckets.maxOfOrNull { it.listenedMs } ?: 1L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(Tokens.Spacing.lg).semantics { contentDescription = label }) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        val slot = size.width / buckets.size
        val bar = (slot * 0.7f).coerceAtLeast(1f)
        buckets.forEachIndexed { index, bucket ->
            val h = size.height * bucket.listenedMs / max
            if (h > 0) {
                drawRoundRect(
                    accent,
                    topLeft = Offset(index * slot + (slot - bar) / 2, size.height - h),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(bar / 4, bar / 4),
                )
            }
        }
    }
}

private fun kindTitle(kind: String): Int = when (kind) {
    "book" -> R.string.kind_audiobooks
    "episode" -> R.string.kind_podcasts
    else -> R.string.kind_music
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "book" -> Icons.AutoMirrored.Filled.MenuBook
    "episode" -> Icons.Filled.Mic
    else -> Icons.Filled.MusicNote
}

/** "3 h 20 min" or "45 min", as iOS's abbreviated units. */
@Composable
fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60) else stringResource(R.string.duration_minutes, minutes)
}
