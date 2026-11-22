package audio.own.podcastplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R

/**
 * A page of the app below the tabs: a top bar with back (or Cancel for a form, as the iOS
 * sheets have), the title, and actions; the content scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    closeIsCancel: Boolean = false,
    actions: @Composable () -> Unit = {},
    scrolls: Boolean = true,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading()) },
                navigationIcon = {
                    if (onBack != null) {
                        if (closeIsCancel) {
                            IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                        } else {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).imePadding(), contentAlignment = Alignment.TopCenter) {
            val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            Column(
                if (scrolls) column.verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xxl) else column.fillMaxSize(),
                content = content,
            )
        }
    }
}

/**
 * A tab's page: its bar with the title (and the source switch, Offline Mode, Settings where the
 * tab has them), the mini player above the navigation bar, and the content filling the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /** Lists stay a readable width on a tablet; a grid that fills the width passes null. */
    maxContentWidth: androidx.compose.ui.unit.Dp? = LIST_MAX_WIDTH,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading()) },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            Box(if (maxContentWidth != null) Modifier.widthIn(max = maxContentWidth).fillMaxSize() else Modifier.fillMaxSize()) { content() }
        }
    }
}

/** How wide a list of episodes grows on a tablet or an unfolded foldable. */
val LIST_MAX_WIDTH = 840.dp

/** The text button of a top bar that confirms a form ("Sign In"), or a spinner while it works. */
@Composable
fun BarAction(label: String, enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    if (working) {
        Box(Modifier.size(Tokens.minTarget), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    } else {
        TextButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

/** A failure under a form, with the warning sign iOS puts in front of it. */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(20.dp))
        Text(text, color = palette.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A row of a settings card that is a button: text in [color], an optional icon, a spinner. */
@Composable
fun ButtonRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Color.Unspecified,
    working: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val tint = if (!enabled) palette.muted else if (color == Color.Unspecified) palette.foreground else color
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.minTarget)
            .then(if (onClick != null && enabled && !working) Modifier.clickableRow(onClick) else Modifier)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint)
        Text(label, color = tint, modifier = Modifier.weight(1f))
        if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

/** Text in a card's row, muted: a note, a status line. */
@Composable
fun NoteRow(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, icon: ImageVector? = null) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        val tint = if (color == Color.Unspecified) palette.muted else color
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Two lines in a row: the main one and a muted one under it. */
@Composable
fun TwoLines(title: String, subtitle: String?, modifier: Modifier = Modifier, titleColor: Color = Color.Unspecified) {
    Column(modifier) {
        Text(title, color = if (titleColor == Color.Unspecified) LocalPalette.current.foreground else titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

val CardContentPadding = PaddingValues(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md)

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

/** A divider inside a settings card, indented past the icons as iOS does. */
@Composable
fun RowDivider(start: androidx.compose.ui.unit.Dp = Tokens.Spacing.lg) {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = start), color = LocalPalette.current.border)
}

/**
 * [audio.own.core.designsystem.components.SettingsSection] with the text colour on its rows: its
 * card is backgroundAlt, which Material takes for surfaceVariant and gives muted content.
 */
@Composable
fun Section(header: String?, modifier: Modifier = Modifier, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    audio.own.core.designsystem.components.SettingsSection(header = header, modifier = modifier, footer = footer) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides LocalPalette.current.foreground) {
            Column(content = content)
        }
    }
}

/** TalkBack's headings: a page's title and its sections', to jump between with the heading gesture. */
fun Modifier.asHeading(): Modifier = semantics { heading() }
