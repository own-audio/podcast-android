package audio.own.podcastplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import audio.own.player.core.SleepTimer
import audio.own.podcast.library.CatalogueCategory
import audio.own.podcast.library.Capability
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcast.library.UpNext
import audio.own.podcastplayer.AppContainer
import audio.own.podcastplayer.R
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.playback.OutputSwitcher
import audio.own.podcastplayer.podcasts.AddShowContent
import audio.own.podcastplayer.podcasts.AddShowViewModel
import audio.own.podcastplayer.podcasts.CategoryContent
import audio.own.podcastplayer.podcasts.CategoryViewModel
import audio.own.podcastplayer.podcasts.DiscoverContent
import audio.own.podcastplayer.podcasts.DiscoverViewModel
import audio.own.podcastplayer.podcasts.DownloadsPlaceholder
import audio.own.podcastplayer.podcasts.EpisodeActions
import audio.own.podcastplayer.podcasts.EpisodeContent
import audio.own.podcastplayer.podcasts.FamilySharing
import audio.own.podcastplayer.podcasts.MiniPlayer
import audio.own.podcastplayer.podcasts.NewEpisodesContent
import audio.own.podcastplayer.podcasts.PlayerActions
import audio.own.podcastplayer.podcasts.PlayerContent
import audio.own.podcastplayer.podcasts.PodcastModel
import audio.own.podcastplayer.podcasts.ProgressText
import audio.own.podcastplayer.podcasts.SearchResultContent
import audio.own.podcastplayer.podcasts.ShowContent
import audio.own.podcastplayer.podcasts.ShowMenu
import audio.own.podcastplayer.podcasts.ShowSettingsContent
import audio.own.podcastplayer.podcasts.ShowSort
import audio.own.podcastplayer.podcasts.ShowViewModel
import audio.own.podcastplayer.podcasts.ShowsContent
import audio.own.podcastplayer.podcasts.rowUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

object PodcastRoutes {
    const val SHOW = "show/{id}"
    const val SHOW_SETTINGS = "show-settings/{id}"
    const val EPISODE = "episode/{id}"
    const val ADD_SHOW = "add-show"
    const val DISCOVER = "discover"
    const val CATEGORY = "category/{key}"
    const val RESULT = "result/{id}"
    const val PLAYER = "player"

    fun show(show: SourcedShow) = "show/${Uri.encode(show.id)}"
    fun showSettings(show: SourcedShow) = "show-settings/${Uri.encode(show.id)}"
    fun episode(id: String) = "episode/${Uri.encode(id)}"
    fun category(key: String) = "category/${Uri.encode(key)}"
    fun result(id: String) = "result/${Uri.encode(id)}"
}

/**
 * What a page needs to open something it was handed rather than one it can look up: an episode
 * from a show's list, a search result. Kept for the process; after a restart such a page closes.
 */
class NavStash {
    val episodes = HashMap<String, SourcedEpisode>()
    val results = HashMap<String, ShowSearchResult>()
    val shows = HashMap<String, SourcedShow>()
}

/** The model's actions for rows, pages and the player; each opens what it names through [nav]. */
@androidx.annotation.OptIn(UnstableApi::class)
class ModelActions(
    private val model: PodcastModel,
    private val scope: CoroutineScope,
    private val nav: NavHostController,
    private val stash: NavStash,
    private val context: Context,
) : EpisodeActions, PlayerActions {
    override fun open(item: SourcedEpisode) {
        stash.episodes[item.id] = item
        nav.navigate(PodcastRoutes.episode(item.id))
    }

    override fun play(item: SourcedEpisode) {
        scope.launch { model.play(item) }
    }

    override fun togglePlayback() = toggle()

    override fun markPlayed(item: SourcedEpisode, played: Boolean) = model.markPlayed(item, played)

    override fun playNext(item: SourcedEpisode) = model.playNext(item)

    override fun playLater(item: SourcedEpisode) = model.playLater(item)

    override fun removeFromUpNext(item: SourcedEpisode) = model.removeFromUpNext(item.id)

    override fun dismiss(item: SourcedEpisode) = model.dismiss(item)

    override fun toggle() = model.player.toggle()

    override fun skipBack() = model.player.skipBack()

    override fun skipForward() = model.player.skipForward()

    override fun seekTo(positionMs: Long) = model.player.seekTo(positionMs)

    override fun setRate(rate: Float) = model.player.setRate(rate)

    override fun sleepAfter(preset: SleepTimer.Preset) = model.player.sleepAfter(preset)

    override fun sleepAtEndOfChapter() = model.player.stopAtEndOfChapter(true)

    override fun sleepAtEndOfEpisode() = model.player.sleepAtEndOfEpisode()

    override fun extendSleep() = model.player.extendSleep()

    override fun cancelSleep() = model.player.cancelSleep()

    override fun stop() = model.stopPlayback()

    override fun playQueued(entry: UpNext.Entry) {
        scope.launch { model.play(entry) }
    }

    override fun removeQueued(entry: UpNext.Entry) = model.removeFromUpNext(entry.id)

    override fun moveQueued(from: Int, to: Int) = model.moveUpNext(from, to)

    override fun clearQueue() = model.clearUpNext()

    override fun output() {
        OutputSwitcher.show(context)
    }

    fun openShow(show: SourcedShow) {
        stash.shows[show.id] = show
        nav.navigate(PodcastRoutes.show(show))
    }

    fun openResult(result: ShowSearchResult) {
        stash.results[result.id] = result
        nav.navigate(PodcastRoutes.result(result.id))
    }
}

/** The share sheet with a show's feed address. */
fun shareFeed(context: Context, feed: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, feed)
    context.startActivity(Intent.createChooser(send, null))
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberModelActions(container: AppContainer, nav: NavHostController): ModelActions {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(nav) { ModelActions(container.podcasts, scope, nav, container.navStash, context) }
}

/** The mini player above every podcast page's bottom edge, while an episode is loaded. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberMiniPlayer(container: AppContainer, nav: NavHostController): @Composable () -> Unit = remember(nav) {
    {
        val player by container.player.state.collectAsStateWithLifecycle()
        MiniPlayer(
            player,
            onOpen = { nav.navigate(PodcastRoutes.PLAYER) { launchSingleTop = true } },
            onToggle = { container.player.toggle() },
            onSkipForward = { container.player.skipForward() },
        )
    }
}

/** The four tabs with the library's state. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PodcastTabs(container: AppContainer, nav: NavHostController, onSettings: () -> Unit) {
    val model = container.podcasts
    val state by model.state.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val offlineMode by container.preferences.offlineMode.collectAsState(initial = false)
    val sortKey by container.preferences.showsSort.collectAsState(initial = ShowSort.TITLE.key)
    val actions = rememberModelActions(container, nav)
    val mini = rememberMiniPlayer(container, nav)
    val scope = rememberCoroutineScope()
    TabsScreen { tab ->
        when (tab) {
            Tab.NEW_EPISODES -> NewEpisodesContent(
                state = state,
                player = player,
                actions = actions,
                canPlay = model::canPlay,
                offlineMode = offlineMode,
                onOfflineMode = { on -> scope.launch { container.preferences.setOfflineMode(on) } },
                onSettings = onSettings,
                onSource = model::setActiveSource,
                onRefresh = { scope.launch { model.refresh(asked = true) } },
                bottomBar = mini,
            )
            Tab.SHOWS -> ShowsContent(
                state = state,
                sort = ShowSort.of(sortKey),
                onSort = { scope.launch { container.preferences.setShowsSort(it.key) } },
                onOpen = actions::openShow,
                onAdd = { nav.navigate(PodcastRoutes.ADD_SHOW) },
                onRefresh = { scope.launch { model.refresh(asked = true) } },
                bottomBar = mini,
            )
            Tab.DOWNLOADS -> DownloadsPlaceholder(bottomBar = mini)
            Tab.DISCOVER -> DiscoverRoute(container, nav, actions, onBack = null, bottomBar = mini)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DiscoverRoute(container: AppContainer, nav: NavHostController, actions: ModelActions, onBack: (() -> Unit)?, bottomBar: @Composable () -> Unit) {
    val model = container.podcasts
    val state by model.state.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val vm = viewModel(key = "discover") { DiscoverViewModel({ model.catalogue() }, { model.state.value.isOffline }) }
    val discover by vm.state.collectAsStateWithLifecycle()
    val catalogueKey = "${state.catalogueServer}|${state.followTargets.size}|${state.isOffline}"
    LaunchedEffect(catalogueKey) {
        if (!state.isOffline) vm.loadCategories(catalogueKey)
        model.loadSuggestions()
    }
    DiscoverContent(
        state = state,
        discover = discover,
        row = { state.rowUi(it, player, model.canPlay(it)) },
        actions = actions,
        onQuery = vm::setQuery,
        onSubmit = vm::searchNow,
        onAddByAddress = { nav.navigate(PodcastRoutes.ADD_SHOW) },
        onOpenShow = actions::openShow,
        onOpenResult = actions::openResult,
        onOpenCategory = { nav.navigate(PodcastRoutes.category(it.key)) },
        onBack = onBack,
        bottomBar = bottomBar,
    )
}

/** The podcast pages over the tabs: a show, its settings, an episode, Add a Show, Discover's pages, the player. */
@androidx.annotation.OptIn(UnstableApi::class)
fun NavGraphBuilder.podcastPages(container: AppContainer, nav: NavHostController) {
    val model = container.podcasts
    val back: () -> Unit = { nav.popBackStack() }
    fun id(entry: androidx.navigation.NavBackStackEntry, name: String = "id") = entry.arguments?.getString(name)?.let(Uri::decode).orEmpty()

    composable(PodcastRoutes.SHOW) { entry ->
        val showId = id(entry)
        val state by model.state.collectAsStateWithLifecycle()
        val player by container.player.state.collectAsStateWithLifecycle()
        val show = state.shows.firstOrNull { it.id == showId } ?: container.navStash.shows[showId]
        if (show == null) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        val actions = rememberModelActions(container, nav)
        val context = LocalContext.current
        val vm = viewModel(key = "show-$showId") { ShowViewModel({ model.episodes(show) }, { model.refresh(show) }) }
        val page by vm.state.collectAsStateWithLifecycle()
        val similar by produceState<List<ShowSearchResult>?>(null, show.show.feedUrl, state.catalogueServer) {
            value = show.show.feedUrl?.let { model.similarShows(it) }
        }
        var sharing by remember(show.id) { mutableStateOf<FamilySharing?>(null) }
        LaunchedEffect(show.id) { sharing = model.familySharing(show) }
        ShowContent(
            show = show,
            sourceName = state.sourceName(show.source),
            page = page,
            row = { state.rowUi(it, player, model.canPlay(it)) },
            actions = actions,
            menu = ShowMenu(show.show.link, show.show.feedUrl, Capability.UNFOLLOW in model.capabilities(show.source)),
            similar = similar,
            sharedWithFamily = sharing?.shared == true,
            canShareWithFamily = sharing?.canChange == true,
            onShareWithFamily = { on -> model.setSharedWithFamily(show, on) { held -> sharing = sharing?.copy(shared = held) } },
            onSettings = { nav.navigate(PodcastRoutes.showSettings(show)) },
            onShareFeed = { shareFeed(context, it) },
            onUnfollow = { model.unfollow(show) { nav.popBackStack() } },
            onOpenResult = actions::openResult,
            onRefresh = vm::pullToRefresh,
            onBack = back,
            bottomBar = rememberMiniPlayer(container, nav),
        )
    }
    composable(PodcastRoutes.SHOW_SETTINGS) { entry ->
        val showId = id(entry)
        val state by model.state.collectAsStateWithLifecycle()
        val show = state.shows.firstOrNull { it.id == showId }
        if (show == null) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        ShowSettingsContent(show.show.settings, onCancel = back, onDone = { settings ->
            model.updateSettings(settings, show)
            nav.popBackStack()
        })
    }
    composable(PodcastRoutes.EPISODE) { entry ->
        val episodeId = id(entry)
        val state by model.state.collectAsStateWithLifecycle()
        val player by container.player.state.collectAsStateWithLifecycle()
        val item = container.navStash.episodes[episodeId] ?: state.inbox.firstOrNull { it.id == episodeId }
        if (item == null) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        val actions = rememberModelActions(container, nav)
        val scope = rememberCoroutineScope()
        val ui = state.rowUi(item, player, model.canPlay(item))
        EpisodeContent(
            ui = ui,
            sourceName = state.sourceName(item.source),
            chapters = item.episode.chapters,
            actions = actions,
            onPlayFrom = { start ->
                if (player.item?.id == item.id) {
                    container.player.seekTo(start)
                    if (!player.isPlaying) container.player.play()
                } else {
                    scope.launch { model.play(item, startAt = start) }
                }
            },
            onBack = back,
            bottomBar = rememberMiniPlayer(container, nav),
        )
    }
    composable(PodcastRoutes.ADD_SHOW) {
        val state by model.state.collectAsStateWithLifecycle()
        val address = remember { state.pendingFeedAddress.orEmpty() }
        LaunchedEffect(Unit) { model.feedLinkHandled() }
        val vm = viewModel { AddShowViewModel(address, state.followTargets, state.activeSource) { url, target -> model.follow(url, target) } }
        val form by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(form.followed) { if (form.followed != null) nav.popBackStack() }
        AddShowContent(form, state.followTargets, onAddress = vm::setAddress, onTarget = vm::setTarget, onFollow = vm::submit, onCancel = back)
    }
    composable(PodcastRoutes.DISCOVER) {
        val actions = rememberModelActions(container, nav)
        DiscoverRoute(container, nav, actions, onBack = back, bottomBar = rememberMiniPlayer(container, nav))
    }
    composable(PodcastRoutes.CATEGORY) { entry ->
        val key = id(entry, "key")
        val category = CatalogueCategory(key)
        val actions = rememberModelActions(container, nav)
        val vm = viewModel(key = "category-$key") { CategoryViewModel(model.catalogue(), category) }
        val page by vm.state.collectAsStateWithLifecycle()
        CategoryContent(category, page, onOpen = actions::openResult, onMore = vm::loadMore, onBack = back, bottomBar = rememberMiniPlayer(container, nav))
    }
    composable(PodcastRoutes.RESULT) { entry ->
        val resultId = id(entry)
        val result = container.navStash.results[resultId]
        if (result == null) {
            LaunchedEffect(Unit) { nav.popBackStack() }
            return@composable
        }
        val state by model.state.collectAsStateWithLifecycle()
        val actions = rememberModelActions(container, nav)
        val vm = viewModel(key = "result-$resultId") { AddShowViewModel(result.feedUrl, state.followTargets, state.activeSource) { url, target -> model.follow(url, target) } }
        val follow by vm.state.collectAsStateWithLifecycle()
        val existing = state.shows.firstOrNull { it.show.feedUrl == result.feedUrl }
            ?: result.podcastId?.let { podcast -> state.shows.firstOrNull { it.show.id == podcast } }
        val similar by produceState<List<ShowSearchResult>?>(null, result.feedUrl) { value = model.similarShows(result.feedUrl) }
        SearchResultContent(
            result = result,
            follow = follow,
            targets = state.followTargets,
            existing = existing,
            similar = similar,
            onTarget = vm::setTarget,
            onFollow = vm::submit,
            onOpenShow = actions::openShow,
            onOpenResult = actions::openResult,
            onBack = back,
            bottomBar = rememberMiniPlayer(container, nav),
        )
    }
    composable(
        PodcastRoutes.PLAYER,
        enterTransition = { slideInVertically(tween(300)) { it } },
        exitTransition = { slideOutVertically(tween(300)) { it } },
        popEnterTransition = { slideInVertically(tween(300)) { it } },
        popExitTransition = { slideOutVertically(tween(300)) { it } },
    ) {
        val state by model.state.collectAsStateWithLifecycle()
        val player by container.player.state.collectAsStateWithLifecycle()
        val actions = rememberModelActions(container, nav)
        PlayerContent(player, state.upNext, actions, onClose = back, showOutput = OutputSwitcher.isAvailable, playsOn = state.settings.continueWithNewEpisodes)
    }
}

/** The dialogs over every page: a failed action, and "Continue from another device?". */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PodcastDialogs(container: AppContainer) {
    val model = container.podcasts
    val state by model.state.collectAsStateWithLifecycle()
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(SR.string.error_title)) },
            text = { Text(error.text()) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(SR.string.ok)) } },
        )
    }
    state.pendingResume?.let { pending ->
        AlertDialog(
            onDismissRequest = model::dismissResume,
            title = { Text(stringResource(R.string.continue_title)) },
            text = { Text(stringResource(R.string.another_device_message, ProgressText.clock(pending.otherMs), ProgressText.clock(pending.localMs))) },
            confirmButton = { TextButton(onClick = { model.resolveResume(useOther = true) }) { Text(stringResource(R.string.continue_from, ProgressText.clock(pending.otherMs))) } },
            dismissButton = { TextButton(onClick = { model.resolveResume(useOther = false) }) { Text(stringResource(R.string.keep_this_device, ProgressText.clock(pending.localMs))) } },
        )
    }
}
