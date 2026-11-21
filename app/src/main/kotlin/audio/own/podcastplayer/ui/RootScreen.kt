package audio.own.podcastplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.servers.ServerRecord
import audio.own.podcastplayer.R
import audio.own.podcastplayer.servers.ServerKindIcon
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.core.strings.R as SR

/** The four tabs of podcast-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    NEW_EPISODES(R.string.tab_new_episodes, Icons.Filled.Inbox),
    SHOWS(R.string.tab_shows, Icons.Filled.GridView),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    DISCOVER(R.string.tab_discover, Icons.Filled.Search),
}

/** Why the app is offline, for New Episodes' line; null while online. */
enum class OfflineReason { CHOSEN, NO_NETWORK }

/** The source switch's choice: every source, this device's own feeds, or one server. */
sealed interface SourceChoice {
    data object All : SourceChoice
    data object ThisDevice : SourceChoice
    data class Server(val id: String) : SourceChoice
}

/**
 * The tabs. New Episodes' bar has the source switch (with more than one source; this device's
 * feeds are always one), Offline Mode and the gear that opens Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(
    servers: List<ServerRecord> = emptyList(),
    offlineMode: Boolean = false,
    offline: OfflineReason? = null,
    onOfflineMode: (Boolean) -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(Tab.NEW_EPISODES) }
    // TODO(P6): remember the choice and let New Episodes and Shows follow it.
    var source by remember { mutableStateOf<SourceChoice>(SourceChoice.All) }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                item(
                    selected = tab == selected,
                    onClick = { selected = tab },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.title)) },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (selected == Tab.NEW_EPISODES) {
                    TopAppBar(
                        title = { Text(stringResource(Tab.NEW_EPISODES.title)) },
                        navigationIcon = { if (servers.isNotEmpty()) SourceMenu(servers, source) { source = it } },
                        actions = {
                            IconToggleButton(checked = offlineMode, onCheckedChange = onOfflineMode) {
                                Icon(if (offlineMode) Icons.Filled.WifiOff else Icons.Filled.Wifi, contentDescription = stringResource(R.string.offline_mode))
                            }
                            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(SR.string.settings)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                if (selected == Tab.NEW_EPISODES) {
                    NewEpisodesPlaceholder(offline)
                } else {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** TODO(P6): the episode rows. Until then: the offline line and the empty state. */
@Composable
private fun NewEpisodesPlaceholder(offline: OfflineReason?) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        if (offline != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.WifiOff, contentDescription = null, tint = palette.muted, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(if (offline == OfflineReason.CHOSEN) R.string.offline_mode_on else R.string.no_network),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(Tokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Inbox, contentDescription = null, tint = palette.muted, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.no_new_episodes), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.no_new_episodes_text), style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.Center)
        }
    }
}

/** All Sources, then own.audio servers, This Device, the other servers. */
@Composable
private fun SourceMenu(servers: List<ServerRecord>, selected: SourceChoice, onSelect: (SourceChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (ownAudio, others) = servers.partition { it.kind == ServerKinds.OWN_AUDIO }
    @Composable
    fun Choice(choice: SourceChoice, label: String, icon: @Composable () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = icon,
            trailingIcon = { if (choice == selected) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
            onClick = { open = false; onSelect(choice) },
        )
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Dns, contentDescription = stringResource(R.string.all_sources)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Choice(SourceChoice.All, stringResource(R.string.all_sources)) { Icon(Icons.Filled.Layers, contentDescription = null) }
            ownAudio.forEach { server -> Choice(SourceChoice.Server(server.id), server.displayName) { ServerKindIcon(server.kind, size = 22.dp) } }
            Choice(SourceChoice.ThisDevice, stringResource(R.string.this_device)) { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) }
            others.forEach { server -> Choice(SourceChoice.Server(server.id), server.displayName) { ServerKindIcon(server.kind, size = 22.dp) } }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The activity behind a (possibly wrapped) context: Credential Manager shows its sheet over it. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
