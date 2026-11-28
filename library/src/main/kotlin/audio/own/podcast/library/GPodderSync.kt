@file:UseSerializers(InstantSerializer::class)

package audio.own.podcast.library

import audio.own.kit.gpodder.EpisodeAction
import audio.own.kit.gpodder.GPodderClient
import audio.own.kit.gpodder.GPodderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant

/** What [GPodderSync] needs of the device's feeds; [FeedLibrary] is one. */
interface GPodderLibrary {
    suspend fun shows(): List<Show>

    suspend fun episodes(showId: String): List<Episode>

    suspend fun episode(key: EpisodeKey): Episode

    suspend fun follow(feedUrl: String): Show

    suspend fun unfollow(showId: String)

    suspend fun progress(key: EpisodeKey): ListeningProgress?

    suspend fun allProgress(): Map<EpisodeKey, ListeningProgress>

    /** Returns what the library holds afterwards: the newer position wins. */
    suspend fun saveProgress(progress: ListeningProgress, key: EpisodeKey): ListeningProgress
}

/** One account's sync state, kept per device id. */
@Serializable
data class GPodderSyncState(
    val subscriptionsSince: Long = 0,
    val actionsSince: Long = 0,
    /** The feeds both sides agreed on after the last sync; a feed missing from it on one side was removed there. */
    val syncedFeeds: Set<String> = emptySet(),
    /** When each episode's position was last sent (or came from the account), so only what changed goes up. */
    val sent: Map<String, Instant> = emptyMap(),
    /**
     * The account's addresses that are a show this device follows under another address (a
     * redirect, a personal feed, a moved feed): the account's address to the device's.
     */
    val aliases: Map<String, String> = emptyMap(),
)

/**
 * Keeps the device's feeds and a gPodder account (podfetch, own.audio, gpodder.net) in step:
 * subscriptions both ways, and positions and played marks as episode actions. The newer of two
 * positions wins, so whichever device was listened to last decides.
 *
 * @param device this device's id on the account, stable for the life of the install.
 */
class GPodderSync(
    private val client: GPodderClient,
    private val library: GPodderLibrary,
    private val device: String,
    private val store: ValueStore<GPodderSyncState>,
) {
    data class Report(
        var followed: Int = 0,
        var unfollowed: Int = 0,
        var uploadedShows: Int = 0,
        var positionsApplied: Int = 0,
        var positionsSent: Int = 0,
    )

    private val mutex = Mutex()
    private var state: GPodderSyncState? = null

    /** The feed URLs the account and this device agreed on at the last sync: the account's shows. */
    suspend fun accountFeeds(): Set<String> = mutex.withLock { current().syncedFeeds }

    suspend fun register(caption: String) = client.registerDevice(device, caption)

    suspend fun sync(): Report = mutex.withLock {
        val report = Report()
        syncSubscriptions(report)
        pullActions(report)
        pushActions(report)
        store.save(current())
        report
    }

    // MARK: subscriptions

    private suspend fun syncSubscriptions(report: Report) {
        val start = current()
        val firstSync = start.subscriptionsSince == 0L
        // What the listener did on the device since the last sync, worked out before the account's
        // changes are applied: it is newer than anything the account can say, and a server that
        // repeats a recent "add" must not bring back a show just unfollowed. The first sync only
        // merges: nothing is removed on either side.
        val before = feeds()
        // A show unfollowed here that the account knows by another address goes by that one too.
        val removedHere = if (firstSync) emptySet() else (start.syncedFeeds - before).let { gone -> gone + start.aliases.filterValues { it in gone }.keys }
        val addedHere = before - start.syncedFeeds
        // The account's list where the server keeps one, so shows added in another app (AntennaPod
        // on a per-device server) arrive too; else this device's, which on own.audio is the account's.
        val account = client.allSubscriptionChanges(start.subscriptionsSince)
        // A show only the account's list names is not yet on this device's list, on a server that
        // keeps lists per device; it is sent up below so the device holds it too.
        val accountWide = account != null
        val changes = account ?: client.subscriptionChanges(device, start.subscriptionsSince)

        val synced = start.syncedFeeds.toMutableSet()
        val aliases = start.aliases.toMutableMap()
        for (feed in changes.remove) {
            if (feed in addedHere || aliases[feed]?.let { it in addedHere } == true) continue
            synced -= feed
            val show = show(feed, aliases) ?: continue
            aliases.remove(feed)
            library.unfollow(show.id)
            report.unfollowed += 1
        }
        for (feed in changes.add) {
            if (feed in changes.remove || feed in removedHere) continue
            if (!accountWide) synced += feed
            if (show(feed, aliases) != null) continue
            // One feed that is gone must not stop the rest; it stays on the account.
            try {
                val known = library.shows().map { it.id }.toSet()
                val show = library.follow(feed)
                val local = show.feedUrl
                if (local != null && local != feed) {
                    // The device holds it under its own address: remember the account's, so the
                    // next sync neither fetches it again nor sends the device's address up as new.
                    aliases[feed] = local
                    synced += local
                }
                if (show.id !in known) report.followed += 1
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        val local = feeds()
        // An alias whose show was unfollowed here goes with it.
        aliases.values.retainAll(local)
        val add = (local - synced).sorted()
        val remove = (removedHere - local).sorted()
        var since = changes.timestamp
        if (add.isNotEmpty() || remove.isNotEmpty()) {
            val answer = try {
                client.uploadSubscriptionChanges(device, add, remove)
            } catch (_: GPodderException.Forbidden) {
                // A read-only account: what it has still comes down; nothing goes up.
                state = start.copy(subscriptionsSince = since, syncedFeeds = synced, aliases = aliases)
                return
            }
            since = answer.timestamp
            report.uploadedShows = add.size
            synced += add
            synced -= remove.toSet()
            for (pair in answer.updateUrls) {
                if (pair.size == 2 && synced.remove(pair[0]) && pair[1].isNotEmpty()) synced += pair[1]
            }
        }
        state = start.copy(subscriptionsSince = since, syncedFeeds = synced, aliases = aliases)
    }

    // MARK: episode actions

    private class Match(val key: EpisodeKey, val durationMs: Long?)

    private suspend fun pullActions(report: Report) {
        val start = current()
        val answer = client.episodeActions(start.actionsSince)
        val sent = start.sent.toMutableMap()
        // Per feed: its episodes by enclosure URL (what the API names them by) and by guid.
        val index = mutableMapOf<String, Map<String, Match>>()
        for (action in answer.actions) {
            if (action.action != EpisodeAction.Kind.PLAY && action.action != EpisodeAction.Kind.NEW) continue
            val byEpisode = index[action.podcast] ?: run {
                val show = show(action.podcast) ?: return@run null
                val episodes = try {
                    library.episodes(show.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@run null
                }
                buildMap {
                    for (episode in episodes) {
                        episode.audioUrl?.let { put(it, Match(episode.key, episode.durationMs)) }
                        put("guid:" + episode.id, Match(episode.key, episode.durationMs))
                    }
                }.also { index[action.podcast] = it }
            } ?: continue
            val match = byEpisode[action.episode] ?: action.guid?.let { byEpisode["guid:$it"] } ?: continue
            val at = action.timestamp ?: Instant.ofEpochSecond(answer.timestamp)
            // The account hands back this device's own actions too. They are skipped unless they
            // are clearly newer than what the device holds: podfetch keeps one row per episode and
            // updates it in place under the device that created it, so another app's newer position
            // can arrive wearing this device's name.
            if (action.device == device) {
                val held = library.progress(match.key)
                if (held != null && Duration.between(held.updatedAt, at) < OWN_ACTION_MARGIN) continue
            }
            val progress = if (action.action == EpisodeAction.Kind.NEW) {
                ListeningProgress(0, played = false, updatedAt = at)
            } else {
                val positionMs = (action.position ?: 0) * 1000L
                val durationMs = action.total?.takeIf { it > 0 }?.let { it * 1000L } ?: match.durationMs
                ListeningProgress(positionMs, ListeningProgress.reachedEnd(positionMs, durationMs), at)
            }
            val kept = library.saveProgress(progress, match.key)
            if (kept == progress) {
                report.positionsApplied += 1
                // Came from the account; sending it back would only echo it.
                sent[match.key.toString()] = progress.updatedAt
            }
        }
        state = current().copy(actionsSince = answer.timestamp, sent = sent)
    }

    private suspend fun pushActions(report: Report) {
        val start = current()
        val actions = mutableListOf<EpisodeAction>()
        val stamps = mutableMapOf<String, Instant>()
        val shows = library.shows().associateBy { it.id }
        for ((key, progress) in library.allProgress()) {
            val sentAt = start.sent[key.toString()]
            if (sentAt != null && !sentAt.isBefore(progress.updatedAt)) continue
            val feed = shows[key.showId]?.feedUrl ?: continue
            val episode = try {
                library.episode(key)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            val audio = episode.audioUrl ?: continue
            val total = episode.durationMs?.let { (it / 1000).toInt() }
            val position = if (progress.played) total ?: (progress.positionMs / 1000).toInt() else (progress.positionMs / 1000).toInt()
            actions += if (!progress.played && position == 0) {
                EpisodeAction(podcast = feed, episode = audio, action = EpisodeAction.Kind.NEW, timestamp = progress.updatedAt, device = device, guid = episode.id)
            } else {
                // A position with no length is still worth sending; servers accept a total of the position.
                EpisodeAction(
                    podcast = feed,
                    episode = audio,
                    action = EpisodeAction.Kind.PLAY,
                    timestamp = progress.updatedAt,
                    started = 0,
                    position = position,
                    total = total ?: position,
                    device = device,
                    guid = episode.id,
                )
            }
            stamps[key.toString()] = progress.updatedAt
        }
        if (actions.isEmpty()) return
        for (batch in actions.chunked(GPodderClient.ACTIONS_PER_UPLOAD)) {
            try {
                client.uploadEpisodeActions(batch)
            } catch (_: GPodderException.Forbidden) {
                return
            }
        }
        state = start.copy(sent = start.sent + stamps)
        report.positionsSent = actions.size
    }

    // MARK: -

    private suspend fun current(): GPodderSyncState = state ?: (store.load() ?: GPodderSyncState()).also { state = it }

    private suspend fun feeds(): Set<String> = library.shows().mapNotNull { it.feedUrl }.toSet()

    private suspend fun show(feed: String, aliases: Map<String, String>? = null): Show? {
        val known = aliases ?: current().aliases
        val shows = library.shows()
        return shows.firstOrNull { it.feedUrl == feed } ?: known[feed]?.let { local -> shows.firstOrNull { it.feedUrl == local } }
    }

    companion object {
        /** How much newer than the held position this device's own pulled action must be to count. */
        private val OWN_ACTION_MARGIN: Duration = Duration.ofSeconds(1)
    }
}
