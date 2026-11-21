package audio.own.core.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of one entry sent to its server. */
enum class Delivery {
    /** Taken (or already known: a duplicate, a stale position): done with. */
    DONE,

    /** Not taken this time (no network, a server error): kept, and nothing after it is sent now. */
    KEEP,

    /** Refused for good (the item is gone): dropped, so it can't block the rest. */
    DROP,
}

/**
 * Sends entries to their server: one answer per entry, in order. A whole batch that can't be
 * sent throws; the entries stay and the flush of that server stops.
 */
interface OutboxSender {
    suspend fun sendChanges(serverId: String, changes: List<PendingChange>): List<Delivery>
    suspend fun sendPlays(serverId: String, plays: List<PendingPlay>): List<Delivery>
    suspend fun sendProgress(serverId: String, entries: List<PendingProgress>): List<Delivery> = entries.map { Delivery.DROP }
}

/** How a flush went. */
data class FlushReport(val sent: Int = 0, val dropped: Int = 0, val kept: Int = 0) {
    operator fun plus(other: FlushReport) = FlushReport(sent + other.sent, dropped + other.dropped, kept + other.kept)
}

/**
 * What the device did that its servers haven't heard yet: positions, changes, plays. Everything is
 * stored first and sent after, oldest first, so nothing is lost to a dead network or a killed app.
 * Flushed at launch, on foreground, after the network comes back and by the periodic job.
 */
class Outbox(
    db: OutboxDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val dao = db.outbox()
    private val flushLock = Mutex()

    private val _pending = MutableStateFlow(0)
    /** How many entries wait, for a Settings or Logs line; updated on every change. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** A position report: merged per (server, item, session). */
    suspend fun recordProgress(entry: PendingProgress) {
        dao.mergeProgress(entry)
        count()
    }

    /** A change; one with the same [PendingChange.mergeKey] replaces the earlier. */
    suspend fun recordChange(change: PendingChange) {
        dao.replaceChange(change, CHANGES_LIMIT)
        count()
    }

    /** A counted play; the same client id twice is kept once. */
    suspend fun recordPlay(play: PendingPlay): Boolean {
        val added = dao.addPlay(play, PLAYS_LIMIT)
        count()
        return added
    }

    suspend fun changes(): List<PendingChange> = dao.allChanges()
    suspend fun plays(): List<PendingPlay> = dao.allPlays()
    suspend fun progress(): List<PendingProgress> = dao.allProgress()

    /** A removed server's entries: there is nowhere to send them. */
    suspend fun forget(serverId: String) {
        dao.forgetChanges(serverId)
        dao.forgetPlays(serverId)
        dao.forgetProgress(serverId)
        count()
    }

    /**
     * Sends everything waiting, server by server, oldest first, in batches of [BATCH]. Plays go
     * first: a favourite made after a play doesn't depend on it, but a play is the thing most
     * worth not losing. One flush at a time; a call during a flush waits for it.
     */
    suspend fun flush(sender: OutboxSender, servers: Set<String>? = null): FlushReport = flushLock.withLock {
        var report = FlushReport()
        report += send(dao.allPlays(), servers, { it.serverId }, sender::sendPlays) { dao.deletePlays(it.map(PendingPlay::clientId)) }
        report += send(dao.allChanges(), servers, { it.serverId }, sender::sendChanges) { dao.deleteChanges(it.map(PendingChange::id)) }
        report += send(dao.allProgress(), servers, { it.serverId }, sender::sendProgress) { done -> done.forEach { dao.deleteProgress(it.serverId, it.itemId, it.sessionId, it.at) } }
        count()
        if (report.sent + report.dropped > 0) log("outbox: sent ${report.sent}, dropped ${report.dropped}, kept ${report.kept}")
        report
    }

    private suspend fun <T> send(
        entries: List<T>,
        servers: Set<String>?,
        serverOf: (T) -> String,
        deliver: suspend (String, List<T>) -> List<Delivery>,
        delete: suspend (List<T>) -> Unit,
    ): FlushReport {
        var report = FlushReport()
        val byServer = entries.groupBy(serverOf).filterKeys { servers == null || it in servers }
        for ((serverId, list) in byServer) {
            var index = 0
            server@ while (index < list.size) {
                val batch = list.subList(index, minOf(index + BATCH, list.size))
                val answers = try {
                    deliver(serverId, batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("outbox: $serverId not reached: ${e.javaClass.simpleName}")
                    report += FlushReport(kept = list.size - index)
                    break@server
                }
                val finished = ArrayList<T>()
                for ((i, entry) in batch.withIndex()) {
                    when (answers.getOrNull(i) ?: Delivery.KEEP) {
                        Delivery.DONE -> { finished += entry; report += FlushReport(sent = 1) }
                        Delivery.DROP -> { finished += entry; report += FlushReport(dropped = 1) }
                        Delivery.KEEP -> {
                            delete(finished)
                            report += FlushReport(kept = list.size - index - i)
                            break@server
                        }
                    }
                }
                delete(finished)
                index += batch.size
            }
        }
        return report
    }

    private suspend fun count() {
        _pending.value = dao.allChanges().size + dao.allPlays().size + dao.allProgress().size
    }

    companion object {
        /** A device offline for weeks shouldn't grow these without bound. */
        const val CHANGES_LIMIT = 1000
        const val PLAYS_LIMIT = 500

        /** `/sync/push` takes at most 500 at a time. */
        const val BATCH = 500

        /** The play's id: the same play always gets the same one. */
        fun playId(itemId: String, startedAtMs: Long) = "$itemId@$startedAtMs"
    }
}
