package audio.own.podcastplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.podcastplayer.AppContainer
import audio.own.podcastplayer.account.DevicesContent
import audio.own.podcastplayer.account.DevicesViewModel
import audio.own.podcastplayer.account.HouseholdContent
import audio.own.podcastplayer.account.HouseholdViewModel
import audio.own.podcastplayer.account.ListeningContent
import audio.own.podcastplayer.account.ListeningViewModel
import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import audio.own.podcastplayer.apple.AppleSignIn
import audio.own.podcastplayer.apple.AppleStatus
import audio.own.podcastplayer.google.GoogleResult
import audio.own.podcastplayer.google.GoogleSignIn
import audio.own.podcastplayer.onboarding.AccountMode
import audio.own.podcastplayer.onboarding.AddServerContent
import audio.own.podcastplayer.onboarding.OnboardingContent
import audio.own.podcastplayer.onboarding.OwnAudioAccountContent
import audio.own.podcastplayer.onboarding.OwnAudioAccountViewModel
import audio.own.podcastplayer.onboarding.ServerFormViewModel
import audio.own.podcastplayer.onboarding.TwoFactorContent
import audio.own.podcastplayer.onboarding.TwoFactorViewModel
import audio.own.podcastplayer.servers.ServerDetailContent
import audio.own.podcastplayer.servers.ServerDetailViewModel
import audio.own.podcastplayer.servers.ServerDetector
import audio.own.podcastplayer.servers.ServerPageUi
import audio.own.podcastplayer.servers.SignInAgainContent
import audio.own.podcastplayer.servers.SignInAgainViewModel
import audio.own.podcastplayer.settings.AppLanguage
import audio.own.podcastplayer.settings.LicencesContent
import audio.own.podcastplayer.settings.LogsContent
import audio.own.podcastplayer.settings.SettingsContent
import audio.own.podcastplayer.settings.SettingsUi
import audio.own.podcastplayer.settings.shareLog
import kotlinx.coroutines.launch

private object Routes {
    const val MAIN = "main"
    const val ACCOUNT = "account/{mode}"
    const val SETTINGS = "settings"
    const val ADD_SERVER = "add-server"
    const val SERVER = "server/{id}"
    const val SIGN_IN_AGAIN = "sign-in-again/{id}"
    const val FAMILY = "family/{id}"
    const val DEVICES = "devices/{id}"
    const val LISTENING = "listening/{id}"
    const val LOGS = "logs"
    const val LICENCES = "licences"
}

/** The whole app: the theme with the chosen accent, the pages, and the two-factor step over them. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AppRoot(container: AppContainer) {
    val accent by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    OwnAudioTheme(accent = accent) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            AppNavHost(container, nav)
            TwoFactorOverlay(container)
            AppleBrowser(container.appleSignIn)
            PodcastDialogs(container)
            // A feed link handed to the app opens Add a Show with it.
            val podcastState by container.podcasts.state.collectAsStateWithLifecycle()
            LaunchedEffect(podcastState.pendingFeedAddress, podcastState.loaded) {
                if (podcastState.pendingFeedAddress != null && podcastState.loaded) nav.navigate(PodcastRoutes.ADD_SHOW) { launchSingleTop = true }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AppNavHost(container: AppContainer, nav: NavHostController) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { nav.popBackStack() }
    fun arg(entry: androidx.navigation.NavBackStackEntry) = entry.arguments?.getString("id").orEmpty()

    OpenPlayerFromWidget(container) { nav.navigate(PodcastRoutes.PLAYER) { launchSingleTop = true } }
    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            val podcasts by container.podcasts.state.collectAsStateWithLifecycle()
            when {
                !servers.loaded || !podcasts.loaded -> LoadingScreen()
                // The welcome screen stays while there is no show and no server.
                servers.servers.isEmpty() && podcasts.shows.isEmpty() -> WelcomeRoute(
                    container,
                    onAccount = { nav.navigate("account/${it.name}") },
                    onFindShow = { nav.navigate(PodcastRoutes.DISCOVER) },
                    onAddFeed = { nav.navigate(PodcastRoutes.ADD_SHOW) },
                    onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                )
                else -> PodcastTabs(container, nav, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
        }
        podcastPages(container, nav)
        composable(Routes.ACCOUNT) { entry ->
            val mode = AccountMode.valueOf(entry.arguments?.getString("mode") ?: AccountMode.SIGN_IN.name)
            val vm = viewModel { OwnAudioAccountViewModel(mode, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack(Routes.MAIN, inclusive = false) }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            OwnAudioAccountContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onName = vm::setName,
                onEmail = vm::setEmail,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val icons by container.serverIcons.icons.collectAsStateWithLifecycle()
            val offlineMode by container.preferences.offlineMode.collectAsState(initial = false)
            val podcasts by container.podcasts.state.collectAsStateWithLifecycle()
            SettingsContent(
                ui = SettingsUi(
                    servers = servers.servers,
                    connections = servers.connections,
                    icons = icons,
                    offlineMode = offlineMode,
                    playback = podcasts.settings,
                    accent = accent(container),
                    language = AppLanguage.current(),
                    version = "${container.versionName} (${container.versionCode})",
                    downloadsBytes = podcasts.downloads.totalBytes,
                ),
                onBack = back,
                onOpenServer = { nav.navigate("server/$it") },
                onRemoveServer = { id -> container.servers.scopeLaunch { container.servers.remove(id) } },
                onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                onOfflineMode = { on -> scope.launch { container.preferences.setOfflineMode(on) } },
                onPlayback = container.podcasts::setPlaybackSettings,
                onAccent = { choice -> scope.launch { container.preferences.setAccent(choice) } },
                onLanguage = { AppLanguage.apply(it); context.findActivity()?.recreate() },
                onLicences = { nav.navigate(Routes.LICENCES) },
                onLogs = { nav.navigate(Routes.LOGS) },
                onDownloads = { nav.navigate(PodcastRoutes.DOWNLOADS) },
            )
        }
        composable(Routes.ADD_SERVER) {
            val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            AddServerContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = { container.servers.cancelTwoFactor(); nav.popBackStack() },
                onKind = vm::setKind,
                onAddress = vm::setAddress,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onConnect = vm::connect,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.SERVER) { entry ->
            val id = arg(entry)
            val vm = viewModel {
                ServerDetailViewModel(
                    id,
                    container.servers,
                    ServerDetector(container.http, container.userAgent, container.log),
                    container.log,
                    isOffline = { container.offline.isOffline },
                    sync = container.podcasts::syncNow,
                )
            }
            val detail by vm.state.collectAsStateWithLifecycle()
            val icons by container.serverIcons.icons.collectAsStateWithLifecycle()
            LaunchedEffect(detail.gone) { if (detail.gone) nav.popBackStack() }
            val record = servers.server(id)
            if (record == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            ServerDetailContent(
                ui = ServerPageUi(
                    server = record,
                    connection = servers.connections[id],
                    check = servers.checks[id],
                    checking = id in servers.checking,
                    refused = servers.isRefused(id),
                    detail = detail,
                    extras = servers.extras(id),
                    icon = icons[id],
                ),
                onBack = back,
                onCheckNow = { vm.checkNow() },
                onSignInAgain = { nav.navigate("sign-in-again/$id") },
                onDeleteAccount = vm::deleteAccount,
                onFamily = { nav.navigate("family/$id") },
                onDevices = { nav.navigate("devices/$id") },
                onListening = { nav.navigate("listening/$id") },
                onRename = { vm.rename(it) },
                onConnection = { vm.setConnection(it) },
                onAddAddress = { vm.addAddress(it) },
                onRemoveAddress = { vm.removeAddress(it) },
                onRemove = vm::remove,
                onDismissError = vm::dismissErrors,
                onLibraryShown = { library, shown -> vm.setLibraryShown(library, shown) },
                onSyncNow = vm::syncNow,
            )
        }
        composable(Routes.SIGN_IN_AGAIN) { entry ->
            val id = arg(entry)
            val vm = viewModel { SignInAgainViewModel(id, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            SignInAgainContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.FAMILY) { entry ->
            val id = arg(entry)
            val vm = viewModel { HouseholdViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            HouseholdContent(
                state = state,
                offline = container.offline.isOffline,
                onBack = back,
                onRetry = vm::load,
                onInvite = vm::createInvite,
                onShowInvite = vm::show,
                onWithdraw = vm::withdraw,
                onJoin = vm::join,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.DEVICES) { entry ->
            val id = arg(entry)
            val vm = viewModel { DevicesViewModel({ container.servers.client(id) }, { container.servers.signOutEverywhere(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.signedOutEverywhere) { if (state.signedOutEverywhere) nav.popBackStack() }
            DevicesContent(
                state = state,
                onBack = back,
                onRetry = vm::load,
                onSignOut = vm::signOut,
                onSignOutEverywhere = vm::signOutEverywhere,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.LISTENING) { entry ->
            val id = arg(entry)
            val vm = viewModel { ListeningViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            ListeningContent(state, onBack = back, onRange = vm::setRange, onRetry = vm::load, onShared = vm::setShared, onDismissError = vm::dismissError)
        }
        composable(Routes.LOGS) {
            val context = LocalContext.current
            val lines by container.log.lines.collectAsStateWithLifecycle()
            LogsContent(lines, onBack = back, onShare = { shareLog(context, container.log, container.logHeader) })
        }
        composable(Routes.LICENCES) { LicencesContent(onBack = back) }
    }
}

@Composable
private fun accent(container: AppContainer): AccentChoice {
    val value by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    return value
}

@Composable
private fun WelcomeRoute(container: AppContainer, onAccount: (AccountMode) -> Unit, onFindShow: () -> Unit, onAddFeed: () -> Unit, onAddServer: () -> Unit) {
    val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
    val state by vm.state.collectAsStateWithLifecycle()
    OnboardingContent(
        tryingDemo = state.tryingDemo,
        error = state.error,
        onCreateAccount = { onAccount(AccountMode.SIGN_UP) },
        onSignIn = { onAccount(AccountMode.SIGN_IN) },
        onTryDemo = vm::tryDemo,
        onFindShow = onFindShow,
        onAddFeed = onAddFeed,
        onAddServer = onAddServer,
    )
}

/** Asks Google for an id token made for [String] (the server's web client id). */
@Composable
private fun rememberGoogle(onToken: (String) -> Unit, onFailed: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { clientId ->
        scope.launch {
            when (val result = GoogleSignIn.idToken(context.findActivity() ?: context, clientId)) {
                is GoogleResult.Token -> onToken(result.idToken)
                is GoogleResult.Failed -> onFailed(result.reason)
                GoogleResult.Cancelled -> Unit
            }
        }
    }
}

/** The second step of a sign-in, over whichever page started it. */
@Composable
private fun TwoFactorOverlay(container: AppContainer) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val pending = servers.pendingTwoFactor ?: return
    Dialog(onDismissRequest = { container.servers.cancelTwoFactor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier, color = MaterialTheme.colorScheme.background) {
            val vm = viewModel(key = pending.mfaToken) { TwoFactorViewModel(container.servers) }
            val state by vm.state.collectAsStateWithLifecycle()
            TwoFactorContent(state, onCode = vm::setCode, onCheck = vm::check, onCancel = vm::cancel)
        }
    }
}

/** Opens Sign in with Apple's page in a Custom Tab once the server named it; the redirect brings the answer back. */
@Composable
private fun AppleBrowser(apple: AppleSignIn) {
    val status by apple.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = status as? AppleStatus.Browser
    LaunchedEffect(open) {
        if (open == null || open.opened || open.url.isEmpty()) return@LaunchedEffect
        // Marked first: the app stops as the tab opens, and coming back unanswered is a cancel.
        apple.opened()
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context.findActivity() ?: context, open.url.toUri())
        } catch (e: ActivityNotFoundException) {
            apple.noBrowser()
        }
    }
}

/** A tap on a widget's words opens the player, when an episode is loaded. */
@Composable
private fun OpenPlayerFromWidget(container: AppContainer, open: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context.findActivity() as? androidx.activity.ComponentActivity
        fun handle(intent: android.content.Intent?) {
            if (intent?.getBooleanExtra(audio.own.podcastplayer.MainActivity.EXTRA_OPEN_PLAYER, false) != true) return
            intent.removeExtra(audio.own.podcastplayer.MainActivity.EXTRA_OPEN_PLAYER)
            if (container.player.state.value.item != null) open()
        }
        handle(activity?.intent)
        val listener = androidx.core.util.Consumer<android.content.Intent> { handle(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
}
