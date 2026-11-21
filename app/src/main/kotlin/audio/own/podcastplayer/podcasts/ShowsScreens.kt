package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.feed.Mojibake
import audio.own.podcast.library.FeedAddress
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.ShowSettings
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.R
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.onboarding.FormTextField
import audio.own.podcastplayer.playback.EpisodePlayer
import audio.own.podcastplayer.ui.BarAction
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.Section
import audio.own.podcastplayer.ui.TabBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

enum class ShowSort(val key: String, val label: Int) {
    TITLE("title", R.string.sort_title),
    NEW_EPISODES("newEpisodes", R.string.sort_new_episodes),
    RECENTLY_FOLLOWED("recentlyFollowed", R.string.sort_recently_followed);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: TITLE
    }
}

/** The shows of each source, in the switch's order, each group sorted as chosen. */
fun PodcastState.showGroups(sort: ShowSort): List<Pair<SourceRef, List<SourcedShow>>> {
    val rank = sources.withIndex().associate { it.value.ref to it.index }
    val collator = java.text.Collator.getInstance()
    val newest = HashMap<String, java.time.Instant>()
    if (sort == ShowSort.NEW_EPISODES) {
        for (item in inbox) {
            val key = "${item.source.id}/${item.episode.showId}"
            val date = item.episode.publishedAt ?: item.episode.firstSeenAt
            if (newest[key]?.isBefore(date) != false) newest[key] = date
        }
    }
    return visibleShows.groupBy { it.source }.toList()
        .sortedBy { rank[it.first] ?: Int.MAX_VALUE }
        .map { (source, shows) ->
            source to when (sort) {
                ShowSort.TITLE -> shows.sortedWith(compareBy(collator) { it.show.title })
                ShowSort.RECENTLY_FOLLOWED -> shows.sortedByDescending { it.show.followedAt }
                // The newest unplayed episode of each show first; the rest by title.
                ShowSort.NEW_EPISODES -> shows.sortedWith(
                    compareBy<SourcedShow> { newest[it.id] == null }
                        .thenByDescending { newest[it.id] }
                        .thenBy(collator) { it.show.title },
                )
            }
        }
}

/** Every show followed, grouped by where it comes from. */
@Composable
fun ShowsContent(
    state: PodcastState,
    sort: ShowSort,
    onSort: (ShowSort) -> Unit,
    onOpen: (SourcedShow) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    var sorting by remember { mutableStateOf(false) }
    TabBar(
        title = stringResource(R.string.tab_shows),
        actions = {
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_a_show)) }
            Box {
                IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort)) }
                DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                    for (choice in ShowSort.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(choice.label)) },
                            trailingIcon = { if (choice == sort) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                            onClick = { sorting = false; onSort(choice) },
                        )
                    }
                }
            }
        },
        bottomBar = bottomBar,
    ) {
        val groups = state.showGroups(sort)
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Tokens.Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                for ((source, shows) in groups) {
                    if (groups.size > 1 || source != SourceRef.Phone) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "header-${source.id}") {
                            Column(Modifier.padding(top = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                                SourceLabel(source, state.sourceName(source))
                                if (source.id in state.unreachable) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
                                        Text(stringResource(R.string.cant_be_reached), style = MaterialTheme.typography.labelMedium, color = palette.muted)
                                    }
                                }
                            }
                        }
                    }
                    items(shows, key = { it.id }) { show -> ShowTile(show, onOpen) }
                }
            }
            if (state.visibleShows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.GridView,
                    title = stringResource(R.string.no_shows),
                    text = stringResource(R.string.no_shows_text),
                    actionLabel = stringResource(R.string.add_a_show),
                    onAction = onAdd,
                    modifier = Modifier.fillMaxSize().padding(Tokens.Spacing.xl),
                )
            }
        }
    }
}

@Composable
private fun ShowTile(show: SourcedShow, onOpen: (SourcedShow) -> Unit) {
    Column(Modifier.clickable { onOpen(show) }, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Artwork(show.show.imageUrl, maxWidth)
        }
        // Two lines always, so a row of tiles lines up at the top.
        Text(
            Mojibake.repair(show.show.title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// region One show

enum class EpisodeFilter(val label: Int) { ALL(R.string.filter_all), UNPLAYED(R.string.unplayed) }

/** A show's episodes, loaded from its source; refreshed on a pull. */
class ShowViewModel(
    private val load: suspend () -> List<SourcedEpisode>,
    private val refresh: suspend () -> Unit,
) : ViewModel() {
    data class State(val episodes: List<SourcedEpisode>? = null, val failed: Boolean = false, val refreshing: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun pullToRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                refresh()
                reload()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun reload() {
        try {
            val episodes = load()
            _state.update { it.copy(episodes = episodes, failed = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // What was shown stays; only a page with nothing yet says it failed.
            _state.update { it.copy(failed = it.episodes == null) }
        }
    }
}

/** What the show page's ⋯ menu offers, as the source allows. */
data class ShowMenu(val website: String?, val feed: String?, val canUnfollow: Boolean)

/** One show: about it, its episodes, and its ⋯ menu (settings, website, share, unfollow). */
@Composable
fun ShowContent(
    show: SourcedShow,
    sourceName: String,
    page: ShowViewModel.State,
    row: (SourcedEpisode) -> EpisodeRowUi,
    actions: EpisodeActions,
    menu: ShowMenu,
    similar: List<ShowSearchResult>?,
    sharedWithFamily: Boolean,
    onSettings: () -> Unit,
    onShareFeed: (String) -> Unit,
    onUnfollow: () -> Unit,
    onOpenResult: (ShowSearchResult) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    var filter by remember { mutableStateOf(EpisodeFilter.ALL) }
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val title = Mojibake.repair(show.show.title)
    Page(
        title = title,
        onBack = onBack,
        scrolls = false,
        bottomBar = bottomBar,
        actions = {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(SR.string.more)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_settings)) }, leadingIcon = { Icon(Icons.Filled.Tune, null) }, onClick = { open = false; onSettings() })
                    menu.website?.let { site ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.website)) }, leadingIcon = { Icon(Icons.Filled.Language, null) }, onClick = { open = false; runCatching { links.openUri(site) } })
                    }
                    menu.feed?.let { feed ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_feed)) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { open = false; onShareFeed(feed) })
                    }
                    if (menu.canUnfollow) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.unfollow), color = palette.error) },
                            leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null, tint = palette.error) },
                            onClick = { open = false; confirm = true },
                        )
                    }
                }
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = page.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item("about") {
                    Column(Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                            Artwork(show.show.imageUrl, 112.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                show.show.author?.let { Text(Mojibake.repair(it), style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                                SourceLabel(show.source, sourceName)
                            }
                        }
                        show.show.description?.takeIf { it.isNotBlank() }?.let { about ->
                            Text(linkedText(Mojibake.repair(about), palette.accent), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val episodes = page.episodes
                when {
                    episodes != null -> {
                        item("filter") {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)) {
                                EpisodeFilter.entries.forEachIndexed { index, choice ->
                                    SegmentedButton(
                                        selected = filter == choice,
                                        onClick = { filter = choice },
                                        shape = SegmentedButtonDefaults.itemShape(index, EpisodeFilter.entries.size),
                                    ) { Text(stringResource(choice.label)) }
                                }
                            }
                        }
                        val shown = if (filter == EpisodeFilter.UNPLAYED) episodes.filter { row(it).progress?.played != true } else episodes
                        items(shown, key = { it.id }) { item -> EpisodeRow(row(item), actions, showsShowTitle = false) }
                    }
                    page.failed -> item("failed") {
                        Text(stringResource(R.string.episodes_cant_load), color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.lg))
                    }
                    else -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                if (!similar.isNullOrEmpty()) {
                    item("similar") { SimilarShows(similar, onOpenResult) }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.unfollow_question, title)) },
            text = { Text(stringResource(if (sharedWithFamily) R.string.unfollow_family_message else R.string.unfollow_message)) },
            confirmButton = { TextButton(onClick = { confirm = false; onUnfollow() }) { Text(stringResource(R.string.unfollow), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

/** "Similar Shows": a row of pictures, from the catalogue. */
@Composable
fun SimilarShows(shows: List<ShowSearchResult>, onOpen: (ShowSearchResult) -> Unit) {
    Column(Modifier.padding(vertical = Tokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Text(stringResource(R.string.similar_shows), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Tokens.Spacing.lg))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
            items(shows, key = { it.id }) { show ->
                Column(Modifier.width(112.dp).clickable { onOpen(show) }, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                    Artwork(show.imageUrl, 112.dp)
                    Text(show.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// endregion

// region Show settings

private val SKIP_CHOICES = listOf(0.0, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0)
private val SPEED_CHOICES: List<Float?> = listOf(null, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * What the listener chose for one show: New Episodes, Up Next, a start offset, a speed of its
 * own. Kept on this device for every source. The download rules come with downloads (P8).
 */
@Composable
fun ShowSettingsContent(initial: ShowSettings, onCancel: () -> Unit, onDone: (ShowSettings) -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    Page(
        title = stringResource(R.string.show_settings),
        onBack = onCancel,
        closeIsCancel = true,
        actions = { TextButton(onClick = { onDone(settings) }) { Text(stringResource(SR.string.done)) } },
    ) {
        Section(header = null) {
            SwitchLine(stringResource(R.string.show_in_new_episodes), settings.inInbox) { settings = settings.copy(inInbox = it) }
            SwitchLine(stringResource(R.string.add_new_to_up_next), settings.autoQueue) { settings = settings.copy(autoQueue = it) }
        }
        Section(header = null, footer = stringResource(R.string.skip_the_intro_footer)) {
            ChoiceLine(
                stringResource(R.string.skip_the_intro),
                SKIP_CHOICES,
                settings.skipIntroSecs,
                label = { secs -> if (secs == 0.0) stringResource(R.string.off) else stringResource(R.string.seconds_short, secs.toInt()) },
            ) { settings = settings.copy(skipIntroSecs = it) }
            ChoiceLine(
                stringResource(R.string.speed),
                SPEED_CHOICES,
                settings.speed,
                label = { speed -> speed?.let { audio.own.player.spoken.SpeedText.text(it) } ?: stringResource(R.string.as_in_settings) },
            ) { settings = settings.copy(speed = it) }
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onChange(!checked) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceLine(title: String, choices: List<T>, value: T, label: @Composable (T) -> String, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { open = true }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f))
            Text(label(value), color = LocalPalette.current.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in choices) {
                DropdownMenuItem(
                    text = { Text(label(choice)) },
                    trailingIcon = { if (choice == value) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onChoose(choice) },
                )
            }
        }
    }
}

// endregion

// region Add a show

/** Follows a show by its feed's address, on this device or a server that takes one. */
class AddShowViewModel(
    address: String,
    targets: List<SourceInfo>,
    active: String?,
    private val follow: suspend (String, SourceRef) -> SourcedShow,
) : ViewModel() {
    data class State(
        val address: String,
        val target: SourceRef,
        val working: Boolean = false,
        val error: UiText? = null,
        val followed: SourcedShow? = null,
    )

    private val _state = MutableStateFlow(
        State(address, targets.firstOrNull { it.ref.id == active }?.ref ?: SourceRef.Phone),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    fun setAddress(text: String) = _state.update { it.copy(address = text, error = null) }

    fun setTarget(ref: SourceRef) = _state.update { it.copy(target = ref) }

    fun submit() {
        val held = _state.value
        if (held.working) return
        val url = FeedAddress.normalized(held.address)
        if (url == null) {
            _state.update { it.copy(error = UiText(R.string.error_not_a_feed)) }
            return
        }
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val show = follow(url, held.target)
                _state.update { it.copy(working = false, followed = show) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(working = false, error = PodcastModel.message(error)) }
            }
        }
    }
}

@Composable
fun AddShowContent(
    state: AddShowViewModel.State,
    targets: List<SourceInfo>,
    onAddress: (String) -> Unit,
    onTarget: (SourceRef) -> Unit,
    onFollow: () -> Unit,
    onCancel: () -> Unit,
) {
    Page(
        title = stringResource(R.string.add_a_show),
        onBack = onCancel,
        closeIsCancel = true,
        actions = { BarAction(stringResource(R.string.follow), enabled = state.address.isNotBlank(), working = state.working, onClick = onFollow) },
    ) {
        Section(header = stringResource(R.string.feed_address), footer = stringResource(R.string.feed_address_footer)) {
            FormTextField(
                value = state.address,
                onChange = onAddress,
                label = stringResource(R.string.feed_address),
                placeholder = stringResource(R.string.feed_placeholder),
                keyboard = KeyboardType.Uri,
                ime = ImeAction.Go,
                onGo = onFollow,
            )
        }
        if (targets.size > 1) {
            Section(header = stringResource(R.string.follow_on)) {
                TargetPicker(targets, state.target, onTarget)
            }
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)) }
    }
}

/** Follow On: this device and every server that takes a feed address. */
@Composable
fun TargetPicker(targets: List<SourceInfo>, chosen: SourceRef, onTarget: (SourceRef) -> Unit) {
    for (target in targets) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onTarget(target.ref) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            SourceLabel(target.ref, target.name, Modifier.weight(1f))
            if (target.ref == chosen) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected), tint = LocalPalette.current.accent)
        }
    }
}

// endregion

/** A thin wrapper so the screens and their tests take the same lambdas. */
@Composable
fun rememberRow(state: PodcastState, player: EpisodePlayer.State, canPlay: (SourcedEpisode) -> Boolean): (SourcedEpisode) -> EpisodeRowUi =
    remember(state, player) { { item: SourcedEpisode -> state.rowUi(item, player, canPlay(item)) } }
