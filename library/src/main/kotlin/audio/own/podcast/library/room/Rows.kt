package audio.own.podcast.library.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A show the device follows by its feed. The show itself is kept as JSON: its fields grow with
 * the feed parser, and nothing queries them. The columns are what the store looks things up by.
 */
@Entity(tableName = "show", indices = [Index("feed_url")])
data class ShowRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "feed_url") val feedUrl: String?,
    val show: String,
    val etag: String?,
    @ColumnInfo(name = "last_modified") val lastModified: String?,
    @ColumnInfo(name = "latest_at_follow") val latestAtFollow: String?,
)

/** One episode of a followed feed, in the feed's order ([position]). */
@Entity(
    tableName = "episode",
    primaryKeys = ["show_id", "id"],
    foreignKeys = [ForeignKey(entity = ShowRow::class, parentColumns = ["id"], childColumns = ["show_id"], onDelete = ForeignKey.CASCADE)],
)
data class EpisodeRow(
    @ColumnInfo(name = "show_id") val showId: String,
    val id: String,
    val position: Int,
    val episode: String,
)

/** Where the listener is in a device episode; [key] is the episode key's text. */
@Entity(tableName = "progress")
data class ProgressRow(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val played: Boolean,
    /** ISO-8601, every digit an Instant has: "newer wins" compares them. */
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/** A device episode taken out of New Episodes unplayed. */
@Entity(tableName = "dismissed")
data class DismissedRow(@PrimaryKey val key: String)

/** One small value kept whole (Up Next, pending positions, a server's listening state). */
@Entity(tableName = "value")
data class ValueRow(@PrimaryKey val key: String, val value: String)

/** An own.audio server's show, as its change feed last sent it. */
@Entity(tableName = "oa_podcast", primaryKeys = ["server_id", "id"])
data class OwnAudioPodcastRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    val id: String,
    val record: String,
)

@Entity(tableName = "oa_episode", primaryKeys = ["server_id", "id"], indices = [Index("server_id", "podcast_id")])
data class OwnAudioEpisodeRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    val id: String,
    @ColumnInfo(name = "podcast_id") val podcastId: String,
    val record: String,
)

/** A position the server holds, by episode id. */
@Entity(tableName = "oa_progress", primaryKeys = ["server_id", "episode_id"])
data class OwnAudioProgressRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    val record: String,
)

/** Where the change feed was left, and when. */
@Entity(tableName = "oa_feed")
data class OwnAudioFeedRow(
    @PrimaryKey @ColumnInfo(name = "server_id") val serverId: String,
    val cursor: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: String?,
)
