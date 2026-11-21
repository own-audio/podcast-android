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

/**
 * The four tabs, as a bottom bar on phones and a rail or drawer on tablets. Each tab draws its own
 * bar (New Episodes has the source switch, Offline Mode and Settings).
 */
@Composable
fun TabsScreen(content: @Composable (Tab) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(Tab.NEW_EPISODES) }
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
        Box(Modifier.fillMaxSize()) { content(selected) }
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
