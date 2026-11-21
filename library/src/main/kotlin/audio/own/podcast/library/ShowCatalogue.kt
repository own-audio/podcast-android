package audio.own.podcast.library

import audio.own.kit.feed.FeedValues
import audio.own.kit.feed.HtmlText
import audio.own.kit.ownaudio.DirectoryShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A show found by searching or browsing, before anyone follows it. Its id is the feed address. */
data class ShowSearchResult(
    val title: String,
    val feedUrl: String,
    val author: String? = null,
    val imageUrl: String? = null,
    val episodeCount: Int? = null,
    val genre: String? = null,
    /** On own.audio: the show's id when the household already follows it. */
    val podcastId: String? = null,
) {
    val id: String get() = feedUrl
}

/**
 * A category to browse: its key (`news`, `society`…, a Podcast Index category on own.audio, or
 * Apple's genre under the same name) and how many shows still publish in it, where the catalogue
 * counts them.
 */
data class CatalogueCategory(val key: String, val showCount: Long? = null)

/** One page of a category; [next] goes on, null is the end. */
data class CataloguePage(val shows: List<ShowSearchResult>, val next: String?)

/**
 * Where Discover finds new shows: an own.audio server's directory where it has one, else
 * Apple's public charts and search. Only own.audio knows which shows are alike.
 */
interface ShowCatalogue {
    /** Whose catalogue it is, for the header: a server's name, or null for Apple's. */
    val serverName: String?

    suspend fun categories(): List<CatalogueCategory>

    suspend fun browse(category: CatalogueCategory, cursor: String? = null): CataloguePage

    suspend fun search(term: String): List<ShowSearchResult>

    /** Shows like the one at [feedUrl]; null when this catalogue can't tell. */
    suspend fun similar(feedUrl: String): List<ShowSearchResult>?
}

/**
 * The directory of an own.audio server (`/podcasts/directory`): nothing typed or followed
 * leaves the server. Browsing and similar shows need `podcast_discovery`, search
 * `podcast_search`; without one, that part comes from [fallback] (Apple's).
 */
class OwnAudioShowCatalogue(
    private val directory: OwnAudioPodcastSource.Directory,
    override val serverName: String,
    private val fallback: ShowCatalogue,
) : ShowCatalogue {
    /** Whether this server's catalogue is the one Discover browses (it can say which shows are alike). */
    val browses: Boolean get() = directory.canBrowse

    override suspend fun categories(): List<CatalogueCategory> {
        if (!directory.canBrowse) return fallback.categories()
        return directory.categories()
            .filter { maxOf(it.activeCount, it.feedCount) > 0 }
            .map { CatalogueCategory(it.category, if (it.activeCount > 0) it.activeCount else it.feedCount) }
    }

    override suspend fun browse(category: CatalogueCategory, cursor: String?): CataloguePage {
        if (!directory.canBrowse) return fallback.browse(category, cursor)
        val page = directory.browse(category.key, cursor = cursor)
        return CataloguePage(page.items.mapNotNull(::result), page.next)
    }

    override suspend fun search(term: String): List<ShowSearchResult> {
        if (!directory.canSearch) return fallback.search(term)
        val trimmed = term.trim()
        if (trimmed.length < 2) return emptyList()
        return directory.search(trimmed).mapNotNull(::result)
    }

    override suspend fun similar(feedUrl: String): List<ShowSearchResult>? {
        if (!directory.canBrowse) return null
        return directory.similar(feedUrl = feedUrl).mapNotNull(::result)
    }

    companion object {
        fun result(show: DirectoryShow): ShowSearchResult? {
            val feed = FeedValues.url(show.feedUrl) ?: return null
            return ShowSearchResult(
                title = HtmlText.decodeEntities(show.title),
                feedUrl = feed,
                author = show.author?.let(HtmlText::decodeEntities),
                imageUrl = FeedValues.url(show.image),
                episodeCount = show.episodeCount,
                genre = show.categories.firstOrNull(),
                podcastId = show.podcastId,
            )
        }
    }
}

/**
 * Apple's public podcast directory: the top shows per genre in the listener's country, and its
 * search. No key, no account. A chart names shows by Apple id, so their feed addresses come from
 * one lookup per page. Fetched with the plain client: no server's token goes to Apple.
 */
class AppleShowCatalogue(
    client: OkHttpClient,
    country: String?,
    private val base: HttpUrl = "https://itunes.apple.com".toHttpUrl(),
) : ShowCatalogue {
    private val country = (country?.takeIf { it.length == 2 } ?: "us").lowercase()
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override val serverName: String? = null

    override suspend fun categories(): List<CatalogueCategory> = GENRES.map { CatalogueCategory(it.first) }

    /** Apple's chart is one page of 50. */
    override suspend fun browse(category: CatalogueCategory, cursor: String?): CataloguePage {
        val genre = GENRES.firstOrNull { it.first == category.key }?.second
        if (cursor != null || genre == null) return CataloguePage(emptyList(), null)
        val chart = base.newBuilder().addPathSegment(country).addPathSegments("rss/toppodcasts/limit=50/genre=$genre/json").build()
        val ids = (fetch(chart)["feed"] as? JsonObject)?.get("entry")?.let { entries ->
            // One entry comes as an object, more as an array.
            val list = (entries as? JsonArray)?.toList() ?: listOf(entries)
            list.mapNotNull { entry ->
                (((entry as? JsonObject)?.get("id") as? JsonObject)?.get("attributes") as? JsonObject)?.get("im:id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }.orEmpty()
        if (ids.isEmpty()) return CataloguePage(emptyList(), null)
        val lookup = base.newBuilder().addPathSegment("lookup")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("country", country)
            .addQueryParameter("id", ids.joinToString(","))
            .build()
        val found = results(fetch(lookup)).associateBy { it.first }
        // In the chart's order; a show without a feed address can't be followed and is left out.
        return CataloguePage(ids.mapNotNull { found[it]?.second }, null)
    }

    override suspend fun search(term: String): List<ShowSearchResult> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        val url = base.newBuilder().addPathSegment("search")
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("term", trimmed)
            .addQueryParameter("limit", "30")
            .addQueryParameter("country", country)
            .build()
        val seen = HashSet<String>()
        return results(fetch(url)).map { it.second }.filter { seen.add(it.feedUrl) }
    }

    override suspend fun similar(feedUrl: String): List<ShowSearchResult>? = null

    /** `results` of a lookup or a search, with the Apple id of each. */
    private fun results(answer: JsonObject): List<Pair<String, ShowSearchResult>> =
        (answer["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull
            val title = text("collectionName") ?: return@mapNotNull null
            val feed = FeedValues.url(text("feedUrl")) ?: return@mapNotNull null
            val id = (item["collectionId"] as? JsonPrimitive)?.longOrNull?.toString() ?: ""
            id to ShowSearchResult(
                title = title,
                feedUrl = feed,
                author = text("artistName"),
                imageUrl = FeedValues.url(text("artworkUrl600") ?: text("artworkUrl100")),
                episodeCount = (item["trackCount"] as? JsonPrimitive)?.intOrNull,
                genre = text("primaryGenreName"),
            )
        }

    private suspend fun fetch(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw SourceException.Network(error)
        }
        response.use {
            if (it.code != 200) throw SourceException.Network()
            try {
                json.parseToJsonElement(it.body.string()).jsonObject
            } catch (_: Exception) {
                throw SourceException.Server("unreadable directory answer")
            }
        }
    }

    companion object {
        /** Apple's podcast genres, under the catalogue's category names where they match. */
        val GENRES: List<Pair<String, Int>> = listOf(
            "news" to 1489, "society" to 1324, "comedy" to 1303, "education" to 1304, "business" to 1321,
            "history" to 1487, "science" to 1533, "technology" to 1318, "health" to 1512, "truecrime" to 1488,
            "culture" to 1301, "sports" to 1545, "kids" to 1305, "fiction" to 1483, "religion" to 1314,
            "leisure" to 1502, "music" to 1310, "tv" to 1309, "government" to 1511,
        )
    }
}

/**
 * Normalises what someone typed or a link handed over into a feed address: `feed://`,
 * `podcast://` and `pcast://` become `https://`, and an address without a scheme gets one.
 * Null when it can't be one.
 */
object FeedAddress {
    fun normalized(typed: String): String? {
        var text = typed.trim()
        if (text.isEmpty()) return null
        for (scheme in listOf("feed://", "podcast://", "pcast://")) {
            if (text.lowercase().startsWith(scheme)) {
                text = "https://" + text.substring(scheme.length)
                break
            }
        }
        // `feed:https://…`, as some sites write it.
        if (text.lowercase().startsWith("feed:")) text = text.substring(5)
        if (!text.contains("://")) text = "https://$text"
        return FeedValues.url(text)?.takeIf { url ->
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            !host.isNullOrEmpty() && host.contains('.') || host == "localhost"
        }
    }
}
