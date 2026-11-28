package audio.own.podcast.library

import audio.own.kit.feed.FeedDocument
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedShow
import kotlinx.serialization.Serializable
import java.time.Instant

/** One followed feed as the device keeps it: the show, its episodes, and what the next fetch needs. */
@Serializable
data class ShowRecord(
    val show: Show,
    val episodes: List<Episode>,
    val validators: FeedFetcher.Validators? = null,
    /**
     * The episode that was newest when the show was followed: the one old episode the inbox shows,
     * so a new follow is not an empty row.
     */
    val latestAtFollow: String? = null,
)

/** How a fetched feed becomes, or updates, a [ShowRecord]. Pure, so the rules are tested alone. */
object FeedMerge {
    data class Result(val record: ShowRecord, val fresh: List<Episode>)

    /** A first follow: only episodes with audio, every one stamped with the follow's time. */
    fun followed(id: String, document: FeedDocument, validators: FeedFetcher.Validators?, finalUrl: String, now: Instant): ShowRecord {
        val show = apply(document.show, Show(id = id, title = document.show.title, followedAt = now)).copy(
            feedUrl = document.show.newFeedUrl ?: finalUrl,
            refreshedAt = now,
        )
        val episodes = document.episodes
            .filter { it.enclosure != null }
            .distinctBy { it.id }
            .map { Episode.from(it, id, now) }
        return ShowRecord(show, episodes, validators, Inbox.latestAtFollow(episodes, show.isSerial))
    }

    /**
     * The feed's episodes over the stored ones: known ones keep their first-seen time, new ones get
     * [now], and ones the feed dropped are dropped unless their ids are in [keep] (the listener is
     * in the middle of them, or has them in Up Next). An unchanged feed (304) only notes the time.
     */
    fun merge(record: ShowRecord, result: FeedFetcher.Result, now: Instant, keep: Set<String>): Result {
        val refreshed = record.copy(show = record.show.copy(refreshedAt = now))
        if (result !is FeedFetcher.Result.Fetched) return Result(refreshed, emptyList())
        val document = result.document
        val moved = document.show.newFeedUrl ?: result.finalUrl
        val show = apply(document.show, refreshed.show).copy(feedUrl = moved)
        val known = record.episodes.associateBy { it.id }
        val fresh = mutableListOf<Episode>()
        val merged = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (feedEpisode in document.episodes) {
            if (feedEpisode.enclosure == null || !seen.add(feedEpisode.id)) continue
            val old = known[feedEpisode.id]
            if (old != null) {
                merged += Episode.from(feedEpisode, show.id, old.firstSeenAt)
            } else {
                val episode = Episode.from(feedEpisode, show.id, now)
                merged += episode
                fresh += episode
            }
        }
        merged += record.episodes.filter { it.id !in seen && it.id in keep }
        return Result(refreshed.copy(show = show, episodes = merged, validators = result.validators), fresh)
    }

    /**
     * The episodes of [showId] a refresh must keep even when the feed no longer lists them: half
     * heard (a position, not played) or queued.
     */
    fun keep(showId: String, queued: Collection<EpisodeKey>, progress: Map<EpisodeKey, ListeningProgress>): Set<String> =
        queued.filter { it.showId == showId }.map { it.episodeId }.toSet() +
            progress.filter { (key, value) -> key.showId == showId && !value.played && value.positionMs > 0 }.keys.map { it.episodeId }

    /** What the feed says about the show, over what was known; an empty value keeps the old one. */
    fun apply(feed: FeedShow, show: Show): Show = show.copy(
        title = feed.title.ifEmpty { show.title },
        author = feed.author ?: show.author,
        description = feed.description ?: show.description,
        imageUrl = feed.imageUrl ?: show.imageUrl,
        link = feed.link ?: show.link,
        categories = feed.categories.ifEmpty { show.categories },
        isSerial = feed.isSerial,
        isExplicit = feed.isExplicit,
        podcastGuid = feed.podcastGuid ?: show.podcastGuid,
    )
}
