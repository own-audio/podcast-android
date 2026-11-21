package audio.own.core.sync

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Where a listening position is (or was), not yet sent: one row per (server, item, session).
 * A newer position replaces an older one; listened time adds up.
 */
@Entity(tableName = "pending_progress", primaryKeys = ["serverId", "itemId", "sessionId"])
data class PendingProgress(
    val serverId: String,
    /** The item's id as its server knows it. */
    val itemId: String,
    val sessionId: String,
    /** The app's word for the kind (`track`, `book`, `episode`). */
    val kind: String,
    val positionMs: Long,
    val finished: Boolean = false,
    val fileId: String? = null,
    val filePositionMs: Long? = null,
    val listenedMs: Long = 0,
    /** When the listener was there (ms). */
    val at: Long,
    /** First recorded: the order they are sent in. */
    val createdAt: Long = at,
)

/**
 * A change made on the device (a favourite, a rating, a playlist edit), kept until a server
 * takes it. [mergeKey] names what it changes: a later change with the same key replaces it
 * (only the last favourite of a song matters); null keeps every one (songs added to a playlist).
 */
@Entity(tableName = "pending_changes")
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val itemId: String,
    /** The app's name for the change: `favourite`, `rating`, `playlist_add`… */
    val op: String,
    /** The item's kind, as the app names it. */
    val kind: String? = null,
    /** The change's value, in the app's own format (often JSON). */
    val value: String? = null,
    val mergeKey: String? = null,
    /** Stable, so a change sent twice counts once. */
    val clientId: String,
    val madeAt: Long,
)

/** A counted play, waiting to be reported. [clientId] (`<itemId>@<startedAt ms>`) dedupes. */
@Entity(tableName = "pending_plays")
data class PendingPlay(
    @PrimaryKey val clientId: String,
    val serverId: String,
    val itemId: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val fileId: String? = null,
    val createdAt: Long = endedAt,
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM pending_progress WHERE serverId = :serverId AND itemId = :itemId AND sessionId = :sessionId")
    suspend fun progress(serverId: String, itemId: String, sessionId: String): PendingProgress?

    @Upsert
    suspend fun upsert(progress: PendingProgress)

    @Query("SELECT * FROM pending_progress ORDER BY createdAt, serverId, itemId")
    suspend fun allProgress(): List<PendingProgress>

    @Query("DELETE FROM pending_progress WHERE serverId = :serverId AND itemId = :itemId AND sessionId = :sessionId AND at = :at")
    suspend fun deleteProgress(serverId: String, itemId: String, sessionId: String, at: Long)

    @Insert
    suspend fun insert(change: PendingChange): Long

    @Query("DELETE FROM pending_changes WHERE serverId = :serverId AND mergeKey = :mergeKey")
    suspend fun deleteMerged(serverId: String, mergeKey: String)

    @Query("SELECT * FROM pending_changes ORDER BY id")
    suspend fun allChanges(): List<PendingChange>

    @Query("DELETE FROM pending_changes WHERE id IN (:ids)")
    suspend fun deleteChanges(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_changes")
    suspend fun changeCount(): Int

    @Query("DELETE FROM pending_changes WHERE id IN (SELECT id FROM pending_changes ORDER BY id LIMIT :count)")
    suspend fun dropOldestChanges(count: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(play: PendingPlay): Long

    @Query("SELECT * FROM pending_plays ORDER BY createdAt, clientId")
    suspend fun allPlays(): List<PendingPlay>

    @Query("DELETE FROM pending_plays WHERE clientId IN (:ids)")
    suspend fun deletePlays(ids: List<String>)

    @Query("SELECT COUNT(*) FROM pending_plays")
    suspend fun playCount(): Int

    @Query("DELETE FROM pending_plays WHERE clientId IN (SELECT clientId FROM pending_plays ORDER BY createdAt LIMIT :count)")
    suspend fun dropOldestPlays(count: Int)

    @Query("DELETE FROM pending_changes WHERE serverId = :serverId")
    suspend fun forgetChanges(serverId: String)

    @Query("DELETE FROM pending_plays WHERE serverId = :serverId")
    suspend fun forgetPlays(serverId: String)

    @Query("DELETE FROM pending_progress WHERE serverId = :serverId")
    suspend fun forgetProgress(serverId: String)

    @Transaction
    suspend fun replaceChange(change: PendingChange, limit: Int): Long {
        change.mergeKey?.let { deleteMerged(change.serverId, it) }
        val id = insert(change)
        val over = changeCount() - limit
        if (over > 0) dropOldestChanges(over)
        return id
    }

    @Transaction
    suspend fun addPlay(play: PendingPlay, limit: Int): Boolean {
        val added = insert(play) != -1L
        val over = playCount() - limit
        if (over > 0) dropOldestPlays(over)
        return added
    }

    @Transaction
    suspend fun mergeProgress(next: PendingProgress) {
        val old = progress(next.serverId, next.itemId, next.sessionId)
        upsert(
            if (old == null) {
                next
            } else if (next.at >= old.at) {
                next.copy(listenedMs = old.listenedMs + next.listenedMs, createdAt = old.createdAt)
            } else {
                old.copy(listenedMs = old.listenedMs + next.listenedMs)
            },
        )
    }
}

@Database(entities = [PendingProgress::class, PendingChange::class, PendingPlay::class], version = 1)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        fun open(context: Context, name: String = "outbox.db"): OutboxDatabase =
            Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, name).build()

        fun inMemory(context: Context): OutboxDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, OutboxDatabase::class.java).allowMainThreadQueries().build()
    }
}
