package audio.own.player.spoken.chapters

/**
 * A chapter mark as one file carries it, in that file's milliseconds. [endMs] and [title] are
 * missing when the format doesn't say; [EmbeddedChapters] fills them in.
 */
data class FileChapter(val startMs: Long, val endMs: Long? = null, val title: String? = null)

/** Upper bounds against corrupt or hostile files: no real book has more chapters than this. */
internal const val MAX_CHAPTERS = 10_000
