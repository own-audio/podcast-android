package audio.own.podcast.library.room

import audio.own.kit.feed.FeedFetcher
import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.kit.ownaudio.Podcast
import audio.own.podcast.library.Episode
import audio.own.podcast.library.FeedLibraryState
import audio.own.podcast.library.FeedLibraryStore
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.OwnAudioCatalogue
import audio.own.podcast.library.Show
import audio.own.podcast.library.ShowRecord
import audio.own.podcast.library.ValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.time.Instant
import audio.own.kit.ownaudio.Episode as ServerEpisode
import audio.own.kit.ownaudio.Progress as ServerProgress

/**
 * [FeedLibraryStore] in Room: a row per show and per episode, so one show is read without the
 * others, and a row per position. Each call is one transaction.
 */
class RoomFeedLibraryStore(private val db: PodcastDatabase) : FeedLibraryStore {
    private val dao = db.dao()

    override suspend fun records(): List<ShowRecord> {
        val episodes = dao.allEpisodes().groupBy { it.showId }
        return dao.shows().map { record(it, episodes[it.id].orEmpty()) }
    }

    override suspend fun record(showId: String): ShowRecord? = db.inTransaction {
        dao.show(showId)?.let { record(it, dao.episodes(showId)) }
    }

    override suspend fun put(record: ShowRecord) = db.inTransaction {
        val show = record.show
        dao.upsertShow(
            ShowRow(
                id = show.id,
                feedUrl = show.feedUrl,
                show = StoreJson.encodeToString(Show.serializer(), show),
                etag = record.validators?.etag,
                lastModified = record.validators?.lastModified,
                latestAtFollow = record.latestAtFollow,
            ),
        )
        dao.deleteEpisodesOf(show.id)
        dao.insertEpisodes(
            record.episodes.mapIndexed { index, episode ->
                EpisodeRow(show.id, episode.id, index, StoreJson.encodeToString(Episode.serializer(), episode))
            },
        )
    }

    override suspend fun delete(showId: String) = dao.deleteShow(showId)

    override suspend fun state(): FeedLibraryState = db.inTransaction {
        FeedLibraryState(
            progress = dao.progress().associate { it.key to ListeningProgress(it.positionMs, it.played, Instant.parse(it.updatedAt)) },
            dismissed = dao.dismissed().mapTo(mutableSetOf()) { it.key },
        )
    }

    /** Only what changed is written: a position every minute rewrites one row, not all of them. */
    override suspend fun putState(state: FeedLibraryState) = db.inTransaction {
        val held = state()
        val changed = state.progress.filter { (key, value) -> held.progress[key] != value }
        if (changed.isNotEmpty()) {
            dao.upsertProgress(changed.map { (key, value) -> ProgressRow(key, value.positionMs, value.played, value.updatedAt.toString()) })
        }
        (held.progress.keys - state.progress.keys).chunked(CHUNK).forEach { dao.deleteProgress(it) }
        (state.dismissed - held.dismissed).takeIf { it.isNotEmpty() }?.let { added -> dao.insertDismissed(added.map(::DismissedRow)) }
        (held.dismissed - state.dismissed).chunked(CHUNK).forEach { dao.deleteDismissed(it) }
    }

    private fun record(row: ShowRow, episodes: List<EpisodeRow>) = ShowRecord(
        show = StoreJson.decodeFromString(Show.serializer(), row.show),
        // A row this version can't read is skipped rather than losing the show.
        episodes = episodes.mapNotNull { runCatching { StoreJson.decodeFromString(Episode.serializer(), it.episode) }.getOrNull() },
        validators = if (row.etag == null && row.lastModified == null) null else FeedFetcher.Validators(row.etag, row.lastModified),
        latestAtFollow = row.latestAtFollow,
    )

    private companion object {
        // SQLite allows 999 variables in one statement.
        const val CHUNK = 500
    }
}

/**
 * One value under [key], as JSON. The library's small state (Up Next, pending positions, a
 * server's listening state, a gPodder account's sync state) goes through these.
 */
class RoomValueStore<T : Any>(
    db: PodcastDatabase,
    private val key: String,
    private val serializer: KSerializer<T>,
) : ValueStore<T> {
    private val dao = db.dao()

    override suspend fun load(): T? = dao.value(key)?.let { text ->
        // An older shape that no longer reads is nothing, rather than a crash at every launch.
        runCatching { StoreJson.decodeFromString(serializer, text) }.getOrNull()
    }

    override suspend fun save(value: T?) {
        if (value == null) dao.deleteValue(key) else dao.setValue(ValueRow(key, StoreJson.encodeToString(serializer, value)))
    }
}

/**
 * An own.audio server's catalogue in Room. [OwnAudioPodcastSource][audio.own.podcast.library.OwnAudioPodcastSource]
 * saves it whole after every page of changes; only the records that changed since the last save
 * are written, in one transaction together with the cursor, so a kill between pages reads the
 * same page again and never skips one.
 */
class RoomOwnAudioCatalogueStore(
    private val db: PodcastDatabase,
    private val serverId: String,
) : ValueStore<OwnAudioCatalogue> {
    private val dao = db.dao()
    private val lock = Mutex()
    private var written: OwnAudioCatalogue? = null

    override suspend fun load(): OwnAudioCatalogue? = lock.withLock {
        val feed = dao.ownAudioFeed(serverId)
        val podcasts = dao.ownAudioPodcasts(serverId).decoded(Podcast.serializer()) { it.record }.associateBy { it.id }
        val episodes = dao.ownAudioEpisodes(serverId).decoded(ServerEpisode.serializer()) { it.record }.associateBy { it.id }
        val progress = dao.ownAudioProgress(serverId).mapNotNull { row ->
            decode(ServerProgress.serializer(), row.record)?.let { row.episodeId to it }
        }.toMap()
        if (feed == null && podcasts.isEmpty() && episodes.isEmpty() && progress.isEmpty()) {
            written = null
            return@withLock null
        }
        OwnAudioCatalogue(feed?.cursor, podcasts, episodes, progress, feed?.syncedAt?.let(Instant::parse)).also { written = it }
    }

    override suspend fun save(value: OwnAudioCatalogue?) = lock.withLock {
        db.inTransaction {
            if (value == null) {
                dao.clearOwnAudioPodcasts(serverId)
                dao.clearOwnAudioEpisodes(serverId)
                dao.clearOwnAudioProgress(serverId)
                dao.clearOwnAudioFeed(serverId)
                return@inTransaction
            }
            val before = written ?: OwnAudioCatalogue()
            write(before.podcasts, value.podcasts, { dao.upsertOwnAudioPodcasts(it) }, { dao.deleteOwnAudioPodcasts(serverId, it) }) { id, podcast ->
                OwnAudioPodcastRow(serverId, id, OwnAudioJson.encodeToString(Podcast.serializer(), podcast))
            }
            write(before.episodes, value.episodes, { dao.upsertOwnAudioEpisodes(it) }, { dao.deleteOwnAudioEpisodes(serverId, it) }) { id, episode ->
                OwnAudioEpisodeRow(serverId, id, episode.podcastId, OwnAudioJson.encodeToString(ServerEpisode.serializer(), episode))
            }
            write(before.progress, value.progress, { dao.upsertOwnAudioProgress(it) }, { dao.deleteOwnAudioProgress(serverId, it) }) { id, progress ->
                OwnAudioProgressRow(serverId, id, OwnAudioJson.encodeToString(ServerProgress.serializer(), progress))
            }
            dao.upsertOwnAudioFeed(OwnAudioFeedRow(serverId, value.cursor, value.syncedAt?.toString()))
        }
        written = value
    }

    /** When nothing was read yet ([written] null), every row is written: a fresh database. */
    private suspend fun <V, R> write(
        before: Map<String, V>,
        after: Map<String, V>,
        upsert: suspend (List<R>) -> Unit,
        delete: suspend (List<String>) -> Unit,
        row: (String, V) -> R,
    ) {
        val changed = after.filter { (id, value) -> before[id] != value }.map { (id, value) -> row(id, value) }
        changed.chunked(CHUNK).forEach { upsert(it) }
        (before.keys - after.keys).chunked(CHUNK).forEach { delete(it) }
    }

    private fun <R, V> List<R>.decoded(serializer: KSerializer<V>, text: (R) -> String): List<V> = mapNotNull { decode(serializer, text(it)) }

    private fun <V> decode(serializer: KSerializer<V>, text: String): V? = runCatching { OwnAudioJson.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        const val CHUNK = 500
    }
}
