package audio.own.podcast.library.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PodcastDao {
    // region The device's feeds

    @Query("SELECT * FROM show")
    suspend fun shows(): List<ShowRow>

    @Query("SELECT * FROM show WHERE id = :id")
    suspend fun show(id: String): ShowRow?

    @Upsert
    suspend fun upsertShow(row: ShowRow)

    @Query("DELETE FROM show WHERE id = :id")
    suspend fun deleteShow(id: String)

    @Query("SELECT * FROM episode ORDER BY show_id, position")
    suspend fun allEpisodes(): List<EpisodeRow>

    @Query("SELECT * FROM episode WHERE show_id = :showId ORDER BY position")
    suspend fun episodes(showId: String): List<EpisodeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(rows: List<EpisodeRow>)

    @Query("DELETE FROM episode WHERE show_id = :showId")
    suspend fun deleteEpisodesOf(showId: String)

    @Query("SELECT * FROM progress")
    suspend fun progress(): List<ProgressRow>

    @Upsert
    suspend fun upsertProgress(rows: List<ProgressRow>)

    @Query("DELETE FROM progress WHERE `key` IN (:keys)")
    suspend fun deleteProgress(keys: List<String>)

    @Query("SELECT * FROM dismissed")
    suspend fun dismissed(): List<DismissedRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDismissed(rows: List<DismissedRow>)

    @Query("DELETE FROM dismissed WHERE `key` IN (:keys)")
    suspend fun deleteDismissed(keys: List<String>)

    // endregion

    // region Small values

    @Query("SELECT value FROM value WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun setValue(row: ValueRow)

    @Query("DELETE FROM value WHERE `key` = :key")
    suspend fun deleteValue(key: String)

    @Query("DELETE FROM value WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteValuesStartingWith(prefix: String)

    // endregion

    // region own.audio catalogues

    @Query("SELECT * FROM oa_podcast WHERE server_id = :serverId")
    suspend fun ownAudioPodcasts(serverId: String): List<OwnAudioPodcastRow>

    @Query("SELECT * FROM oa_episode WHERE server_id = :serverId")
    suspend fun ownAudioEpisodes(serverId: String): List<OwnAudioEpisodeRow>

    @Query("SELECT * FROM oa_progress WHERE server_id = :serverId")
    suspend fun ownAudioProgress(serverId: String): List<OwnAudioProgressRow>

    @Query("SELECT * FROM oa_feed WHERE server_id = :serverId")
    suspend fun ownAudioFeed(serverId: String): OwnAudioFeedRow?

    @Upsert suspend fun upsertOwnAudioPodcasts(rows: List<OwnAudioPodcastRow>)
    @Upsert suspend fun upsertOwnAudioEpisodes(rows: List<OwnAudioEpisodeRow>)
    @Upsert suspend fun upsertOwnAudioProgress(rows: List<OwnAudioProgressRow>)
    @Upsert suspend fun upsertOwnAudioFeed(row: OwnAudioFeedRow)

    @Query("DELETE FROM oa_podcast WHERE server_id = :serverId AND id IN (:ids)")
    suspend fun deleteOwnAudioPodcasts(serverId: String, ids: List<String>)

    @Query("DELETE FROM oa_episode WHERE server_id = :serverId AND id IN (:ids)")
    suspend fun deleteOwnAudioEpisodes(serverId: String, ids: List<String>)

    @Query("DELETE FROM oa_progress WHERE server_id = :serverId AND episode_id IN (:ids)")
    suspend fun deleteOwnAudioProgress(serverId: String, ids: List<String>)

    @Query("DELETE FROM oa_podcast WHERE server_id = :serverId") suspend fun clearOwnAudioPodcasts(serverId: String)
    @Query("DELETE FROM oa_episode WHERE server_id = :serverId") suspend fun clearOwnAudioEpisodes(serverId: String)
    @Query("DELETE FROM oa_progress WHERE server_id = :serverId") suspend fun clearOwnAudioProgress(serverId: String)
    @Query("DELETE FROM oa_feed WHERE server_id = :serverId") suspend fun clearOwnAudioFeed(serverId: String)

    // endregion
}
