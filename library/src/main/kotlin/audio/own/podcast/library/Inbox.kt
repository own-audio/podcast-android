package audio.own.podcast.library

import java.time.Instant

/**
 * The rules of New Episodes, the same for the device's feeds and for every server: an episode is
 * there when it is unplayed, not dismissed, its show wants the inbox, and it is new to the
 * listener — first seen after the show was followed (or first seen, for a server's show), or the
 * one episode that was newest at that moment, so a new follow is not an empty row.
 */
object Inbox {
    const val LIMIT = 200

    /** New to the listener: arrived after the follow, or the newest one at the follow. */
    fun isNew(episode: Episode, followedAt: Instant, latestAtFollow: String?): Boolean =
        episode.firstSeenAt.isAfter(followedAt) || episode.id == latestAtFollow

    fun isInInbox(
        episode: Episode,
        settings: ShowSettings,
        followedAt: Instant?,
        latestAtFollow: String?,
        progress: ListeningProgress?,
        dismissed: Boolean,
    ): Boolean {
        if (!settings.inInbox || progress?.played == true || dismissed || followedAt == null) return false
        return isNew(episode, followedAt, latestAtFollow)
    }

    /**
     * The episode remembered at the follow. None for a serial show: its newest episode is its last
     * chapter, which nobody wants to hear first.
     */
    fun latestAtFollow(episodes: List<Episode>, serial: Boolean): String? =
        if (serial) null else episodes.maxByOrNull { it.publishedAt ?: Instant.MIN }?.id

    /** Newest first, by the publication date or, without one, when it was first seen. */
    fun newestFirst(episodes: List<Episode>, limit: Int = LIMIT): List<Episode> =
        episodes.sortedByDescending { it.publishedAt ?: it.firstSeenAt }.take(limit)

    /** A show's list: newest first, or oldest first for a serial show. */
    fun ordered(episodes: List<Episode>, serial: Boolean): List<Episode> =
        if (serial) episodes.sortedBy { it.publishedAt ?: it.firstSeenAt } else episodes.sortedByDescending { it.publishedAt ?: it.firstSeenAt }
}
