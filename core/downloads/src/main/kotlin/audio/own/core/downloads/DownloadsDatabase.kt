package audio.own.core.downloads

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One file kept on the device, or waiting to be. [key] is the item's composite id, with
 * `#<fileId>` for one file of an item made of several (a book). [record] is the item as the app
 * shows it, in the app's own JSON, so offline screens need nothing from the network.
 */
@Entity(tableName = "items", indices = [Index("serverId")])
data class DownloadEntity(
    @PrimaryKey val key: String,
    val itemId: String,
    val fileId: String?,
    val serverId: String,
    val record: String,
    /** The format the record names ("flac"), for a file whose bytes and type say nothing. */
    val extensionHint: String?,
    /** For the progress notification. */
    val title: String?,
    val state: String,
    /** Why it failed, in the app's words; null while it can still work. */
    val error: String? = null,
    /** "<server>/<name>.<ext>" under the downloads folder, once done. */
    val fileName: String? = null,
    val bytes: Long = 0,
    /** Downloaded on its own (a song from its menu), not only as part of a collection. */
    val standalone: Boolean = false,
    val queuedAt: Long,
    val downloadedAt: Long? = null,
    val lastPlayedAt: Long? = null,
) {
    companion object {
        const val QUEUED = "queued"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** An album, playlist, book, series or show downloaded as a whole. */
@Entity(tableName = "collections", indices = [Index("serverId")])
data class CollectionEntity(
    @PrimaryKey val id: String,
    /** The app's word: `album`, `playlist`… */
    val kind: String,
    val serverId: String,
    /** The collection's own composite id. */
    val itemId: String,
    val name: String,
    val subtitle: String?,
    val coverId: String?,
    /** The collection as the app shows it, in the app's JSON; may be null. */
    val record: String?,
    val createdAt: Long,
)

@Entity(tableName = "members", primaryKeys = ["collectionId", "itemKey"], indices = [Index("itemKey")])
data class MemberEntity(val collectionId: String, val itemKey: String, val position: Int)

@Dao
interface DownloadsDao {
    @Query("SELECT * FROM items ORDER BY queuedAt, `key`")
    fun itemsFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun collectionsFlow(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM members ORDER BY collectionId, position")
    fun membersFlow(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM items ORDER BY queuedAt, `key`")
    suspend fun items(): List<DownloadEntity>

    @Query("SELECT * FROM items WHERE `key` = :key")
    suspend fun item(key: String): DownloadEntity?

    @Query("SELECT * FROM items WHERE state = 'queued' ORDER BY queuedAt, `key`")
    suspend fun queued(): List<DownloadEntity>

    @Query("SELECT * FROM items WHERE state = 'done'")
    suspend fun done(): List<DownloadEntity>

    @Upsert
    suspend fun upsert(item: DownloadEntity)

    @Query("DELETE FROM items WHERE `key` IN (:keys)")
    suspend fun deleteItems(keys: List<String>)

    @Query("UPDATE items SET lastPlayedAt = :at WHERE `key` = :key")
    suspend fun notePlayed(key: String, at: Long): Int

    @Query("UPDATE items SET state = 'queued', error = NULL WHERE state = 'failed'")
    suspend fun requeueFailed(): Int

    @Query("SELECT * FROM collections")
    suspend fun collections(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: String): CollectionEntity?

    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM members")
    suspend fun members(): List<MemberEntity>

    @Query("SELECT * FROM members WHERE collectionId = :id ORDER BY position")
    suspend fun members(id: String): List<MemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<MemberEntity>)

    @Query("DELETE FROM members WHERE collectionId = :id")
    suspend fun deleteMembers(id: String)
}

@Database(entities = [DownloadEntity::class, CollectionEntity::class, MemberEntity::class], version = 1)
abstract class DownloadsDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadsDao

    companion object {
        fun open(context: Context, name: String = "downloads.db"): DownloadsDatabase =
            Room.databaseBuilder(context.applicationContext, DownloadsDatabase::class.java, name).build()

        fun inMemory(context: Context): DownloadsDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, DownloadsDatabase::class.java).allowMainThreadQueries().build()
    }
}
