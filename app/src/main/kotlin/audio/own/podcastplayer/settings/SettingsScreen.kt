package audio.own.podcastplayer.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import audio.own.core.designsystem.components.AccentColorPicker
import audio.own.podcastplayer.ui.Section
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.servers.ServerRecord
import audio.own.core.strings.R
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.onboarding.SOURCE_URL
import audio.own.podcastplayer.servers.ConnectionState
import audio.own.podcastplayer.servers.ServerKindIcon
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.kindTitle
import audio.own.podcastplayer.ui.ButtonRow
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.TwoLines
import audio.own.podcastplayer.app.PlaybackSettings
import audio.own.podcastplayer.podcasts.ChoiceLine
import audio.own.podcastplayer.podcasts.SwitchLine
import audio.own.player.spoken.SpeedText
import java.io.File

/** The in-app language, on top of the system's per-app language setting. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CZECH("cs"),
    ;

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
            return entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
        }

        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
            )
        }
    }
}

/** Each language's own name: not translated. */
private fun AppLanguage.endonym(system: String) = when (this) {
    AppLanguage.SYSTEM -> system
    AppLanguage.ENGLISH -> "English"
    AppLanguage.CZECH -> "Čeština"
}

data class SettingsUi(
    val servers: List<ServerRecord>,
    val connections: Map<String, ConnectionState> = emptyMap(),
    /** Each server's own icon, once fetched. */
    val icons: Map<String, File> = emptyMap(),
    val offlineMode: Boolean = false,
    val playback: PlaybackSettings = PlaybackSettings(),
    val accent: AccentChoice,
    val language: AppLanguage,
    /** "1.0.0 (123)". */
    val version: String,
)

@Composable
fun SettingsContent(
    ui: SettingsUi,
    onBack: () -> Unit,
    onOpenServer: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onOfflineMode: (Boolean) -> Unit,
    onPlayback: (PlaybackSettings) -> Unit,
    onAccent: (AccentChoice) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onLicences: () -> Unit,
    onLogs: () -> Unit,
) {
    var removing by remember { mutableStateOf<ServerRecord?>(null) }
    var choosingLanguage by remember { mutableStateOf(false) }
    val links = LocalUriHandler.current
    Page(title = stringResource(R.string.settings), onBack = onBack) {
        Section(header = stringResource(R.string.servers), footer = stringResource(AppR.string.servers_footer)) {
            // own.audio first, as the source switch lists them.
            ui.servers.sortedBy { if (it.kind == ServerKinds.OWN_AUDIO) 0 else 1 }.forEach { server ->
                ServerRow(server, ui.connections[server.id], ui.icons[server.id], onOpen = { onOpenServer(server.id) }, onRemove = { removing = server })
                RowDivider(start = 64.dp)
            }
            ButtonRow(stringResource(R.string.add_a_server), onClick = onAddServer, icon = Icons.Filled.Add, color = MaterialTheme.colorScheme.primary)
        }

        // Downloads, Download on Wi-Fi Only and Keep at Most join Offline Mode with the downloads.
        Section(header = null, footer = stringResource(AppR.string.downloads_offline_footer)) {
            SwitchLine(stringResource(AppR.string.offline_mode), ui.offlineMode, onOfflineMode)
        }

        PlaybackSection(ui.playback, onPlayback)
        StreamingSection(ui.playback, onPlayback)

        AccentColorPicker(selected = ui.accent, onSelect = onAccent)

        Section(header = null) {
            val system = stringResource(R.string.language_system)
            ValueRow(stringResource(R.string.language), ui.language.endonym(system), onClick = { choosingLanguage = true })
        }

        Section(header = stringResource(R.string.about), footer = stringResource(AppR.string.about_footer)) {
            ValueRow(stringResource(R.string.version), ui.version, onClick = null)
            RowDivider()
            ButtonRow(stringResource(R.string.source_code_and_licence), onClick = { links.openUri(SOURCE_URL) }, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.privacy), onClick = { links.openUri("$SOURCE_URL/blob/main/PRIVACY.md") }, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.licences), onClick = onLicences, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.logs), onClick = onLogs, color = MaterialTheme.colorScheme.primary)
        }
    }

    removing?.let { server ->
        RemoveServerDialog(server, onConfirm = { onRemoveServer(server.id); removing = null }, onDismiss = { removing = null })
    }
    if (choosingLanguage) {
        LanguageDialog(ui.language, onChoose = { onLanguage(it); choosingLanguage = false }, onDismiss = { choosingLanguage = false })
    }
}

/** Skip intervals, the speed episodes start at, smart rewind, and playing on into New Episodes. */
@Composable
private fun PlaybackSection(settings: PlaybackSettings, onChange: (PlaybackSettings) -> Unit) {
    Section(header = stringResource(AppR.string.playback)) {
        val seconds: @Composable (Int) -> String = { stringResource(AppR.string.seconds_short, it) }
        ChoiceLine(stringResource(AppR.string.skip_back), PlaybackSettings.SKIP_CHOICES, settings.skipBackSecs, seconds) { onChange(settings.copy(skipBackSecs = it)) }
        RowDivider()
        ChoiceLine(stringResource(AppR.string.skip_forward), PlaybackSettings.SKIP_CHOICES, settings.skipForwardSecs, seconds) { onChange(settings.copy(skipForwardSecs = it)) }
        RowDivider()
        ChoiceLine(stringResource(AppR.string.speed), PlaybackSettings.SPEED_CHOICES, settings.defaultRate, { SpeedText.text(it) }) { onChange(settings.copy(defaultRate = it)) }
        RowDivider()
        SwitchLine(stringResource(AppR.string.smart_rewind), settings.smartRewind) { onChange(settings.copy(smartRewind = it)) }
        RowDivider()
        SwitchLine(stringResource(AppR.string.play_new_after_up_next), settings.continueWithNewEpisodes) { onChange(settings.copy(continueWithNewEpisodes = it)) }
    }
}

/** What plays on mobile data, and how much of a stream is kept ahead on the device. */
@Composable
private fun StreamingSection(settings: PlaybackSettings, onChange: (PlaybackSettings) -> Unit) {
    val footer = stringResource(AppR.string.streaming_footer_downloaded_only) + "\n\n" + stringResource(AppR.string.streaming_footer_keep_ahead)
    Section(header = stringResource(AppR.string.streaming), footer = footer) {
        ChoiceLine(
            stringResource(AppR.string.on_mobile_data),
            PlaybackSettings.MobileData.entries,
            settings.mobileData,
            { stringResource(if (it == PlaybackSettings.MobileData.STREAM) AppR.string.stream else AppR.string.downloaded_only) },
        ) { onChange(settings.copy(mobileData = it)) }
        RowDivider()
        ChoiceLine(stringResource(AppR.string.keep_ahead), PlaybackSettings.KEEP_AHEAD_CHOICES, settings.keepAheadMinutes, { stringResource(AppR.string.minutes_count, it) }) {
            onChange(settings.copy(keepAheadMinutes = it))
        }
    }
}

/** A server in the list: a tap opens its page, a swipe or its menu removes it, after asking. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(server: ServerRecord, connection: ConnectionState?, icon: File?, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
            swipe.reset()
        }
    }
    val palette = LocalPalette.current
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.remove), color = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.backgroundAlt)
                .heightIn(min = 60.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            Box {
                ServerKindIcon(server.kind, size = 32.dp, icon = icon)
                StatusDot(connection, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
            }
            TwoLines(server.displayName, "${kindTitle(server.kind)} · ${server.auth.userName}", Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = palette.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove), color = palette.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

/** Green when an address answers, orange when none does, grey while unknown. */
@Composable
private fun StatusDot(connection: ConnectionState?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val (color, label) = when (connection) {
        is ConnectionState.Connected -> palette.success to AppR.string.status_answers
        ConnectionState.Unreachable -> Caution to AppR.string.status_unreachable
        else -> palette.muted to AppR.string.status_unknown
    }
    val description = stringResource(label)
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(palette.backgroundAlt)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}

/** Orange for "not now", as iOS's status colours. */
private val Caution = androidx.compose.ui.graphics.Color(0xFFFF9500)

/** gPodder only kept the shows in step; the other kinds' shows came from the server. */
@Composable
fun RemoveServerDialog(server: ServerRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_server_question, server.displayName)) },
        text = { Text(stringResource(if (server.kind == ServerKinds.GPODDER) AppR.string.remove_server_shows_stay else AppR.string.remove_server_shows_leave)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove), color = LocalPalette.current.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageDialog(current: AppLanguage, onChoose: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    val system = stringResource(R.string.language_system)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.minTarget)
                            .selectable(selected = language == current, role = Role.RadioButton) { onChoose(language) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Text(language.endonym(system), modifier = Modifier.padding(start = Tokens.Spacing.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
