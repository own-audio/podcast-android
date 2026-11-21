package audio.own.podcastplayer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The four tabs of podcast-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    NEW_EPISODES(R.string.tab_new_episodes, Icons.Filled.Inbox),
    SHOWS(R.string.tab_shows, Icons.Filled.GridView),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    DISCOVER(R.string.tab_discover, Icons.Filled.Search),
}

@Composable
fun RootScreen() {
    MaterialTheme {
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
            Scaffold { inner ->
                Box(Modifier.fillMaxSize().padding(inner).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
