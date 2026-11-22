package audio.own.podcastplayer.app

import kotlinx.serialization.Serializable

/**
 * The listener's playback choices in Settings: the skip intervals, the speed episodes start at
 * (a show can have its own), smart rewind, playing on into New Episodes, and what streams.
 */
@Serializable
data class PlaybackSettings(
    val skipBackSecs: Int = 15,
    val skipForwardSecs: Int = 30,
    val defaultRate: Float = 1.0f,
    val smartRewind: Boolean = true,
    /** When Up Next is empty, the newest new episode plays on. */
    val continueWithNewEpisodes: Boolean = true,
    val mobileData: MobileData = MobileData.STREAM,
    /** While streaming, how many minutes ahead of the playhead are kept on the device. */
    val keepAheadMinutes: Int = 10,
) {
    /** The books app has a third choice, Saver; a feed has one file per episode, nothing smaller to ask for. */
    enum class MobileData { STREAM, DOWNLOADED_ONLY }

    /** The window, and the smallest one with Data Saver on. */
    fun keepAheadMinutes(dataSaver: Boolean): Int = if (dataSaver) LOW_DATA_KEEP_AHEAD else keepAheadMinutes

    companion object {
        val SKIP_CHOICES = listOf(5, 10, 15, 30, 45, 60, 90)
        val SPEED_CHOICES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val KEEP_AHEAD_CHOICES = listOf(5, 10, 15)
        const val LOW_DATA_KEEP_AHEAD = 5
    }
}

/**
 * What the app keeps about a server beside its record, which is shared with the other apps'
 * code and has no place for it.
 */
@Serializable
data class ServerExtras(
    /** "podfetch" for a gPodder server that said it is one. */
    val software: String? = null,
    /** An Audiobookshelf account that may upload can add shows. */
    val canAddShows: Boolean = false,
    /** Audiobookshelf libraries the listener turned off. */
    val hiddenLibraries: Set<String> = emptySet(),
    /** A gPodder account's last sync that finished. */
    val lastSyncedAtMs: Long? = null,
)
