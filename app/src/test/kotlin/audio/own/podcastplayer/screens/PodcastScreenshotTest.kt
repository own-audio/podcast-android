package audio.own.podcastplayer.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.player.core.SkipIntervals
import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.CatalogueCategory
import audio.own.podcast.library.Chapter
import audio.own.podcast.library.Episode
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcast.library.UpNext
import audio.own.podcastplayer.playback.PodcastPlayer
import audio.own.podcastplayer.podcasts.AddShowContent
import audio.own.podcastplayer.podcasts.AddShowViewModel
import audio.own.podcastplayer.podcasts.ChaptersBody
import audio.own.podcastplayer.podcasts.DiscoverContent
import audio.own.podcastplayer.podcasts.DiscoverViewModel
import audio.own.podcastplayer.podcasts.EpisodeActions
import audio.own.podcastplayer.podcasts.EpisodeContent
import audio.own.podcastplayer.podcasts.EpisodeSummarySection
import audio.own.podcastplayer.podcasts.SummaryUi
import audio.own.kit.ownaudio.EpisodeAnswer
import audio.own.kit.ownaudio.EpisodeSummary
import audio.own.kit.ownaudio.SummaryLength
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import audio.own.podcastplayer.podcasts.MiniPlayer
import audio.own.podcastplayer.podcasts.NewEpisodesContent
import audio.own.podcastplayer.podcasts.PlayerActions
import audio.own.podcastplayer.podcasts.PlayerContent
import audio.own.podcastplayer.podcasts.PodcastState
import audio.own.podcastplayer.podcasts.ShowContent
import audio.own.podcastplayer.podcasts.ShowMenu
import audio.own.podcastplayer.podcasts.ShowSettingsContent
import audio.own.podcastplayer.podcasts.ShowSort
import audio.own.podcastplayer.podcasts.ShowViewModel
import audio.own.podcastplayer.podcasts.ShowsContent
import audio.own.podcastplayer.podcasts.SleepBody
import audio.own.podcastplayer.podcasts.SourceInfo
import audio.own.podcastplayer.podcasts.SpeedBody
import audio.own.podcastplayer.podcasts.Suggestion
import audio.own.podcastplayer.podcasts.UpNextBody
import audio.own.podcastplayer.podcasts.rowUi
import audio.own.podcastplayer.downloads.DownloadedEpisode
import audio.own.podcastplayer.downloads.DownloadsContent
import audio.own.podcastplayer.downloads.DownloadsState
import audio.own.podcastplayer.downloads.EpisodeDownload
import audio.own.podcastplayer.downloads.EpisodeDownloads

import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import audio.own.podcastplayer.ui.TabsScreen
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The P6 screens in light, dark, Czech and at twice the font size: New Episodes, Shows, a show,
 * an episode, Discover, the player and its sheets. Pictures are left to their placeholders.
 * Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PodcastScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // Dates in a past year: rows then always show the year, so the pictures don't change on 1 January.
    private fun day(month: Int, day: Int) = LocalDate.of(2025, month, day).atTime(7, 0).toInstant(ZoneOffset.UTC)

    private val server = SourceRef.Server(UUID.fromString("6f1c0a52-0000-4000-8000-000000000001"))
    private val sources = listOf(SourceInfo(server, "own.audio", "own_audio"), SourceInfo(SourceRef.Phone, "This Device", null))

    private val morning = Show(id = "s1", title = "The Morning Brief", author = "City Radio", feedUrl = "https://feeds.example.com/morning.xml", link = "https://example.com/morning",
        description = "Ten minutes of the day's news every weekday morning. More at https://example.com/morning.", followedAt = day(9, 1))
    private val science = Show(id = "s2", title = "Small Science", author = "Ana Ortiz", feedUrl = "https://feeds.example.com/science.xml", followedAt = day(8, 1))
    private val history = Show(id = "s3", title = "Long Ago, Not Far Away: Stories of the Places We Pass Every Day", author = "Local History Society", followedAt = day(10, 2))

    private fun episode(show: Show, id: String, title: String, published: Instant, minutes: Long, chapters: List<Chapter> = emptyList()) =
        Episode(id = id, showId = show.id, title = title, publishedAt = published, durationMs = minutes * 60_000, firstSeenAt = published, chapters = chapters,
            description = "In this episode: what changed, who it affects and what comes next. Links at https://example.com/notes/$id.")

    private val chapters = listOf(Chapter(0, 60_000, "Headlines"), Chapter(60_000, 420_000, "The council's budget"), Chapter(420_000, 600_000, "Weather"))
    private val e1 = SourcedEpisode(SourceRef.Phone, episode(morning, "m1", "Friday: the council's budget, a new bridge and the weekend's weather", day(10, 17), 10, chapters))
    private val e2 = SourcedEpisode(server, episode(science, "x1", "Why bread rises", day(10, 16), 38))
    private val e3 = SourcedEpisode(SourceRef.Phone, episode(morning, "m0", "Thursday: trams, tickets and a quiet return to school", day(10, 16), 11))
    private val e4 = SourcedEpisode(server, episode(science, "x0", "The surprising life of lichen", day(10, 9), 47))

    private val state = PodcastState(
        loaded = true,
        shows = listOf(SourcedShow(server, science), SourcedShow(SourceRef.Phone, morning), SourcedShow(SourceRef.Phone, history)),
        inbox = listOf(e1, e2, e3, e4),
        progress = mapOf(
            e2.id to ListeningProgress(26 * 60_000, false, day(10, 18)),
            e3.id to ListeningProgress(0, true, day(10, 17)),
        ),
        upNext = listOf(UpNext.Entry(e4.id, server.id, e4.episode, science), UpNext.Entry(e3.id, "phone", e3.episode, morning)),
        sources = sources,
        followTargets = sources.reversed(),
        unreachable = setOf(server.id),
        catalogueServer = "own.audio",
        suggestions = listOf(
            Suggestion(ShowSearchResult("Kitchen Chemistry", "https://feeds.example.com/kitchen.xml", "Lee Park"), "Small Science"),
            Suggestion(ShowSearchResult("Night Sky Notes", "https://feeds.example.com/sky.xml"), "Small Science"),
        ),
    )

    private val player = PodcastPlayer.State(
        item = e1,
        show = morning,
        isPlaying = true,
        positionMs = 95_000,
        durationMs = 600_000,
        rate = 1.25f,
        chapters = chapters.map { SpokenChapter(it.title, it.startMs, it.endMs!!) },
        sleepRemainingSecs = 14 * 60.0 + 32,
        skip = SkipIntervals(15, 30),
        savedAheadMs = 12 * 60_000,
    )

    @Composable
    private fun NewEpisodes() = NewEpisodesContent(
        state = state, player = player, actions = EpisodeActions.None, canPlay = { true }, offlineMode = false,
        onOfflineMode = {}, onSettings = {}, onSource = {}, onRefresh = {},
        bottomBar = { MiniPlayer(player, {}, {}, {}) },
    )

    /** One downloaded on this device, one on its way from the server, one the server only streams. */
    private val withDownloads = state.copy(
        downloads = DownloadsState(
            entries = listOf(
                EpisodeDownload(e1.id, DownloadedEpisode(e1.source.id, e1.episode, morning), EpisodeDownload.Status.DONE, bytes = 9_600_000, queuedAt = 3, finishedAt = 3),
                EpisodeDownload(e2.id, DownloadedEpisode(e2.source.id, e2.episode, science), EpisodeDownload.Status.DOWNLOADING, fraction = 0.4f, queuedAt = 2),
                EpisodeDownload(e4.id, DownloadedEpisode(e4.source.id, e4.episode, science, automatic = true), EpisodeDownload.Status.FAILED, error = EpisodeDownloads.STREAM_ONLY, queuedAt = 1),
            ).associateBy { it.id },
            loaded = true,
        ),
    )

    @Composable
    private fun Downloads() = DownloadsContent(withDownloads, player, EpisodeActions.None, onRetryFailed = {}, onRemoveAll = {}, bottomBar = { MiniPlayer(player, {}, {}, {}) })

    @Composable
    private fun ShowSettingsPage() = ShowSettingsContent(morning.settings.copy(autoDownload = 3, deleteWhenPlayed = true, keepAtMost = 5), {}, {})

    @Composable
    private fun Shows() = ShowsContent(state, ShowSort.TITLE, {}, {}, {}, {})

    @Composable
    private fun ShowPage() = ShowContent(
        show = SourcedShow(SourceRef.Phone, morning),
        sourceName = "This Device",
        page = ShowViewModel.State(episodes = listOf(e1, e3)),
        row = { state.rowUi(it, player) },
        actions = EpisodeActions.None,
        menu = ShowMenu(morning.link, morning.feedUrl, canUnfollow = true),
        similar = null,
        sharedWithFamily = false,
        onSettings = {}, onShareFeed = {}, onUnfollow = {}, onOpenResult = {}, onRefresh = {}, onBack = {},
    )

    @Composable
    private fun EpisodePage() = EpisodeContent(
        ui = withDownloads.rowUi(e1, PodcastPlayer.State()),
        sourceName = "This Device",
        chapters = chapters,
        actions = EpisodeActions.None,
        onPlayFrom = {},
        onBack = {},
    )

    @Composable
    private fun Discover() = DiscoverContent(
        state = state,
        discover = DiscoverViewModel.State(categories = listOf("news", "society", "comedy", "science", "technology", "history").map { CatalogueCategory(it, 1200L) }),
        row = { state.rowUi(it, player) },
        actions = EpisodeActions.None,
        onQuery = {}, onSubmit = {}, onAddByAddress = {}, onOpenShow = {}, onOpenResult = {}, onOpenCategory = {},
    )

    @Composable
    private fun DiscoverSearch() = DiscoverContent(
        state = state,
        discover = DiscoverViewModel.State(
            query = "science",
            results = listOf(ShowSearchResult("Science Weekly", "https://feeds.example.com/sw.xml", "The Paper"), ShowSearchResult("Science Friday", "https://feeds.example.com/sf.xml", "WNYC")),
            search = DiscoverViewModel.Search.DONE,
        ),
        row = { state.rowUi(it, player) },
        actions = EpisodeActions.None,
        onQuery = {}, onSubmit = {}, onAddByAddress = {}, onOpenShow = {}, onOpenResult = {}, onOpenCategory = {},
    )

    @Composable
    private fun Player() = PlayerContent(player, state.upNext, PlayerActions.None, onClose = {})

    @Composable
    private fun Sheets() = androidx.compose.foundation.layout.Column {
        SpeedBody(1.25f) {}
        SleepBody(player, PlayerActions.None) {}
    }

    @Composable
    private fun QueueAndChapters() = androidx.compose.foundation.layout.Column {
        UpNextBody(state.upNext, PlayerActions.None) {}
        ChaptersBody(player, PlayerActions.None) {}
    }

    @Composable
    private fun Forms() = androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
            AddShowContent(AddShowViewModel.State("feeds.example.com/show.xml", SourceRef.Phone), sources.reversed(), {}, {}, {}, {})
        }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
            ShowSettingsContent(morning.settings.copy(skipIntroSecs = 30.0, speed = 1.5f), {}, {})
        }
    }

    @Test fun newEpisodesLight() = shoot("new_episodes_light", false) { NewEpisodes() }
    @Test fun newEpisodesDark() = shoot("new_episodes_dark", true) { NewEpisodes() }
    @Test @Config(qualifiers = "+cs") fun newEpisodesCzech() = shoot("new_episodes_cs", false) { NewEpisodes() }
    @Test @Config(fontScale = 2f) fun newEpisodesLargestText() = shoot("new_episodes_font200", false) { NewEpisodes() }

    @Test fun showsLight() = shoot("shows_light", false) { Shows() }
    @Test fun showsDark() = shoot("shows_dark", true) { Shows() }
    @Test @Config(qualifiers = "+cs") fun showsCzech() = shoot("shows_cs", false) { Shows() }
    @Test @Config(fontScale = 2f) fun showsLargestText() = shoot("shows_font200", false) { Shows() }

    @Test fun showLight() = shoot("show_light", false) { ShowPage() }
    @Test fun showDark() = shoot("show_dark", true) { ShowPage() }
    @Test @Config(qualifiers = "+cs") fun showCzech() = shoot("show_cs", false) { ShowPage() }
    @Test @Config(fontScale = 2f) fun showLargestText() = shoot("show_font200", false) { ShowPage() }

    /** P10: a summary with its cost, a question answered, and the field for the next. */
    @Composable
    private fun Summary() = androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
        EpisodeSummarySection(
            state = SummaryUi(
                summary = EpisodeSummary("en", SummaryLength.MEDIUM, "The hosts walk through the week's news: a new tram line, the river clean-up and the library's late opening on Thursdays.", 12_000),
                checked = true,
                turns = listOf(EpisodeAnswer("a1", "When does the library stay open late?", "On Thursdays, until nine in the evening.", 2_500)),
            ),
            onLength = {}, onSummarize = {}, onQuestion = {}, onAsk = {}, onClearQuestions = {},
        )
    }

    /** No summary in this length yet: what it costs, and the button. */
    @Composable
    private fun NoSummary() = androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
        EpisodeSummarySection(
            state = SummaryUi(length = SummaryLength.SHORT, checked = true, error = audio.own.podcastplayer.app.UiText(audio.own.podcastplayer.R.string.summary_no_credit)),
            onLength = {}, onSummarize = {}, onQuestion = {}, onAsk = {}, onClearQuestions = {},
        )
    }

    @Test fun summaryLight() = shoot("episode_summary_light", false) { Summary() }
    @Test fun summaryDark() = shoot("episode_summary_dark", true) { Summary() }
    @Test @Config(qualifiers = "+cs") fun summaryCzech() = shoot("episode_summary_cs", false) { Summary() }
    @Test fun summaryMissingLight() = shoot("episode_summary_none_light", false) { NoSummary() }

    @Test fun episodeLight() = shoot("episode_light", false) { EpisodePage() }
    @Test fun episodeDark() = shoot("episode_dark", true) { EpisodePage() }
    @Test @Config(qualifiers = "+cs") fun episodeCzech() = shoot("episode_cs", false) { EpisodePage() }
    @Test @Config(fontScale = 2f) fun episodeLargestText() = shoot("episode_font200", false) { EpisodePage() }

    @Test fun discoverLight() = shoot("discover_light", false) { Discover() }
    @Test fun discoverDark() = shoot("discover_dark", true) { Discover() }
    @Test @Config(qualifiers = "+cs") fun discoverCzech() = shoot("discover_cs", false) { Discover() }
    @Test @Config(fontScale = 2f) fun discoverLargestText() = shoot("discover_font200", false) { Discover() }
    @Test fun discoverSearchLight() = shoot("discover_search_light", false) { DiscoverSearch() }

    @Test fun playerLight() = shoot("player_light", false) { Player() }
    @Test fun playerDark() = shoot("player_dark", true) { Player() }
    @Test @Config(qualifiers = "+cs") fun playerCzech() = shoot("player_cs", false) { Player() }
    @Test @Config(fontScale = 2f) fun playerLargestText() = shoot("player_font200", false) { Player() }

    @Test fun speedAndSleepLight() = shoot("sheets_speed_sleep_light", false) { Sheets() }
    @Test fun speedAndSleepDark() = shoot("sheets_speed_sleep_dark", true) { Sheets() }
    @Test @Config(qualifiers = "+cs") fun speedAndSleepCzech() = shoot("sheets_speed_sleep_cs", false) { Sheets() }
    @Test @Config(fontScale = 2f) fun speedAndSleepLargestText() = shoot("sheets_speed_sleep_font200", false) { Sheets() }

    @Test fun upNextAndChaptersLight() = shoot("sheets_up_next_chapters_light", false) { QueueAndChapters() }
    @Test fun upNextAndChaptersDark() = shoot("sheets_up_next_chapters_dark", true) { QueueAndChapters() }
    @Test @Config(qualifiers = "+cs") fun upNextAndChaptersCzech() = shoot("sheets_up_next_chapters_cs", false) { QueueAndChapters() }
    @Test @Config(fontScale = 2f) fun upNextAndChaptersLargestText() = shoot("sheets_up_next_chapters_font200", false) { QueueAndChapters() }

    @Test fun downloadsLight() = shoot("downloads_light", false) { Downloads() }
    @Test fun downloadsDark() = shoot("downloads_dark", true) { Downloads() }
    @Test @Config(qualifiers = "+cs") fun downloadsCzech() = shoot("downloads_cs", false) { Downloads() }
    @Test @Config(fontScale = 2f) fun downloadsLargestText() = shoot("downloads_font200", false) { Downloads() }
    @Test fun downloadsEmptyLight() = shoot("downloads_empty_light", false) { DownloadsContent(state, player, EpisodeActions.None, {}, {}) }

    @Test fun showSettingsLight() = shoot("show_settings_light", false) { ShowSettingsPage() }
    @Test fun showSettingsDark() = shoot("show_settings_dark", true) { ShowSettingsPage() }
    @Test @Config(qualifiers = "+cs") fun showSettingsCzech() = shoot("show_settings_cs", false) { ShowSettingsPage() }

    @Test fun formsLight() = shoot("forms_light", false) { Forms() }
    @Test @Config(qualifiers = "+cs") fun formsCzech() = shoot("forms_cs", false) { Forms() }

    // Every screen at twice the font size, the ones above included.
    @Test @Config(fontScale = 2f) fun showSettingsLargestText() = shoot("show_settings_font200", false) { ShowSettingsPage() }
    @Test @Config(fontScale = 2f) fun formsLargestText() = shoot("forms_font200", false) { Forms() }
    @Test @Config(fontScale = 2f) fun summaryLargestText() = shoot("episode_summary_font200", false) { Summary() }
    @Test @Config(fontScale = 2f) fun discoverSearchLargestText() = shoot("discover_search_font200", false) { DiscoverSearch() }

    // A tablet in landscape: the tabs as a rail, lists a readable width, the player in a column.
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletNewEpisodes() = shoot("tablet_new_episodes", false) { TabsScreen { NewEpisodes() } }
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletShows() = shoot("tablet_shows", true) { TabsScreen { Shows() } }
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tabletPlayer() = shoot("tablet_player", false) { Player() }
    @Test @Config(qualifiers = "w673dp-h841dp-mdpi") fun foldableShow() = shoot("foldable_show", false) { TabsScreen { ShowPage() } }
}
