package audio.own.kit.feed

import java.time.Instant

/**
 * A podcast feed as the app needs it: the show and its episodes, every field optional except
 * what a feed cannot be without. Times are milliseconds. URLs are kept as the feed wrote them
 * (trimmed, spaces escaped), because gPodder servers identify episodes by the exact enclosure URL.
 */
data class FeedDocument(
    val show: FeedShow,
    val episodes: List<FeedEpisode>,
)

data class FeedShow(
    val title: String,
    val author: String? = null,
    /** Plain text with paragraphs kept; [descriptionHtml] holds what the feed sent. */
    val description: String? = null,
    val descriptionHtml: String? = null,
    val link: String? = null,
    val imageUrl: String? = null,
    val language: String? = null,
    val categories: List<String> = emptyList(),
    val isExplicit: Boolean = false,
    /** `serial` shows are heard oldest first; `episodic` (the default) newest first. */
    val isSerial: Boolean = false,
    /** `itunes:new-feed-url`: the publisher moved the feed; follow it. */
    val newFeedUrl: String? = null,
    /** `itunes:complete`: no more episodes will come. */
    val isComplete: Boolean = false,
    /** `podcast:guid`, the show's identity across feed moves. */
    val podcastGuid: String? = null,
    val funding: List<FeedLink> = emptyList(),
    val lastBuildDate: Instant? = null,
)

data class FeedEpisode(
    /**
     * The `guid`, or the enclosure URL where the feed gives none — what identifies the episode
     * from one fetch to the next.
     */
    val id: String,
    val title: String,
    val description: String? = null,
    val descriptionHtml: String? = null,
    val publishedAt: Instant? = null,
    val durationMs: Long? = null,
    val enclosure: FeedEnclosure? = null,
    val link: String? = null,
    val imageUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** `full`, `trailer` or `bonus`. */
    val episodeType: String? = null,
    val isExplicit: Boolean = false,
    /** A Podcasting 2.0 chapters document to fetch (`podcast:chapters`). */
    val chaptersUrl: String? = null,
    /** Podlove Simple Chapters, given inline in the feed. */
    val inlineChapters: List<FeedChapter> = emptyList(),
    val transcripts: List<FeedTranscript> = emptyList(),
    val persons: List<FeedPerson> = emptyList(),
)

data class FeedEnclosure(
    val url: String,
    val type: String? = null,
    /** Bytes, where the feed says (often 0 or wrong); 0 is read as unknown. */
    val length: Long? = null,
)

data class FeedChapter(
    val startMs: Long,
    val endMs: Long? = null,
    val title: String,
    val url: String? = null,
    val imageUrl: String? = null,
    /** Podcasting 2.0's `toc: false` marks a chapter that is only an image or link change. */
    val isInTableOfContents: Boolean = true,
)

data class FeedTranscript(
    val url: String,
    /** `text/vtt`, `application/x-subrip`, `application/json`, `text/html`, `text/plain`. */
    val type: String,
    val language: String? = null,
)

data class FeedPerson(
    val name: String,
    /** `host`, `guest`, … (Podcasting 2.0's taxonomy); `host` when the feed says nothing. */
    val role: String,
    val imageUrl: String? = null,
    val link: String? = null,
)

data class FeedLink(
    val url: String,
    val title: String? = null,
)

sealed class FeedException(message: String) : Exception(message) {
    /** The document is XML but neither RSS nor Atom: an HTML page, an OPML file, an error. */
    class NotAFeed : FeedException("not a feed")

    /** The XML could not be read, even after the usual repair. */
    class Malformed(val reason: String) : FeedException("malformed feed: $reason")

    class TooLarge : FeedException("feed too large")

    class Http(val status: Int) : FeedException("HTTP $status")

    class Network(cause: Throwable? = null) : FeedException("network error") {
        init {
            cause?.let(::initCause)
        }
    }
}
