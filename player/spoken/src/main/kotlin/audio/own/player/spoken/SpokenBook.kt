package audio.own.player.spoken

/**
 * A book as the player takes it: its parts end to end, and the chapters the server knows (empty:
 * the player reads them from the files, else uses one per part).
 */
data class SpokenBook(
    /** The app's composite id; reports and cache keys carry it. */
    val id: String,
    val title: String,
    val author: String? = null,
    /** For the notification and the lock screen. */
    val artworkUri: String? = null,
    val parts: List<SpokenPart>,
    val serverChapters: List<SpokenChapter> = emptyList(),
    /** Titles of the parts for the one-chapter-per-part fallback; null entries get "Part N". */
    val partTitles: List<String?>? = null,
)

/** Where the chapters a player shows came from. */
enum class ChapterOrigin { SERVER, FILES, PARTS }

/** Why a position was reported. */
enum class ReportReason { PERIODIC, PAUSE, SEEK, CHAPTER, STOP, FINISHED, TEARDOWN }

/** A position worth saving, for the app to write to disk first and then send. */
data class SpokenPosition(
    val bookId: String,
    val positionMs: Long,
    val durationMs: Long,
    val partId: String,
    val partOffsetMs: Long,
    /** Wall-clock milliseconds listened since the last report; waiting for a signal doesn't count. */
    val listenedMs: Long,
    val finished: Boolean,
    val atMs: Long,
    val reason: ReportReason,
)
