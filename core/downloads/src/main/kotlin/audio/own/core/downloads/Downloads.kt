package audio.own.core.downloads

import android.util.Base64
import audio.own.player.core.AudioPayloadCheck
import audio.own.player.core.PlaybackLink
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Something to keep on the device. [itemId] is composite; [record] is the app's JSON of it. */
data class DownloadRequest(
    val itemId: String,
    val serverId: String,
    val record: String,
    val fileId: String? = null,
    val extensionHint: String? = null,
    val title: String? = null,
) {
    val key: String get() = Downloads.key(itemId, fileId)
}

/** An album, playlist, book… downloaded as a whole, in its own order. */
data class DownloadCollection(
    val id: String,
    val kind: String,
    val serverId: String,
    val itemId: String,
    val name: String,
    val subtitle: String? = null,
    val coverId: String? = null,
    val record: String? = null,
)

/** Finds the link for a file to keep: the original (own.audio: `playback-info` with `direct` = everything). */
fun interface DownloadLinkResolver {
    suspend fun resolve(item: DownloadEntity): PlaybackLink
}

/** Where downloads run: WorkManager in the app, a fake in tests. Keys are [DownloadEntity.key]s. */
interface DownloadScheduler {
    /** Keys with work that hasn't finished (waiting for its network included). */
    suspend fun active(): Set<String>
    fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean)
    fun cancel(key: String)
    fun cancelAll()
}

/** What the screens show: every item and collection, as the index has them now. */
data class DownloadsSnapshot(
    val items: Map<String, DownloadEntity> = emptyMap(),
    val collections: List<CollectionView> = emptyList(),
    val loaded: Boolean = false,
) {
    data class CollectionView(val collection: CollectionEntity, val keys: List<String>)

    val done: List<DownloadEntity> get() = items.values.filter { it.state == DownloadEntity.DONE }
    val totalBytes: Long get() = items.values.sumOf { if (it.state == DownloadEntity.DONE) it.bytes else 0 }
    val pendingCount: Int get() = items.values.count { it.state == DownloadEntity.QUEUED }
    val failedCount: Int get() = items.values.count { it.state == DownloadEntity.FAILED }

    fun isDownloaded(key: String) = items[key]?.state == DownloadEntity.DONE
    fun collection(id: String) = collections.firstOrNull { it.collection.id == id }

    /** (downloaded, total) of a collection; null when it isn't downloaded at all. */
    fun progress(collectionId: String): Pair<Int, Int>? {
        val view = collection(collectionId) ?: return null
        return view.keys.count(::isDownloaded) to view.keys.size
    }

    /** Whether a collection still has songs on their way (not failed, not done). */
    fun isDownloading(collectionId: String): Boolean =
        collection(collectionId)?.keys?.any { items[it]?.state == DownloadEntity.QUEUED } == true

    /** Kept items that belong to no collection, newest first. */
    val loose: List<DownloadEntity>
        get() {
            val inCollections = collections.flatMapTo(HashSet()) { it.keys }
            return items.values.filter { it.key !in inCollections && it.state == DownloadEntity.DONE }.sortedByDescending { it.downloadedAt ?: 0 }
        }
}

/**
 * Files kept on the device for offline listening, and the collections they were downloaded as.
 *
 * Each file is one WorkManager job (`download:<key>`, a foreground worker), two at a time so
 * signed links are fresh when a file's turn comes; the rest wait in the index and [pump] starts
 * them as others finish. Files live in `<root>/<server>/…`, plain files with a real extension;
 * the index (Room) keeps each item's whole record, so offline screens need no network.
 */
class Downloads(
    db: DownloadsDatabase,
    val root: File,
    private val scheduler: DownloadScheduler,
    private val resolver: DownloadLinkResolver,
    private val fetcher: DownloadFetcher,
    private val settings: Settings,
    private val scope: CoroutineScope,
    /** Every server's kept items (composite ids), after changes, debounced: `PUT /me/holdings`. */
    private val onHoldingsChanged: (suspend (Map<String, List<String>>) -> Unit)? = null,
    /** Worth trying again later (no network, a server error) rather than failed for good. */
    private val isTransient: (Throwable) -> Boolean = { it is IOException },
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val holdingsDelayMs: Long = 3_000,
) {
    /** What the listener chose, read each time. */
    interface Settings {
        fun wifiOnly(): Boolean
        /** Keep at Most, in bytes; null is no limit. */
        fun capBytes(): Long?
        /** The key of what is playing now, never evicted. */
        fun protectedKey(): String?
    }

    private val dao = db.downloads()
    private val pumpLock = Mutex()
    private val changeLock = Mutex()
    private var holdingsJob: Job? = null
    private val reportedServers = HashSet<String>()

    /** Key → file, readable from any thread: the player asks from its own. */
    private val files = ConcurrentHashMap<String, File>()
    @Volatile private var filesLoaded = false

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Fraction done of what is downloading now, by key; updated at most twice a second. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    val state: StateFlow<DownloadsSnapshot> = combine(dao.itemsFlow(), dao.collectionsFlow(), dao.membersFlow()) { items, collections, members ->
        val byCollection = members.groupBy { it.collectionId }
        DownloadsSnapshot(
            items = items.associateBy { it.key },
            collections = collections.map { c -> DownloadsSnapshot.CollectionView(c, byCollection[c.id].orEmpty().sortedBy { it.position }.map { it.itemKey }) },
            loaded = true,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DownloadsSnapshot())

    // region Reading

    /** The file of a kept item, or null. Loads the index once, on first use. */
    fun file(key: String): File? {
        if (!filesLoaded) loadFiles()
        return files[key]
    }

    private fun loadFiles() = synchronized(files) {
        if (filesLoaded) return
        // A few milliseconds once; Room refuses the main thread, so the read runs on IO.
        val done = runBlocking(Dispatchers.IO) { dao.done() }
        for (item in done) item.fileName?.let { files[item.key] = File(root, it) }
        filesLoaded = true
    }

    // endregion

    // region Changing

    /** Queues [requests]; with [collection], as that collection's members in this order. */
    suspend fun download(requests: List<DownloadRequest>, collection: DownloadCollection? = null) {
        changeLock.withLock {
            val t = now()
            requests.forEachIndexed { index, request ->
                val existing = dao.item(request.key)
                val standalone = (existing?.standalone ?: false) || collection == null
                when {
                    existing == null -> dao.upsert(
                        DownloadEntity(
                            key = request.key, itemId = request.itemId, fileId = request.fileId, serverId = request.serverId,
                            record = request.record, extensionHint = request.extensionHint, title = request.title,
                            state = DownloadEntity.QUEUED, standalone = standalone, queuedAt = t + index,
                        ),
                    )
                    existing.state == DownloadEntity.FAILED -> dao.upsert(existing.copy(state = DownloadEntity.QUEUED, error = null, standalone = standalone, record = request.record))
                    else -> dao.upsert(existing.copy(standalone = standalone, record = request.record))
                }
            }
            if (collection != null) {
                dao.upsert(
                    CollectionEntity(
                        id = collection.id, kind = collection.kind, serverId = collection.serverId, itemId = collection.itemId,
                        name = collection.name, subtitle = collection.subtitle, coverId = collection.coverId, record = collection.record,
                        createdAt = dao.collection(collection.id)?.createdAt ?: t,
                    ),
                )
                dao.deleteMembers(collection.id)
                dao.insertMembers(requests.mapIndexed { i, r -> MemberEntity(collection.id, r.key, i) })
            }
        }
        pump()
    }

    /**
     * Removes an item kept on its own. A collection that still lists it keeps the file; the
     * item just stops being "downloaded on its own".
     */
    suspend fun remove(key: String) {
        changeLock.withLock {
            val item = dao.item(key) ?: return
            val needed = dao.members().any { it.itemKey == key }
            if (needed) dao.upsert(item.copy(standalone = false)) else deleteItems(listOf(key))
        }
        afterChange()
    }

    /** Removes a collection, and each of its files that no other collection needs. */
    suspend fun removeCollection(id: String) {
        changeLock.withLock {
            val keys = dao.members(id).map { it.itemKey }
            dao.deleteCollection(id)
            dao.deleteMembers(id)
            val stillNeeded = dao.members().mapTo(HashSet()) { it.itemKey }
            val doomed = keys.filter { it !in stillNeeded && dao.item(it)?.standalone != true }
            deleteItems(doomed)
        }
        afterChange()
        pump()
    }

    /** Everything, or one server's (a removed server's downloads leave with it). */
    suspend fun removeAll(serverId: String? = null) {
        changeLock.withLock {
            val collections = dao.collections().filter { serverId == null || it.serverId == serverId }
            for (c in collections) {
                dao.deleteCollection(c.id)
                dao.deleteMembers(c.id)
            }
            deleteItems(dao.items().filter { serverId == null || it.serverId == serverId }.map { it.key })
        }
        afterChange()
    }

    /** Retry N Failed. */
    suspend fun retryFailed() {
        dao.requeueFailed()
        pump()
    }

    /** A kept item played: it moves to the back of the line for Keep at Most. */
    suspend fun notePlayed(key: String) {
        dao.notePlayed(key, now())
    }

    /** Download on Wi-Fi Only changed: the waiting work starts again under the new rule. */
    suspend fun wifiOnlyChanged() {
        pumpLock.withLock {
            scheduler.cancelAll()
            val queued = dao.queued()
            for (item in queued.take(PARALLEL)) scheduler.enqueue(item.key, settings.wifiOnly(), replace = true)
        }
    }

    /**
     * Lets go of files until the downloads fit Keep at Most: the ones not played for longest
     * first, never the one playing. A file an album still lists goes too; the album then shows
     * as partly downloaded.
     */
    suspend fun enforceCap() {
        val cap = settings.capBytes() ?: return
        val evicted = changeLock.withLock {
            val candidates = dao.done().map { EvictionPolicy.Candidate(it.key, it.bytes, false, it.lastPlayedAt ?: it.downloadedAt ?: it.queuedAt) }
            val out = EvictionPolicy(cap).evictions(candidates, settings.protectedKey())
            if (out.isNotEmpty()) deleteItems(out)
            out
        }
        if (evicted.isNotEmpty()) {
            log("evicted ${evicted.size} over Keep at Most")
            scheduleHoldings()
        }
    }

    /**
     * At launch: files the index doesn't know are deleted, items whose file is gone are
     * forgotten, and what was waiting starts again.
     */
    suspend fun reconcile() {
        withContext(Dispatchers.IO) {
            changeLock.withLock {
                val items = dao.items()
                val lost = items.filter { it.state == DownloadEntity.DONE && (it.fileName == null || !File(root, it.fileName).isFile) }
                if (lost.isNotEmpty()) {
                    dao.deleteItems(lost.map { it.key })
                    lost.forEach { files.remove(it.key) }
                    log("forgot ${lost.size} downloads whose files are gone")
                }
                val keep = HashSet<String>()
                for (item in items) {
                    if (item.state == DownloadEntity.DONE) item.fileName?.let { keep += File(root, it).canonicalPath }
                    if (item.state == DownloadEntity.QUEUED) keep += partFile(item).canonicalPath
                }
                var orphans = 0
                root.walkBottomUp().forEach { file ->
                    if (file == root) return@forEach
                    if (file.isFile && file.canonicalPath !in keep) {
                        if (file.delete()) orphans++
                    } else if (file.isDirectory && file.list()?.isEmpty() == true) {
                        file.delete()
                    }
                }
                if (orphans > 0) log("deleted $orphans files the index didn't know")
            }
        }
        if (!filesLoaded) loadFiles()
        pump()
    }

    /** Starts waiting items while fewer than two run. */
    suspend fun pump(finishing: String? = null) {
        pumpLock.withLock {
            val active = scheduler.active() - setOfNotNull(finishing)
            val free = PARALLEL - active.size
            if (free <= 0) return
            val waiting = dao.queued().filter { it.key !in active && it.key != finishing }
            for (item in waiting.take(free)) scheduler.enqueue(item.key, settings.wifiOnly(), replace = false)
        }
    }

    // endregion

    // region Work

    /** What one worker run comes to. */
    enum class Outcome { DONE, RETRY, FAILED }

    /**
     * Downloads one file: called by [DownloadWorker]. [attempt] counts earlier runs; after
     * [MAX_ATTEMPTS] a passing problem counts as failed. [onStart] runs once the item is known
     * (the worker goes to the foreground with its title).
     */
    suspend fun run(key: String, attempt: Int, onStart: suspend (DownloadEntity) -> Unit = {}, onProgress: suspend (Float?) -> Unit = {}): Outcome {
        val item = dao.item(key) ?: return Outcome.DONE
        if (item.state == DownloadEntity.DONE && file(key)?.isFile == true) return Outcome.DONE
        if (item.state == DownloadEntity.FAILED) return Outcome.FAILED
        onStart(item)
        val part = partFile(item)
        val outcome = try {
            var link = resolver.resolve(item)
            val fetched = try {
                fetch(key, link, part, onProgress)
            } catch (_: LinkExpired) {
                link = resolver.resolve(item)
                fetch(key, link, part, onProgress)
            }
            keep(item, part, fetched)
        } catch (e: CancellationException) {
            _progress.update { it - key }
            throw e
        } catch (e: DownloadFailure) {
            if (e.permanent) fail(item, part, e.code) else retryOrFail(item, attempt, e.code)
        } catch (e: LinkExpired) {
            retryOrFail(item, attempt, DownloadFailure.LINK)
        } catch (e: Exception) {
            log("download of $key: ${e.javaClass.simpleName} ${e.message}")
            if (isTransient(e)) retryOrFail(item, attempt, DownloadFailure.NETWORK) else fail(item, part, DownloadFailure.LINK)
        }
        _progress.update { it - key }
        if (outcome != Outcome.RETRY) pump(finishing = key)
        return outcome
    }

    private suspend fun fetch(key: String, link: PlaybackLink, part: File, onProgress: suspend (Float?) -> Unit): DownloadFetcher.Fetched {
        return fetcher.fetch(link, part) { done, total ->
            val fraction = total?.takeIf { it > 0 }?.let { (done.toDouble() / it).toFloat().coerceIn(0f, 1f) }
            _progress.update { it + (key to (fraction ?: 0f)) }
            onProgress(fraction)
        }
    }

    private suspend fun keep(item: DownloadEntity, part: File, fetched: DownloadFetcher.Fetched): Outcome {
        if (!AudioPayloadCheck.looksLikeAudio(fetched.contentType, part, item.extensionHint)) {
            return fail(item, part, DownloadFailure.NOT_AUDIO)
        }
        val prefix = part.inputStream().use { input -> ByteArray(AudioPayloadCheck.PREFIX_BYTES).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val ext = DownloadFetcher.extension(prefix, fetched.contentType, item.extensionHint)
        val relative = "${safeName(item.serverId)}/${safeName(item.key)}.$ext"
        val target = File(root, relative)
        val kept = changeLock.withLock {
            // Removed while it downloaded: nothing to keep.
            val now = dao.item(item.key) ?: return@withLock false
            target.delete()
            if (!part.renameTo(target)) throw DownloadFailure(DownloadFailure.STORAGE, permanent = false)
            dao.upsert(now.copy(state = DownloadEntity.DONE, error = null, fileName = relative, bytes = target.length(), downloadedAt = now()))
            files[item.key] = target
            true
        }
        if (!kept) {
            part.delete()
            return Outcome.DONE
        }
        afterChange()
        return Outcome.DONE
    }

    private suspend fun fail(item: DownloadEntity, part: File, code: String): Outcome {
        part.delete()
        dao.item(item.key)?.let { dao.upsert(it.copy(state = DownloadEntity.FAILED, error = code)) }
        log("download of ${item.key} failed: $code")
        return Outcome.FAILED
    }

    private suspend fun retryOrFail(item: DownloadEntity, attempt: Int, code: String): Outcome {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            // The .part stays: Retry picks up where it got.
            dao.item(item.key)?.let { dao.upsert(it.copy(state = DownloadEntity.FAILED, error = code)) }
            return Outcome.FAILED
        }
        return Outcome.RETRY
    }

    private suspend fun afterChange() {
        enforceCap()
        scheduleHoldings()
    }

    private fun scheduleHoldings() {
        val report = onHoldingsChanged ?: return
        holdingsJob?.cancel()
        holdingsJob = scope.launch {
            delay(holdingsDelayMs)
            val byServer = dao.done().groupBy({ it.serverId }, { it.itemId }).mapValues { it.value.distinct() }
            // A server whose last download went is reported once more, with nothing.
            val servers = byServer.keys + reportedServers
            try {
                report(servers.associateWith { byServer[it].orEmpty() })
                reportedServers.clear()
                reportedServers += byServer.keys
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("couldn't report holdings: ${e.message}")
            }
        }
    }

    /** Called with the index lock held. */
    private suspend fun deleteItems(keys: List<String>) {
        if (keys.isEmpty()) return
        val items = keys.mapNotNull { dao.item(it) }
        for (item in items) {
            scheduler.cancel(item.key)
            item.fileName?.let { File(root, it).delete() }
            partFile(item).delete()
            files.remove(item.key)
        }
        dao.deleteItems(keys)
        _progress.update { it - keys.toSet() }
    }

    fun partFile(item: DownloadEntity): File = File(root, "${safeName(item.serverId)}/${safeName(item.key)}.part")

    // endregion

    companion object {
        /** Two at a time: a signed link asked for long before its turn would have expired. */
        const val PARALLEL = 2
        const val MAX_ATTEMPTS = 8

        fun key(itemId: String, fileId: String?) = if (fileId == null) itemId else "$itemId#$fileId"

        /** A file name from any id: URL-safe Base64, no padding. */
        fun safeName(id: String): String =
            Base64.encodeToString(id.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
