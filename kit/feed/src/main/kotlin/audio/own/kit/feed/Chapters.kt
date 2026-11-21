package audio.own.kit.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * Podcasting 2.0 chapter documents (`application/json+chapters`) and the closing of chapter lists
 * that give only start times.
 */
object Chapters {
    @Serializable
    private data class Document(val chapters: List<Chapter>)

    @Serializable
    private data class Chapter(
        val startTime: Double,
        val endTime: Double? = null,
        val title: String? = null,
        val img: String? = null,
        val url: String? = null,
        val toc: Boolean? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a chapters document. Chapters with `toc: false` are kept but marked, since they change
     * only the image or link; the list comes back sorted and closed.
     */
    fun parse(bytes: ByteArray): List<FeedChapter> {
        val document = try {
            json.decodeFromString<Document>(bytes.decodeToString())
        } catch (error: SerializationException) {
            throw FeedException.Malformed(error.message ?: "unreadable chapters")
        } catch (error: IllegalArgumentException) {
            throw FeedException.Malformed(error.message ?: "unreadable chapters")
        }
        return closed(
            document.chapters.map { chapter ->
                FeedChapter(
                    startMs = (chapter.startTime * 1000).roundToLong(),
                    endMs = chapter.endTime?.let { (it * 1000).roundToLong() },
                    title = Mojibake.repair(chapter.title ?: ""),
                    url = FeedValues.url(chapter.url),
                    imageUrl = FeedValues.url(chapter.img),
                    isInTableOfContents = chapter.toc ?: true,
                )
            },
        )
    }

    /**
     * Sorted by start, each ending where the next begins unless it says otherwise; the last stays
     * open (the episode's length closes it, which the feed may not know).
     */
    fun closed(chapters: List<FeedChapter>): List<FeedChapter> {
        val sorted = chapters.sortedBy { it.startMs }
        return sorted.mapIndexed { index, chapter ->
            if (chapter.endMs == null && index + 1 < sorted.size) chapter.copy(endMs = sorted[index + 1].startMs) else chapter
        }
    }
}
