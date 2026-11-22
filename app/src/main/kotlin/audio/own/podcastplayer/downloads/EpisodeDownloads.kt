package audio.own.podcastplayer.downloads

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadFailure
import audio.own.core.downloads.DownloadFetcher
import audio.own.core.downloads.DownloadNotifications
import audio.own.core.downloads.DownloadRequest
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.DownloadWorker
import audio.own.core.downloads.DownloadWorkerFactory
import audio.own.core.downloads.Downloads
import audio.own.core.downloads.DownloadsDatabase
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import audio.own.podcast.library.Episode
import audio.own.podcast.library.Playback
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceException
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.R
import audio.own.podcastplayer.app.AppLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * An episode as the downloads index keeps it: the episode and its show as they were, and the
 * source it came from, so the Downloads tab and the player need no network. [automatic]: fetched
 * by a show's rules rather than asked for.
 */
@Serializable
data class DownloadedEpisode(
    /** [SourceRef.id]. */
    val source: String,
    val episode: Episode,
    val show: Show? = null,
    val automatic: Boolean = false,
) {
    val ref: SourceRef? get() = SourceRef.parse(source)
    val item: SourcedEpisode? get() = ref?.let { SourcedEpisode(it, episode) }
}

/** One episode on the device or on its way, for the screens and the rules. */
data class EpisodeDownload(
    /** [SourcedEpisode.id]. */
    val id: String,
    val record: DownloadedEpisode,
    val status: Status,
    val bytes: Long = 0,
    /** How far it got, while downloading; null when the size isn't known yet. */
    val fraction: Float? = null,
    /** Why it failed: one of [DownloadFailure]'s codes, or [EpisodeDownloads.STREAM_ONLY]. */
    val error: String? = null,
    val queuedAt: Long = 0,
    val finishedAt: Long? = null,
    val lastPlayedAt: Long? = null,
) {
    enum class Status { DOWNLOADING, DONE, FAILED }

    val isDone: Boolean get() = status == Status.DONE
}

data class DownloadsState(val entries: Map<String, EpisodeDownload> = emptyMap(), val loaded: Boolean = false) {
    val totalBytes: Long get() = entries.values.sumOf { if (it.isDone) it.bytes else 0 }
    val downloadingCount: Int get() = entries.values.count { it.status == EpisodeDownload.Status.DOWNLOADING }
    val failedCount: Int get() = entries.values.count { it.status == EpisodeDownload.Status.FAILED }

    fun status(id: String): EpisodeDownload.Status? = entries[id]?.status

    fun isDownloaded(id: String): Boolean = entries[id]?.isDone == true
}

/** What the podcast model asks of the downloads: [EpisodeDownloads] on the device, a fake in tests. */
interface EpisodeDownloader {
    val state: StateFlow<DownloadsState>

    /** The episode's file, or null to stream it. Any thread. */
    fun localFile(id: String): Uri?

    /**
     * Keeps [item] on the device. [automatic] for what a show's rules fetch: it waits for an
     * unmetered network when Wi-Fi Only is on and for a battery that isn't low. Asking for an
     * episode a rule is fetching moves it to the quick queue.
     */
    suspend fun download(item: SourcedEpisode, show: Show?, automatic: Boolean = false)

    suspend fun remove(ids: Collection<String>)

    /** Everything, or what came from one source ([SourceRef.id], "phone" for the device's feeds). */
    suspend fun removeAll(source: String? = null)

    suspend fun retryFailed()

    /** Played now: it goes later when the storage cap lets downloads go. */
    suspend fun notePlayed(id: String)

    /** Download on Wi-Fi Only changed: what waits starts again under the new rule. */
    suspend fun wifiOnlyChanged()

    companion object {
        /** Downloads that never happen, for a model without them (previews, some tests). */
        val None = object : EpisodeDownloader {
            override val state: StateFlow<DownloadsState> = MutableStateFlow(DownloadsState(loaded = true)).asStateFlow()
            override fun localFile(id: String): Uri? = null
            override suspend fun download(item: SourcedEpisode, show: Show?, automatic: Boolean) = Unit
            override suspend fun remove(ids: Collection<String>) = Unit
            override suspend fun removeAll(source: String?) = Unit
            override suspend fun retryFailed() = Unit
            override suspend fun notePlayed(id: String) = Unit
            override suspend fun wifiOnlyChanged() = Unit
        }
    }
}

/**
 * Episodes kept on the device, through `:core:downloads`: one WorkManager job per episode, a
 * plain file with a real extension in `filesDir/downloads/<source>/`, and an index holding each
 * episode's whole record. The port of the iOS app's `DownloadManager`, whose two URL sessions
 * become two kinds of work: what the listener asked for runs expedited, what a show's rules fetch
 * waits for its network and a battery that isn't low.
 *
 * The storage cap is the podcast model's (played episodes go first), so the core's own is off.
 */
class EpisodeDownloads(
    context: Context,
    http: OkHttpClient,
    userAgent: String,
    proxyMemory: ProxyMemory,
    /** Where the episode's file is now: the source's answer, as for playing it. */
    private val playback: suspend (SourcedEpisode) -> Playback,
    private val wifiOnly: () -> Boolean,
    /** The episode playing now, never let go of. */
    private val playing: () -> String?,
    /** Every own.audio server's downloaded episodes (their ids there), after changes: `PUT /me/holdings`. */
    reportHoldings: suspend (serverId: String, episodeIds: List<String>) -> Unit,
    private val log: AppLog,
    scope: CoroutineScope,
    root: File = File(context.applicationContext.filesDir, "downloads"),
    db: DownloadsDatabase = DownloadsDatabase.open(context.applicationContext),
    workManager: () -> WorkManager = { WorkManager.getInstance(context.applicationContext) },
    /** Where the work runs, told which keys are a rule's; WorkManager in the app. */
    schedulerFor: ((isAutomatic: (String) -> Boolean) -> DownloadScheduler) = { PodcastDownloadScheduler(workManager, it) },
) : EpisodeDownloader {
    /** Keys of downloads a rule started; the rest are the listener's. */
    private val automatic: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scheduler = schedulerFor { it in automatic }

    /** Asked for in this run and not removed since. */
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val core = Downloads(
        db = db,
        root = root,
        scheduler = scheduler,
        resolver = { entity -> link(entity) },
        fetcher = DownloadFetcher(http, proxyMemory, userAgent),
        settings = object : Downloads.Settings {
            override fun wifiOnly() = this@EpisodeDownloads.wifiOnly()
            override fun capBytes(): Long? = null
            override fun protectedKey() = playing()
        },
        scope = scope,
        onHoldingsChanged = { byServer -> byServer.forEach { (server, ids) -> if (server != PHONE) reportHoldings(server, ids.mapNotNull(::episodeIdOf)) } },
        isTransient = ::isTransient,
        log = { log.info("downloads", it) },
    )

    val workerFactory = DownloadWorkerFactory(
        downloads = { core },
        notifications = DownloadNotifications(
            channelName = { it.getString(R.string.tab_downloads) },
            text = { ctx, title -> if (title != null) ctx.getString(R.string.downloading_title, title) else ctx.getString(R.string.downloading_episodes) },
            smallIcon = R.drawable.ic_stat_download,
        ),
    )

    override val state: StateFlow<DownloadsState> = combine(core.state, core.progress) { snapshot, progress -> view(snapshot, progress) }
        .stateIn(scope, SharingStarted.Eagerly, DownloadsState())

    /** At launch: the rules' downloads are known again, then what the index lost goes and what waited starts again. */
    suspend fun start() {
        val snapshot = core.state.first { it.loaded }
        snapshot.items.values.filter { it.state != DownloadEntity.DONE && record(it)?.automatic == true }.forEach { automatic += it.key }
        core.reconcile()
    }

    override fun localFile(id: String): Uri? = core.file(id)?.takeIf { it.isFile }?.let(Uri::fromFile)

    override suspend fun download(item: SourcedEpisode, show: Show?, automatic: Boolean) {
        val id = item.id
        val existing = core.state.value.items[id]
        if (existing?.state == DownloadEntity.DONE) return
        // The index's flow may not have caught up with a download asked for a moment ago.
        val known = existing != null || id in asked
        val wasAutomatic = id in this.automatic
        // A rule doesn't take over what the listener asked for, nor start a failed one again.
        if (automatic && known) return
        asked += id
        if (automatic) this.automatic += id else this.automatic -= id
        val record = DownloadedEpisode(item.source.id, item.episode, show, automatic)
        core.download(
            listOf(
                DownloadRequest(
                    itemId = id,
                    serverId = item.source.serverId?.toString() ?: PHONE,
                    record = recordJson.encodeToString(DownloadedEpisode.serializer(), record),
                    extensionHint = extensionHint(item.episode),
                    title = item.episode.title,
                ),
            ),
        )
        // The listener's starts now, beside whatever else runs; one a rule was fetching moves
        // to the quick queue, and picks up where it got.
        if (!automatic) scheduler.enqueue(id, wifiOnly(), replace = wasAutomatic)
    }

    override suspend fun remove(ids: Collection<String>) {
        for (id in ids) {
            automatic -= id
            asked -= id
            core.remove(id)
        }
    }

    override suspend fun removeAll(source: String?) {
        if (source == null) {
            automatic.clear()
            asked.clear()
            core.removeAll()
        } else {
            val server = if (source == PHONE) PHONE else SourceRef.parse(source)?.serverId?.toString() ?: return
            core.removeAll(server)
        }
    }

    override suspend fun retryFailed() = core.retryFailed()

    override suspend fun notePlayed(id: String) {
        if (core.state.value.isDownloaded(id)) core.notePlayed(id)
    }

    override suspend fun wifiOnlyChanged() = core.wifiOnlyChanged()

    private suspend fun link(entity: DownloadEntity): PlaybackLink {
        val item = record(entity)?.item ?: throw DownloadFailure(DownloadFailure.LINK, permanent = true)
        val answer = playback(item)
        // A transcode made for one listen is no file to keep.
        if (answer.isHls) throw DownloadFailure(STREAM_ONLY, permanent = true)
        return PlaybackLink(answer.url, answer.expiresAt?.toEpochMilli(), item.episode.audioType)
    }

    private fun view(snapshot: DownloadsSnapshot, progress: Map<String, Float>): DownloadsState {
        val entries = snapshot.items.values.mapNotNull { entity ->
            val record = record(entity) ?: return@mapNotNull null
            val status = when (entity.state) {
                DownloadEntity.DONE -> EpisodeDownload.Status.DONE
                DownloadEntity.FAILED -> EpisodeDownload.Status.FAILED
                else -> EpisodeDownload.Status.DOWNLOADING
            }
            EpisodeDownload(
                id = entity.key,
                record = record,
                status = status,
                bytes = entity.bytes,
                fraction = progress[entity.key],
                error = entity.error,
                queuedAt = entity.queuedAt,
                finishedAt = entity.downloadedAt,
                lastPlayedAt = entity.lastPlayedAt,
            )
        }
        return DownloadsState(entries.associateBy { it.id }, loaded = snapshot.loaded)
    }

    private fun isTransient(error: Throwable): Boolean = error is IOException || error is SourceException.Network

    companion object {
        /** The server only streams the episode (a transcode as HLS): nothing to download. */
        const val STREAM_ONLY = "stream_only"

        /** The downloads' folder and server id for the device's own feeds. */
        const val PHONE = "phone"

        private val recordJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun record(entity: DownloadEntity): DownloadedEpisode? =
            runCatching { recordJson.decodeFromString(DownloadedEpisode.serializer(), entity.record) }.getOrNull()

        /** The episode's own id on its server, from the app's composite id. */
        fun episodeIdOf(id: String): String? = SourcedEpisode.parse(id)?.second?.episodeId

        /** For a file whose bytes and content type say nothing: what the feed's type or address names. */
        fun extensionHint(episode: Episode): String? {
            val type = episode.audioType?.substringBefore(';')?.trim()?.lowercase()
            val byType = when (type) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
                "audio/ogg", "audio/opus" -> "ogg"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/wav", "audio/x-wav" -> "wav"
                else -> null
            }
            if (byType != null) return byType
            val path = episode.audioUrl?.substringBefore('?')?.substringAfterLast('/') ?: return null
            return path.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
        }
    }
}

/**
 * The two kinds of download work: the listener's expedited, a rule's under constraints (an
 * unmetered network with Wi-Fi Only, a battery that isn't low). One unique job per episode, with
 * backoff for passing failures.
 */
class PodcastDownloadScheduler(
    private val workManager: () -> WorkManager,
    private val isAutomatic: (String) -> Boolean,
) : DownloadScheduler {
    override suspend fun active(): Set<String> = withContext(Dispatchers.IO) {
        workManager().getWorkInfosByTag(DownloadWorker.TAG).get()
            .filter { !it.state.isFinished }
            .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG) }
            .toSet()
    }

    override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) {
        workManager().enqueueUniqueWork(DownloadWorker.workName(key), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(key, wifiOnly, isAutomatic(key)))
    }

    override fun cancel(key: String) {
        workManager().cancelUniqueWork(DownloadWorker.workName(key))
    }

    override fun cancelAll() {
        workManager().cancelAllWorkByTag(DownloadWorker.TAG)
    }

    companion object {
        private const val KEY_TAG = "download-key:"

        fun request(key: String, wifiOnly: Boolean, automatic: Boolean) = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY to key))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(automatic)
                    .build(),
            )
            .apply { if (!automatic) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag(KEY_TAG + key)
            .build()
    }
}
