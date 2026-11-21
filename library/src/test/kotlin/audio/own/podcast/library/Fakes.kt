package audio.own.podcast.library

import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedEnclosure
import audio.own.kit.feed.FeedEpisode
import audio.own.kit.feed.FeedException
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedShow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Kept in memory; a second store object over the same [box] is "the same file after a restart". */
class InMemoryValueStore<T : Any>(private val box: Box<T> = Box()) : ValueStore<T> {
    class Box<T : Any> {
        var value: T? = null
    }

    override suspend fun load(): T? = box.value

    override suspend fun save(value: T?) {
        box.value = value
    }
}

class InMemoryFeedLibraryStore : FeedLibraryStore {
    private val records = linkedMapOf<String, ShowRecord>()
    private var state = FeedLibraryState()

    override suspend fun records() = synchronized(this) { records.values.toList() }

    override suspend fun record(showId: String) = synchronized(this) { records[showId] }

    override suspend fun put(record: ShowRecord) {
        synchronized(this) { records[record.show.id] = record }
    }

    override suspend fun delete(showId: String) {
        synchronized(this) { records.remove(showId) }
    }

    override suspend fun state() = synchronized(this) { state }

    override suspend fun putState(state: FeedLibraryState) {
        synchronized(this) { this.state = state }
    }
}

/** Feeds the test changes between fetches, and a clock it moves. */
class World {
    private class Page(val document: FeedDocument?, val etag: String?)

    private val pages = mutableMapOf<String, Page>()
    var time: Instant = Instant.ofEpochSecond(1_800_000_000)
    val fetched = mutableListOf<String>()

    fun set(url: String, document: FeedDocument, etag: String? = null) {
        synchronized(this) { pages[url] = Page(document, etag) }
    }

    /** An HTML page where a feed was expected. */
    fun setPage(url: String) {
        synchronized(this) { pages[url] = Page(null, null) }
    }

    fun advance(seconds: Long) {
        time = time.plusSeconds(seconds)
    }

    val loader = FeedLoader { url, validators ->
        val page = synchronized(this) {
            fetched += url
            pages[url]
        } ?: throw FeedException.Http(404)
        val document = page.document ?: throw FeedException.NotAFeed()
        if (page.etag != null && validators?.etag == page.etag) {
            FeedFetcher.Result.NotModified
        } else {
            FeedFetcher.Result.Fetched(document, FeedFetcher.Validators(etag = page.etag), url)
        }
    }
}

data class Item(val id: String, val day: Int, val durationMs: Long? = null)

/** A show whose episodes are published on October [Item.day] 2026, plus a post without audio. */
fun feed(
    title: String = "Show",
    serial: Boolean = false,
    moved: String? = null,
    items: List<Item>,
) = FeedDocument(
    show = FeedShow(title = title, author = "Host", isSerial = serial, newFeedUrl = moved),
    episodes = listOf(FeedEpisode(id = "post", title = "A post")) + items.map {
        FeedEpisode(
            id = it.id,
            title = "Episode ${it.id}",
            publishedAt = LocalDate.of(2026, 10, it.day).atTime(8, 0).toInstant(ZoneOffset.UTC),
            durationMs = it.durationMs,
            enclosure = FeedEnclosure("https://cdn.example.com/${it.id}.mp3", "audio/mpeg", 100),
        )
    },
)
