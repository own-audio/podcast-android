package audio.own.podcast.library

import audio.own.kit.feed.FeedShow
import java.net.URI

/** Whether a fetched feed is a show the device already follows. Pure, so the rules are tested alone. */
object ShowIdentity {
    /**
     * The followed show [feed] is, if any: first by `podcast:guid`, then by any of [addresses]
     * (the one asked for, the one after redirects, the one the feed says it moved to), then by
     * title and website together.
     */
    fun of(shows: List<Show>, feed: FeedShow, addresses: List<String>): Show? {
        feed.podcastGuid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { guid ->
            shows.firstOrNull { it.podcastGuid?.trim()?.lowercase() == guid }?.let { return it }
        }
        byAddress(shows, addresses)?.let { return it }
        val title = feed.title.trim().lowercase()
        val link = feed.link?.let(::normalized)
        if (title.isEmpty() || link == null) return null
        return shows.firstOrNull { it.title.trim().lowercase() == title && it.link?.let(::normalized) == link }
    }

    /** The followed show at any of [addresses], compared as [normalized]. */
    fun byAddress(shows: List<Show>, addresses: List<String>): Show? {
        val wanted = addresses.mapNotNull(::normalized).toSet()
        if (wanted.isEmpty()) return null
        return shows.firstOrNull { show -> show.feedUrl?.let(::normalized) in wanted }
    }

    /**
     * One spelling for one address: no scheme (a feed is the same over http and https), the host
     * in lower case without `www.`, no default port, no trailing slash, no fragment. The path and
     * query stay as they are: they are what tell two feeds on one host apart.
     */
    fun normalized(address: String): String? {
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val port = uri.port.takeIf { it != -1 && it != 80 && it != 443 }?.let { ":$it" }.orEmpty()
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return host + port + path + query
    }
}
