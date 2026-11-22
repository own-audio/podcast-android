package audio.own.podcastplayer.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.AddressScope
import audio.own.core.servers.ServerAddress
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerLibraryRef
import audio.own.core.servers.ServerRecord
import audio.own.podcastplayer.app.PlaybackSettings
import audio.own.podcastplayer.app.ServerExtras
import audio.own.core.servers.StoredTokens
import audio.own.podcastplayer.onboarding.AccountMode
import audio.own.podcastplayer.onboarding.AccountState
import audio.own.podcastplayer.onboarding.AddServerContent
import audio.own.podcastplayer.onboarding.OnboardingContent
import audio.own.podcastplayer.onboarding.OwnAudioAccountContent
import audio.own.podcastplayer.onboarding.ServerFormState
import audio.own.podcastplayer.servers.ConnectionCheck
import audio.own.podcastplayer.servers.ConnectionState
import audio.own.podcastplayer.servers.ServerDetailContent
import audio.own.podcastplayer.servers.ServerDetailState
import audio.own.podcastplayer.servers.ServerKinds
import audio.own.podcastplayer.servers.ServerPageUi
import audio.own.podcastplayer.settings.AppLanguage
import audio.own.podcastplayer.settings.SettingsContent
import audio.own.podcastplayer.settings.SettingsUi
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome screen, the account sheet, Add a Server, Settings and a server page in light, dark, Czech and at
 * twice the font size. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val home = ServerRecord(
        id = "s1",
        kind = ServerKinds.OWN_AUDIO,
        displayName = "own.audio",
        host = "https://podcasts.example.com",
        auth = ServerAuth.OwnAudio("alex@example.com", StoredTokens("a", "r", 0)),
        serverIdentity = "srv-1",
        addresses = listOf(
            ServerAddress("http://192.168.1.10:8080", AddressScope.LAN, AddressOrigin.SERVER),
            ServerAddress("http://100.64.0.7:8080", AddressScope.VPN, AddressOrigin.USER),
        ),
    )
    private val shelf = ServerRecord(
        id = "s3",
        kind = ServerKinds.AUDIOBOOKSHELF,
        displayName = "Shelf",
        host = "https://shelf.example.com",
        auth = ServerAuth.Audiobookshelf("alex", StoredTokens("a", "r", 0)),
        libraries = listOf(ServerLibraryRef("lib1", "Podcasts"), ServerLibraryRef("lib2", "YouTube")),
    )
    private val sync = ServerRecord(
        id = "s4",
        kind = ServerKinds.GPODDER,
        displayName = "podfetch",
        host = "https://sync.example.com",
        auth = ServerAuth.GPodder("alex", "secret", "own-audio-podcasts-0123abcd"),
    )
    private val demo = home.copy(id = "s2", displayName = "own.audio demo", host = "https://demo.example.com", auth = ServerAuth.OwnAudio("demo@example.com", StoredTokens("a", "r", 0)), addresses = emptyList())

    @Composable
    private fun Welcome() = OnboardingContent(
        tryingDemo = false,
        error = null,
        onCreateAccount = {}, onSignIn = {}, onTryDemo = {}, onFindShow = {}, onAddFeed = {}, onAddServer = {},
    )

    @Composable
    private fun AddServer() = AddServerContent(
        state = ServerFormState(address = "podcasts.example.com", username = "alex"),
        showGoogle = true,
        onCancel = {}, onKind = {}, onAddress = {}, onUsername = {}, onPassword = {}, onConnect = {}, onGoogle = {},
    )

    @Composable
    private fun Account() = OwnAudioAccountContent(
        state = AccountState(AccountMode.SIGN_UP, name = "Alex", email = "alex@example.com", googleClientId = "web"),
        showGoogle = true,
        onCancel = {}, onName = {}, onEmail = {}, onPassword = {}, onSubmit = {}, onGoogle = {},
    )

    @Composable
    private fun Settings() = SettingsContent(
        ui = SettingsUi(
            listOf(home, demo, shelf, sync),
            connections = mapOf("s1" to ConnectionState.Connected("http://192.168.1.10:8080"), "s3" to ConnectionState.Unreachable),
            playback = PlaybackSettings(skipBackSecs = 15, skipForwardSecs = 30, defaultRate = 1.25f),
            accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1.0.0 (123)", downloadsBytes = 734_000_000),
        onBack = {}, onOpenServer = {}, onRemoveServer = {}, onAddServer = {}, onOfflineMode = {}, onPlayback = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
    )

    private val shotAt = Instant.parse("2026-11-21T14:05:00Z")

    @Composable
    private fun ShelfServer() = ServerDetailContent(
        ui = ServerPageUi(
            server = shelf,
            connection = ConnectionState.Connected("https://shelf.example.com"),
            check = null,
            checking = false,
            refused = false,
            detail = ServerDetailState(version = "2.37.1"),
            extras = ServerExtras(canAddShows = true, hiddenLibraries = setOf("lib2")),
        ),
        onBack = {}, onCheckNow = {}, onSignInAgain = {}, onDeleteAccount = {}, onFamily = {}, onDevices = {}, onListening = {},
        onRename = {}, onConnection = {}, onAddAddress = {}, onRemoveAddress = {}, onRemove = {}, onDismissError = {},
    )

    @Composable
    private fun SyncServer() = ServerDetailContent(
        ui = ServerPageUi(
            server = sync,
            connection = ConnectionState.Connected("https://sync.example.com"),
            check = ConnectionCheck(ConnectionCheck.Status.OK, shotAt),
            checking = false,
            refused = false,
            detail = ServerDetailState(),
            extras = ServerExtras(software = "podfetch", lastSyncedAtMs = shotAt.minusSeconds(300).toEpochMilli()),
            now = shotAt.toEpochMilli(),
        ),
        onBack = {}, onCheckNow = {}, onSignInAgain = {}, onDeleteAccount = {}, onFamily = {}, onDevices = {}, onListening = {},
        onRename = {}, onConnection = {}, onAddAddress = {}, onRemoveAddress = {}, onRemove = {}, onDismissError = {},
    )

    @Composable
    private fun Server() = ServerDetailContent(
        ui = ServerPageUi(
            server = home,
            connection = ConnectionState.Connected("http://192.168.1.10:8080"),
            check = ConnectionCheck(ConnectionCheck.Status.OK, Instant.parse("2026-11-21T14:05:00Z")),
            checking = false,
            refused = false,
            detail = ServerDetailState(version = "1.0.5"),
        ),
        onBack = {}, onCheckNow = {}, onSignInAgain = {}, onDeleteAccount = {}, onFamily = {}, onDevices = {}, onListening = {},
        onRename = {}, onConnection = {}, onAddAddress = {}, onRemoveAddress = {}, onRemove = {}, onDismissError = {},
    )

    @Test fun welcomeLight() = shoot("welcome_light", false) { Welcome() }
    @Test fun welcomeDark() = shoot("welcome_dark", true) { Welcome() }
    @Test @Config(qualifiers = "+cs") fun welcomeCzech() = shoot("welcome_cs", false) { Welcome() }
    @Test @Config(fontScale = 2f) fun welcomeLargestText() = shoot("welcome_font200", false) { Welcome() }

    @Test fun accountLight() = shoot("account_light", false) { Account() }
    @Test fun accountDark() = shoot("account_dark", true) { Account() }
    @Test @Config(qualifiers = "+cs") fun accountCzech() = shoot("account_cs", false) { Account() }
    @Test @Config(fontScale = 2f) fun accountLargestText() = shoot("account_font200", false) { Account() }

    @Test fun addServerLight() = shoot("add_server_light", false) { AddServer() }
    @Test fun addServerDark() = shoot("add_server_dark", true) { AddServer() }
    @Test @Config(qualifiers = "+cs") fun addServerCzech() = shoot("add_server_cs", false) { AddServer() }
    @Test @Config(fontScale = 2f) fun addServerLargestText() = shoot("add_server_font200", false) { AddServer() }

    @Test fun settingsLight() = shoot("settings_light", false) { Settings() }
    @Test fun settingsDark() = shoot("settings_dark", true) { Settings() }
    @Test @Config(qualifiers = "+cs") fun settingsCzech() = shoot("settings_cs", false) { Settings() }
    @Test @Config(fontScale = 2f) fun settingsLargestText() = shoot("settings_font200", false) { Settings() }

    @Test fun shelfServerLight() = shoot("server_audiobookshelf_light", false) { ShelfServer() }
    @Test fun shelfServerDark() = shoot("server_audiobookshelf_dark", true) { ShelfServer() }
    @Test @Config(qualifiers = "+cs") fun shelfServerCzech() = shoot("server_audiobookshelf_cs", false) { ShelfServer() }

    @Test fun syncServerLight() = shoot("server_gpodder_light", false) { SyncServer() }
    @Test fun syncServerDark() = shoot("server_gpodder_dark", true) { SyncServer() }
    @Test @Config(qualifiers = "+cs") fun syncServerCzech() = shoot("server_gpodder_cs", false) { SyncServer() }

    @Test fun serverLight() = shoot("server_light", false) { Server() }
    @Test fun serverDark() = shoot("server_dark", true) { Server() }
    @Test @Config(qualifiers = "+cs") fun serverCzech() = shoot("server_cs", false) { Server() }
    @Test @Config(fontScale = 2f) fun serverLargestText() = shoot("server_font200", false) { Server() }
}
