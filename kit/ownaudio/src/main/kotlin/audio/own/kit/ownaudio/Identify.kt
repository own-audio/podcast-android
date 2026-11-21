@file:UseSerializers(Rfc3339::class)

package audio.own.kit.ownaudio

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.serializer

// Identify runs (`modules.identify_library`): the server matches part of the library against
// MusicBrainz in the background and proposes a title, album and MusicBrainz ids for each song;
// the owner accepts or rejects them. Only the server's database changes, never a file.

/** What a run covers: the whole library, or one folder, album, playlist, artist or list of songs. */
@Serializable
data class IdentifyScope(
    /** `library`, `folder`, `album`, `playlist`, `artist` or `tracks` (with [trackIds]). */
    val kind: String,
    val id: String? = null,
    val trackIds: List<String>? = null,
    /** How the run is named in lists. */
    val label: String? = null,
) {
    companion object {
        fun library(label: String? = null) = IdentifyScope("library", label = label)
    }
}

@Serializable
data class IdentifyRun(
    val id: String,
    /** `queued`, `running`, `waiting` (the source asked to slow down), `ready`, `failed`, `cancelled`. */
    val status: String,
    val error: String? = null,
    val scope: IdentifyScope,
    val tracks: Int = 0,
    val groupsTotal: Int = 0,
    val groupsDone: Int = 0,
    val counts: Counts = Counts(),
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
) {
    @Serializable
    data class Counts(
        val sure: Long = 0,
        val review: Long = 0,
        val none: Long = 0,
        val pending: Long = 0,
        val accepted: Long = 0,
        val rejected: Long = 0,
    )

    /** Still looking songs up. */
    val isActive: Boolean get() = status in setOf("queued", "running", "waiting")
}

/** What a run over a scope would look up, and the most it could cost (0 where it is free). */
@Serializable
data class IdentifyQuote(
    val tracks: Int,
    /** Millionths of the server's currency. */
    val chargeMicro: Long,
)

@Serializable
data class IdentifyProposal(
    val id: String,
    val group: Group,
    val track: Song,
    /** `sure`, `review` or `none`. */
    @SerialName("class") val proposalClass: String,
    /** Why it is not sure: `incomplete`, `partial`, `layout`, `duplicate`, `ambiguous`, … */
    val reasons: List<String> = emptyList(),
    val proposal: Match? = null,
    /** Keep the file's own album and set only the recording (a compilation's song). */
    val keepAlbum: Boolean = false,
    /** `pending`, `accepted` or `rejected`. */
    val decision: String,
    /** The owner picked this recording. */
    val chosenByOwner: Boolean = false,
    /** How many other recordings the server keeps for it. */
    val alternatives: Int = 0,
) {
    @Serializable
    data class Group(val id: String, val kind: String, val label: String)

    /** The song as the library has it now. */
    @Serializable
    data class Song(
        val id: String,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val discNumber: Int? = null,
        val trackNumber: Int? = null,
        val durationSecs: Int? = null,
    )

    @Serializable
    data class Match(
        val mbRecordingId: String,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val mbArtistId: String? = null,
        val mbReleaseId: String? = null,
        val mbReleaseGroupId: String? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val year: Int? = null,
        val lengthMs: Long? = null,
        val score: Int = 0,
    )
}

/** A MusicBrainz recording a search found for a song, best first. */
@Serializable
data class RecordingCandidate(
    val mbRecordingId: String,
    val title: String,
    val artist: String? = null,
    val mbArtistId: String? = null,
    val album: String? = null,
    val mbReleaseId: String? = null,
    val durationMs: Long? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val coverArtUrl: String? = null,
    /** 0–100, relative to the other results. */
    val score: Int = 0,
) {
    /** The same recording can come back on several releases. */
    val key: String get() = mbRecordingId + "/" + (mbReleaseId ?: "")
}

/** What to search MusicBrainz for, in the owner's words. */
@Serializable
data class RecordingSearch(val title: String? = null, val artist: String? = null, val album: String? = null, val limit: Int? = null)

/** An album's editions: every release the source offered, best first. */
@Serializable
data class IdentifyGroup(
    val id: String,
    val kind: String,
    val label: String,
    /** The edition the proposals are on; none when the songs matched different releases. */
    val chosen: Int? = null,
    val editions: List<Edition> = emptyList(),
) {
    @Serializable
    data class Edition(
        val index: Int,
        val mbReleaseId: String,
        val mbReleaseGroupId: String? = null,
        val title: String,
        val artist: String? = null,
        val year: Int? = null,
        val primaryType: String? = null,
        val secondaryTypes: List<String> = emptyList(),
        val status: String? = null,
        val trackCount: Int = 0,
        val matched: Int = 0,
        val coverArtUrl: String? = null,
    )
}

/** The data of an `identify` event: a run moved on. */
@Serializable
data class IdentifyProgress(val runId: String, val status: String, val groupsDone: Int = 0, val groupsTotal: Int = 0) {
    companion object {
        fun of(event: LiveEvent): IdentifyProgress? {
            if (event.type != "identify") return null
            return runCatching { OwnAudioJson.decodeFromString(serializer(), event.data) }.getOrNull()
        }
    }
}

/** A folder on the server's disk that it reads music, books or podcasts from. */
@Serializable
data class LibraryFolder(
    val id: String,
    val path: String,
    /** `music`, `audiobooks` or `podcasts`. */
    val media: String,
    val visibility: String = "private",
    val filesMissing: Long = 0,
    val lastScan: Scan? = null,
) {
    @Serializable
    data class Scan(
        val startedAt: Instant,
        val finishedAt: Instant? = null,
        val filesSeen: Int = 0,
        val filesAdded: Int = 0,
        val error: String? = null,
    )
}

@Serializable
data class LibraryFolders(val folders: List<LibraryFolder> = emptyList(), val scanning: Boolean = false, val filesScanned: Long = 0)

@Serializable
internal data class ScopeBody(val scope: IdentifyScope)

@Serializable
internal data class AcceptAllBody(@SerialName("class") val proposalClass: String)

@Serializable
internal data class Accepted(val accepted: Int)

@Serializable
internal data class Reverted(val reverted: Int)

@Serializable
internal data class KeepAlbumBody(val keepAlbum: Boolean)

@Serializable
internal data class EditionBody(val index: Int)

@Serializable
internal data class AlbumSearchBody(val album: String? = null, val artist: String? = null)

@Serializable
internal data class RecordingChoice(val recording: String, val release: String? = null, val accept: Boolean? = null)

@Serializable
internal data class Candidates(val items: List<RecordingCandidate> = emptyList())

suspend fun OwnAudioClient.identifyQuote(scope: IdentifyScope): IdentifyQuote = send("POST", "identify/quote", ScopeBody(scope))

/** Starts a run. `payment_required` (402) when a server that charges for it finds the credit short. */
suspend fun OwnAudioClient.startIdentify(scope: IdentifyScope): IdentifyRun = send("POST", "identify/runs", ScopeBody(scope))

/** The account's runs, newest first. */
suspend fun OwnAudioClient.identifyRuns(): List<IdentifyRun> = getJson<Page<IdentifyRun>>("identify/runs").items

suspend fun OwnAudioClient.identifyRun(id: String): IdentifyRun = getJson("identify/runs/$id")

/** Stops a run and forgets its proposals; what was accepted stays. */
suspend fun OwnAudioClient.deleteIdentifyRun(id: String) = sendOnly("DELETE", "identify/runs/$id")

/** Undoes every accept of the run; how many songs went back. */
suspend fun OwnAudioClient.revertIdentifyRun(id: String): Int =
    call("POST", "identify/runs/$id/revert", Reverted.serializer(), null, Unit.serializer()).reverted

/** Accepts every sure match of the run; how many. */
suspend fun OwnAudioClient.acceptSureMatches(runId: String): Int =
    send<Accepted, AcceptAllBody>("POST", "identify/runs/$runId/accept", AcceptAllBody("sure")).accepted

suspend fun OwnAudioClient.identifyProposals(runId: String, proposalClass: String? = null, decision: String? = null, cursor: String? = null, limit: Int? = 200): Page<IdentifyProposal> =
    getJson("identify/runs/$runId/proposals", Query("class" to proposalClass, "decision" to decision, "cursor" to cursor).add("limit", limit))

suspend fun OwnAudioClient.acceptProposal(id: String): IdentifyProposal =
    call("POST", "identify/proposals/$id/accept", IdentifyProposal.serializer(), null, Unit.serializer())

suspend fun OwnAudioClient.rejectProposal(id: String): IdentifyProposal =
    call("POST", "identify/proposals/$id/reject", IdentifyProposal.serializer(), null, Unit.serializer())

suspend fun OwnAudioClient.setKeepAlbum(id: String, keep: Boolean): IdentifyProposal = send("PATCH", "identify/proposals/$id", KeepAlbumBody(keep))

suspend fun OwnAudioClient.identifyGroup(id: String): IdentifyGroup = getJson("identify/groups/$id")

/** Puts the album's proposals on another of its editions; no new lookup. */
suspend fun OwnAudioClient.chooseEdition(groupId: String, index: Int): IdentifyGroup = send("PUT", "identify/groups/$groupId/edition", EditionBody(index))

/** Searches an album's editions again with the owner's words and puts its pending proposals on the best one. */
suspend fun OwnAudioClient.searchGroup(id: String, album: String?, artist: String?): IdentifyGroup =
    send("POST", "identify/groups/$id/search", AlbumSearchBody(album, artist))

/** The server's library folders (`modules.library_folders`), which a run can be limited to. */
suspend fun OwnAudioClient.libraryFolders(): LibraryFolders = getJson("library/folders")

/** The music folders only. */
suspend fun OwnAudioClient.musicFolders(): List<LibraryFolder> = libraryFolders().folders.filter { it.media == "music" }

/** The other recordings the server keeps for a proposal. */
suspend fun OwnAudioClient.proposalAlternatives(id: String): List<RecordingCandidate> =
    getJson<Candidates>("identify/proposals/$id/alternatives").items

/** Searches again for a proposal's song in the owner's words; the results become its alternatives. */
suspend fun OwnAudioClient.searchProposal(id: String, search: RecordingSearch): List<RecordingCandidate> =
    send<Candidates, RecordingSearch>("POST", "identify/proposals/$id/search", search).items

/**
 * Makes a recording the proposal (one of its alternatives, or any recording by id or
 * musicbrainz.org link) and accepts it when [accept].
 */
suspend fun OwnAudioClient.chooseRecording(proposalId: String, recording: String, release: String? = null, accept: Boolean): IdentifyProposal =
    send("PUT", "identify/proposals/$proposalId/recording", RecordingChoice(recording, release, accept))

/** Searches MusicBrainz for any of the account's songs, in the owner's words. */
suspend fun OwnAudioClient.searchRecordings(trackId: String, search: RecordingSearch): List<RecordingCandidate> =
    send<Candidates, RecordingSearch>("POST", "tracks/$trackId/identity/search", search).items

/** Identifies one song as a recording, by id or musicbrainz.org link. [undoIdentify] puts it back. */
suspend fun OwnAudioClient.identify(trackId: String, recording: String, release: String? = null): Track =
    send("PUT", "tracks/$trackId/identity", RecordingChoice(recording, release))

/** Undoes one song's identify: it goes back to what its file said. */
suspend fun OwnAudioClient.undoIdentify(trackId: String): Track =
    call("DELETE", "tracks/$trackId/identity", Track.serializer(), null, Unit.serializer())

/**
 * Cover Art Archive's front covers for a release, best first: the release's own, then its
 * release group's. For showing what a match is; the server fetches the one it keeps.
 */
object CoverArtArchive {
    fun fronts(release: String?, group: String?, size: Int = 250): List<String> =
        listOfNotNull(release?.let { "release/$it" }, group?.let { "release-group/$it" })
            .map { "https://coverartarchive.org/$it/front-$size" }
}
