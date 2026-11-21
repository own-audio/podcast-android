package audio.own.podcastplayer.podcasts

import audio.own.podcast.library.CatalogueCategory
import audio.own.podcast.library.CataloguePage
import audio.own.podcast.library.Episode
import audio.own.podcast.library.Show
import audio.own.podcast.library.ShowCatalogue
import audio.own.podcast.library.ShowSearchResult
import audio.own.podcast.library.SourceException
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.R
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelsTest {
    private val main = mainThread()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    private val server = SourceRef.Server(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val targets = listOf(SourceInfo(SourceRef.Phone, "This Device", null), SourceInfo(server, "own.audio", "own_audio"))

    private fun followed(url: String, ref: SourceRef) = SourcedShow(ref, Show(id = "s1", title = "Show", feedUrl = url, followedAt = Instant.EPOCH))

    @Test
    fun `Add a Show normalises the address, follows on the chosen source and says what failed`() = runBlocking {
        val asked = mutableListOf<Pair<String, SourceRef>>()
        val vm = AddShowViewModel("feed://example.com/rss", targets, active = server.id) { url, ref ->
            asked += url to ref
            if (url.contains("broken")) throw SourceException.NotAFeed()
            followed(url, ref)
        }
        assertEquals("the source on the switch is the target", server, vm.state.value.target)
        vm.setTarget(SourceRef.Phone)
        vm.submit()
        eventually { vm.state.value.followed != null }
        assertEquals("https://example.com/rss" to SourceRef.Phone, asked.single())

        vm.setAddress("example.com/broken")
        vm.submit()
        eventually { vm.state.value.error != null && !vm.state.value.working }
        assertEquals(R.string.error_not_a_feed, vm.state.value.error?.id)

        vm.setAddress("not an address")
        assertNull("typing clears the message", vm.state.value.error)
        vm.submit()
        assertEquals(R.string.error_not_a_feed, vm.state.value.error?.id)
        assertEquals("nothing was asked for a bad address", 2, asked.size)
    }

    private class Catalogue(private val results: List<ShowSearchResult>, var fail: Boolean = false) : ShowCatalogue {
        val searches = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override val serverName: String? = null
        override suspend fun categories() = if (fail) throw SourceException.Network() else listOf(CatalogueCategory("news", 10), CatalogueCategory("comedy"))
        override suspend fun browse(category: CatalogueCategory, cursor: String?): CataloguePage {
            pages += cursor
            return when (cursor) {
                null -> CataloguePage(results.take(1), "p2")
                "p2" -> CataloguePage(results.drop(1), null)
                else -> CataloguePage(emptyList(), null)
            }
        }
        override suspend fun search(term: String): List<ShowSearchResult> {
            synchronized(searches) { searches += term }
            if (fail) throw SourceException.Network()
            return results.filter { it.title.contains(term, ignoreCase = true) }
        }
        override suspend fun similar(feedUrl: String): List<ShowSearchResult>? = null
    }

    private val results = listOf(ShowSearchResult("Radio Wave", "https://a.example.com/f"), ShowSearchResult("Wave Two", "https://b.example.com/f"))

    @Test
    fun `Discover searches once the typing pauses, and only for the last text`() = runBlocking {
        val catalogue = Catalogue(results)
        val vm = DiscoverViewModel({ catalogue }, { false }, debounceMs = 100)
        vm.setQuery("r")
        vm.setQuery("ra")
        vm.setQuery("radio")
        eventually { vm.state.value.search == DiscoverViewModel.Search.DONE }
        assertEquals(listOf("radio"), synchronized(catalogue.searches) { catalogue.searches.toList() })
        assertEquals(listOf("Radio Wave"), vm.state.value.results.map { it.title })
        vm.setQuery("  ")
        eventually { vm.state.value.search == DiscoverViewModel.Search.IDLE }
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun `Discover offline or failing says search isn't available`() = runBlocking {
        var offline = true
        val catalogue = Catalogue(results)
        val vm = DiscoverViewModel({ catalogue }, { offline }, debounceMs = 10)
        vm.setQuery("wave")
        eventually { vm.state.value.search == DiscoverViewModel.Search.FAILED }
        assertTrue("offline asks nobody", catalogue.searches.isEmpty())
        offline = false
        catalogue.fail = true
        vm.searchNow()
        eventually { catalogue.searches.isNotEmpty() && vm.state.value.search == DiscoverViewModel.Search.FAILED }
        vm.loadCategories("apple")
        eventually { vm.state.value.categoriesFailed }
        catalogue.fail = false
        vm.loadCategories("server")
        eventually { vm.state.value.categories != null }
        assertEquals(listOf("news", "comedy"), vm.state.value.categories?.map { it.key })
    }

    @Test
    fun `a category reads its pages until the cursor ends`() = runBlocking {
        val catalogue = Catalogue(results)
        val vm = CategoryViewModel(catalogue, CatalogueCategory("news"))
        eventually { vm.state.value.shows.size == 1 && !vm.state.value.loading }
        vm.loadMore()
        eventually { vm.state.value.reachedEnd }
        assertEquals(listOf("Radio Wave", "Wave Two"), vm.state.value.shows.map { it.title })
        vm.loadMore()
        assertEquals(listOf(null, "p2"), catalogue.pages)
    }

    @Test
    fun `a show page keeps what it showed when a reload fails`() = runBlocking {
        val calls = AtomicInteger()
        val episode = SourcedEpisode(SourceRef.Phone, Episode(id = "e1", showId = "s1", title = "One", firstSeenAt = Instant.EPOCH))
        var refreshed = 0
        val vm = ShowViewModel(
            load = { if (calls.incrementAndGet() == 1) listOf(episode) else throw SourceException.Network() },
            refresh = { refreshed++ },
        )
        eventually { vm.state.value.episodes != null }
        vm.pullToRefresh()
        eventually { calls.get() == 2 && !vm.state.value.refreshing }
        assertEquals(1, refreshed)
        assertEquals(listOf(episode), vm.state.value.episodes)
        assertFalse(vm.state.value.failed)

        val failing = ShowViewModel(load = { throw SourceException.Network() }, refresh = {})
        eventually { failing.state.value.failed }
    }
}
