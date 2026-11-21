package audio.own.podcast.library.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.serialization.json.Json

/**
 * Everything the podcast library keeps on the device: the shows followed by feed with their
 * episodes, the listener's positions in them, the small values (Up Next, positions waiting for a
 * server, each server's listening state) and each own.audio server's catalogue.
 */
@Database(
    entities = [
        ShowRow::class, EpisodeRow::class, ProgressRow::class, DismissedRow::class, ValueRow::class,
        OwnAudioPodcastRow::class, OwnAudioEpisodeRow::class, OwnAudioProgressRow::class, OwnAudioFeedRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PodcastDatabase : RoomDatabase() {
    abstract fun dao(): PodcastDao

    /** [block] in one transaction: all of it lands, or none. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = withTransaction(block)

    /** Everything of a removed server: its catalogue and the values named after it. */
    suspend fun forgetServer(serverId: String) = inTransaction {
        val dao = dao()
        dao.clearOwnAudioPodcasts(serverId)
        dao.clearOwnAudioEpisodes(serverId)
        dao.clearOwnAudioProgress(serverId)
        dao.clearOwnAudioFeed(serverId)
        dao.deleteValuesStartingWith("$serverId/")
    }

    companion object {
        const val FILE_NAME = "podcasts.db"

        // The device's own shows and positions live here and nowhere else: a schema change needs
        // a migration, never a destructive fallback.
        fun open(context: Context): PodcastDatabase =
            Room.databaseBuilder(context.applicationContext, PodcastDatabase::class.java, FILE_NAME).build()

        /** For tests. */
        fun inMemory(context: Context): PodcastDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PodcastDatabase::class.java).build()
    }
}

/** How the library writes its JSON columns: every field, so a default that is "now" is never lost. */
internal val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
