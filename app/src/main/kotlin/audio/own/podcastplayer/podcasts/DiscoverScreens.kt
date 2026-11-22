package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.feed.Mojibake
import audio.own.podcast.library.CatalogueCategory
import audio.own.podcast.library.ShowCatalogue
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.R
import audio.own.podcastplayer.ui.asHeading
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.ui.ErrorLine
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.TabBar
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a category looks like: its name in the listener's language, a symbol and a colour, fixed per category. */
object CategoryStyle {
    fun name(key: String): Int? = when (key) {
        "arts" -> R.string.cat_arts
        "business" -> R.string.cat_business
        "comedy" -> R.string.cat_comedy
        "culture" -> R.string.cat_culture
        "education" -> R.string.cat_education
        "family", "kids" -> R.string.cat_kids
        "fiction" -> R.string.cat_fiction
        "film", "tv" -> R.string.cat_tv
        "government" -> R.string.cat_government
        "health" -> R.string.cat_health
        "history" -> R.string.cat_history
        "leisure" -> R.string.cat_leisure
        "music" -> R.string.cat_music
        "news" -> R.string.cat_news
        "religion", "spirituality" -> R.string.cat_religion
        "science" -> R.string.cat_science
        "society" -> R.string.cat_society
        "sports" -> R.string.cat_sports
        "technology" -> R.string.cat_technology
        "truecrime", "true crime" -> R.string.cat_truecrime
        else -> null
    }

    fun icon(key: String): ImageVector = when (key) {
        "arts", "culture" -> Icons.Filled.TheaterComedy
        "business" -> Icons.Filled.BusinessCenter
        "comedy" -> Icons.Filled.SentimentSatisfied
        "education" -> Icons.Filled.School
        "family", "kids" -> Icons.Filled.FamilyRestroom
        "fiction" -> Icons.AutoMirrored.Filled.MenuBook
        "film", "tv" -> Icons.Filled.Tv
        "government" -> Icons.Filled.AccountBalance
        "health" -> Icons.Filled.Favorite
        "history" -> Icons.Filled.HourglassBottom
        "leisure" -> Icons.Filled.SportsEsports
        "music" -> Icons.Filled.MusicNote
        "news" -> Icons.Filled.Newspaper
        "religion", "spirituality" -> Icons.Filled.AutoAwesome
        "science" -> Icons.Filled.Science
        "society" -> Icons.Filled.Groups
        "sports" -> Icons.AutoMirrored.Filled.DirectionsRun
        "technology" -> Icons.Filled.Memory
        "truecrime", "true crime" -> Icons.Filled.Search
        else -> Icons.Filled.GridView
    }

    private val PALETTE = listOf(
        Color(0xFF5856D6), Color(0xFFFF2D55), Color(0xFF30B0C7), Color(0xFFFF9500), Color(0xFFAF52DE), Color(0xFF007AFF),
        Color(0xFF34C759), Color(0xFFFF3B30), Color(0xFF00C7BE), Color(0xFFA2845E), Color(0xFF32ADE6),
    )
    private val KNOWN = listOf(
        "society", "culture", "education", "business", "religion", "news", "comedy", "health", "history", "technology", "science",
        "sports", "arts", "kids", "family", "fiction", "music", "leisure", "tv", "film", "government", "truecrime", "true crime", "spirituality",
    )

    fun colour(key: String): Color {
        val index = KNOWN.indexOf(key).takeIf { it >= 0 } ?: key.sumOf { it.code }
        return PALETTE[index % PALETTE.size]
    }

    @Composable
    fun label(key: String): String = name(key)?.let { stringResource(it) } ?: key.replaceFirstChar { it.uppercase() }
}

/** Discover's search and its categories, from the catalogue the app found. */
class DiscoverViewModel(
    private val catalogue: () -> ShowCatalogue,
    private val isOffline: () -> Boolean,
    private val debounceMs: Long = 450,
) : ViewModel() {
    enum class Search { IDLE, SEARCHING, DONE, FAILED }

    data class State(
        val query: String = "",
        val results: List<ShowSearchResult> = emptyList(),
        val search: Search = Search.IDLE,
        val categories: List<CatalogueCategory>? = null,
        val categoriesFailed: Boolean = false,
    ) {
        val needle: String get() = query.trim()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var searching: Job? = null
    private var catalogueKey: String? = null

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        searching?.cancel()
        // Results as the listener types, after a pause.
        searching = viewModelScope.launch {
            delay(debounceMs)
            search()
        }
    }

    fun searchNow() {
        searching?.cancel()
        searching = viewModelScope.launch { search() }
    }

    private suspend fun search() {
        val query = _state.value.needle
        if (query.isEmpty()) {
            _state.update { it.copy(results = emptyList(), search = Search.IDLE) }
            return
        }
        if (isOffline()) {
            _state.update { it.copy(search = Search.FAILED) }
            return
        }
        _state.update { it.copy(search = Search.SEARCHING) }
        try {
            val found = catalogue().search(query)
            if (query == _state.value.needle) _state.update { it.copy(results = found, search = Search.DONE) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { it.copy(search = Search.FAILED) }
        }
    }

    /** Asked again when the catalogue changes (a server added or removed). */
    fun loadCategories(key: String) {
        if (key == catalogueKey && _state.value.categories != null) return
        catalogueKey = key
        _state.update { it.copy(categories = null, categoriesFailed = false) }
        viewModelScope.launch {
            try {
                val categories = catalogue().categories()
                _state.update { it.copy(categories = categories) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(categoriesFailed = true) }
            }
        }
    }
}

/** What Discover finds on the device, without asking anyone: shows, and episodes in the lists. */
fun PodcastState.localMatches(needle: String): Pair<List<SourcedShow>, List<SourcedEpisode>> {
    if (needle.isEmpty()) return emptyList<SourcedShow>() to emptyList()
    val shows = shows.filter { it.show.title.contains(needle, ignoreCase = true) || it.show.author?.contains(needle, ignoreCase = true) == true }
    val known = inbox + upNext.mapNotNull { entry -> SourceRef.parse(entry.source)?.let { SourcedEpisode(it, entry.episode) } }
    val seen = HashSet<String>()
    val episodes = known.filter { it.episode.title.contains(needle, ignoreCase = true) && seen.add(it.id) }.take(20)
    return shows to episodes
}

/**
 * Discover: before anything is typed, following by address, shows like the ones followed and
 * the categories to browse; while typing, the listener's own shows and episodes (offline too)
 * and new shows from the catalogue.
 */
@Composable
fun DiscoverContent(
    state: PodcastState,
    discover: DiscoverViewModel.State,
    row: (SourcedEpisode) -> EpisodeRowUi,
    actions: EpisodeActions,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onAddByAddress: () -> Unit,
    onOpenShow: (SourcedShow) -> Unit,
    onOpenResult: (ShowSearchResult) -> Unit,
    onOpenCategory: (CatalogueCategory) -> Unit,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val content: @Composable () -> Unit = {
        val needle = discover.needle
        val (myShows, myEpisodes) = state.localMatches(needle)
        val catalogueName = state.catalogueServer ?: stringResource(R.string.apple_podcasts)
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = discover.query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.shows_and_episodes)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (discover.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(audio.own.core.strings.R.string.cancel)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (needle.isEmpty()) {
                    discoverHome(state, discover, catalogueName, onAddByAddress, onOpenResult, onOpenCategory)
                }
                if (myShows.isNotEmpty()) {
                    item("mine-header") { Header(stringResource(R.string.your_shows)) }
                    items(myShows, key = { "mine-${it.id}" }) { show ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenShow(show) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(show.show.imageUrl, 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(Mojibake.repair(show.show.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                SourceLabel(show.source, state.sourceName(show.source))
                            }
                        }
                    }
                }
                if (myEpisodes.isNotEmpty()) {
                    item("episodes-header") { Header(stringResource(R.string.your_episodes)) }
                    items(myEpisodes, key = { "ep-${it.id}" }) { item -> EpisodeRow(row(item), actions) }
                }
                if (needle.isNotEmpty() && discover.results.isNotEmpty()) {
                    item("results-header") { Header(stringResource(R.string.from_catalogue, catalogueName)) }
                    items(discover.results, key = { "result-${it.id}" }) { result -> ResultRow(result, onOpenResult) }
                }
            }
        }
        val hasMatches = myShows.isNotEmpty() || myEpisodes.isNotEmpty()
        if (needle.isNotEmpty() && !hasMatches) {
            when {
                discover.search == DiscoverViewModel.Search.SEARCHING && discover.results.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                discover.search == DiscoverViewModel.Search.DONE && discover.results.isEmpty() ->
                    EmptyState(Icons.Filled.Search, stringResource(R.string.no_results_for, needle), null, Modifier.fillMaxSize().padding(Tokens.Spacing.xl))
                discover.search == DiscoverViewModel.Search.FAILED ->
                    EmptyState(Icons.Filled.WifiOff, stringResource(R.string.search_unavailable), stringResource(R.string.search_needs_network), Modifier.fillMaxSize().padding(Tokens.Spacing.xl))
                else -> Unit
            }
        }
    }
    if (onBack == null) {
        TabBar(title = stringResource(R.string.tab_discover), bottomBar = bottomBar) { Box(Modifier.fillMaxSize()) { content() } }
    } else {
        Page(title = stringResource(R.string.tab_discover), onBack = onBack, scrolls = false, bottomBar = bottomBar) { Box(Modifier.fillMaxSize()) { content() } }
    }
}

private fun LazyListScope.discoverHome(
    state: PodcastState,
    discover: DiscoverViewModel.State,
    catalogueName: String,
    onAddByAddress: () -> Unit,
    onOpenResult: (ShowSearchResult) -> Unit,
    onOpenCategory: (CatalogueCategory) -> Unit,
) {
    item("add-by-address") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = onAddByAddress).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AddLink, contentDescription = null, tint = LocalPalette.current.accent)
            Column {
                Text(stringResource(R.string.add_by_address))
                Text(stringResource(R.string.a_shows_rss_feed), style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted)
            }
        }
    }
    if (state.isOffline) {
        item("offline") {
            Row(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiOff, contentDescription = null, tint = LocalPalette.current.muted, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.discover_offline), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        }
        return
    }
    if (state.catalogueSuggests) {
        item("suggestions") {
            Column(Modifier.padding(vertical = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                Header(stringResource(R.string.more_like_your_shows), stringResource(R.string.more_like_footer))
                val suggestions = state.suggestions
                when {
                    suggestions == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    suggestions.isEmpty() -> Text(stringResource(R.string.follow_a_few), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = Tokens.Spacing.lg))
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                        items(suggestions, key = { it.show.id }) { suggestion ->
                            Column(Modifier.width(140.dp).clickable { onOpenResult(suggestion.show) }, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                                Artwork(suggestion.show.imageUrl, 140.dp)
                                Text(suggestion.show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.because_you_follow, suggestion.because), style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    item("browse-header") {
        Header(
            stringResource(R.string.browse),
            if (state.catalogueSuggests) stringResource(R.string.browse_from_server, catalogueName) else stringResource(R.string.browse_apple),
        )
    }
    val categories = discover.categories
    when {
        categories != null -> items(categories.chunked(2), key = { "cat-" + it.first().key }) { pair ->
            Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                for (category in pair) CategoryTile(category, Modifier.weight(1f)) { onOpenCategory(category) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        discover.categoriesFailed -> item("cat-failed") {
            Text(stringResource(R.string.categories_cant_load), color = LocalPalette.current.muted, modifier = Modifier.padding(Tokens.Spacing.lg))
        }
        else -> item("cat-loading") { Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
    }
}

@Composable
private fun Header(title: String, footnote: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg, end = Tokens.Spacing.lg, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
        footnote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted) }
    }
}

/** A category: its symbol, its name and, where the catalogue counts them, how many shows still publish in it. */
@Composable
fun CategoryTile(category: CatalogueCategory, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colour = CategoryStyle.colour(category.key)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .background(colour.copy(alpha = 0.12f), RoundedCornerShape(audio.own.core.designsystem.theme.AppBrand.radii.cover))
            .clickable(onClick = onClick)
            .padding(Tokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        Icon(CategoryStyle.icon(category.key), contentDescription = null, tint = colour)
        Text(CategoryStyle.label(category.key), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        category.showCount?.let { count ->
            Text(stringResource(R.string.shows_count, NumberFormat.getIntegerInstance().format(count)), style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted)
        }
    }
}

/** A show from a catalogue or a search, opening the page that follows it. */
@Composable
fun ResultRow(result: ShowSearchResult, onOpen: (ShowSearchResult) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(result) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(result.imageUrl, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            result.author?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// region A category

/** One category's shows, a page at a time: own.audio's cursor pages, or Apple's one chart page. */
class CategoryViewModel(private val catalogue: ShowCatalogue, private val category: CatalogueCategory) : ViewModel() {
    data class State(val shows: List<ShowSearchResult> = emptyList(), val loading: Boolean = false, val reachedEnd: Boolean = false, val failed: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var cursor: String? = null

    init {
        loadMore()
    }

    fun loadMore() {
        val held = _state.value
        if (held.loading || held.reachedEnd) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val page = catalogue.browse(category, cursor)
                cursor = page.next
                val known = _state.value.shows.map { it.id }.toSet()
                val fresh = page.shows.filter { it.id !in known }
                _state.update { it.copy(shows = it.shows + fresh, loading = false, reachedEnd = page.next == null || fresh.isEmpty()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}

@Composable
fun CategoryContent(category: CatalogueCategory, state: CategoryViewModel.State, onOpen: (ShowSearchResult) -> Unit, onMore: () -> Unit, onBack: () -> Unit, bottomBar: @Composable () -> Unit = {}) {
    val list = rememberLazyListState()
    Page(title = CategoryStyle.label(category.key), onBack = onBack, scrolls = false, bottomBar = bottomBar) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(state.shows, key = { it.id }) { show -> ResultRow(show, onOpen) }
            if (state.loading) item("loading") { Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            if (state.failed) item("failed") { Text(stringResource(R.string.shows_cant_load), color = LocalPalette.current.muted, modifier = Modifier.padding(Tokens.Spacing.lg)) }
        }
        if (state.shows.isEmpty() && state.reachedEnd && !state.failed) {
            EmptyState(CategoryStyle.icon(category.key), stringResource(R.string.nothing_here_yet), null, Modifier.fillMaxSize())
        }
        val nearEnd = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.shows.size - 3 } == true
        LaunchedEffect(nearEnd, state.shows.size) { if (nearEnd) onMore() }
    }
}

// endregion

// region A search result

/** A show found by searching: about it, Follow On, Follow, and shows like it. */
@Composable
fun SearchResultContent(
    result: ShowSearchResult,
    follow: AddShowViewModel.State,
    targets: List<SourceInfo>,
    existing: SourcedShow?,
    similar: List<ShowSearchResult>?,
    onTarget: (SourceRef) -> Unit,
    onFollow: () -> Unit,
    onOpenShow: (SourcedShow) -> Unit,
    onOpenResult: (ShowSearchResult) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    Page(title = result.title, onBack = onBack, bottomBar = bottomBar) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
            Artwork(result.imageUrl, 112.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.asHeading())
                result.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                result.genre?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = palette.muted) }
                result.episodeCount?.let { Text(pluralStringResource(R.plurals.episodes_count, it, it), style = MaterialTheme.typography.labelMedium, color = palette.muted) }
            }
        }
        if (targets.size > 1 && existing == null && follow.followed == null) {
            audio.own.podcastplayer.ui.Section(header = stringResource(R.string.follow_on)) { TargetPicker(targets, follow.target, onTarget) }
        }
        val open = follow.followed ?: existing
        if (open != null) {
            TextButton(onClick = { onOpenShow(open) }, modifier = Modifier.padding(horizontal = Tokens.Spacing.sm)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.following_open), modifier = Modifier.padding(start = Tokens.Spacing.sm))
            }
        } else {
            TextButton(onClick = onFollow, enabled = !follow.working, modifier = Modifier.padding(horizontal = Tokens.Spacing.sm)) {
                if (follow.working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.follow), modifier = Modifier.padding(start = Tokens.Spacing.sm))
            }
        }
        follow.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.lg)) }
        if (!similar.isNullOrEmpty()) SimilarShows(similar, onOpenResult)
    }
}

// endregion
