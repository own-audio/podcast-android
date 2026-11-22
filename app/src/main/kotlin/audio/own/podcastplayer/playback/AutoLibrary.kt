package audio.own.podcastplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.kit.feed.Mojibake
import audio.own.player.spoken.SpokenChapter
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcast.library.SourcedShow
import audio.own.podcastplayer.downloads.EpisodeDownload
import audio.own.podcastplayer.podcasts.PodcastState
import audio.own.podcastplayer.podcasts.ProgressText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * What the car's screen shows, apart from the media service so tests can check it without a car:
 * the root's lists, which episodes each holds and in what order, a row's progress, and the speed
 * the speed button goes to next. Ported from the iOS app's `CarPlayContent`.
 */
object AutoContent {
    /** The car's guidelines ask for short lists. */
    const val MAX_ITEMS = 100

    const val ROOT = "@root"
    const val NEW_EPISODES = "@new"
    const val DOWNLOADS = "@downloads"
    const val SHOWS = "@shows"
    const val CHAPTERS = "@chapters"
    const val SHOW_PREFIX = "@show/"
    const val CHAPTER_PREFIX = "@chapter/"
    const val MESSAGE_PREFIX = "@message/"

    enum class RootEntry(val id: String) { NEW_EPISODES(AutoContent.NEW_EPISODES), DOWNLOADS(AutoContent.DOWNLOADS), SHOWS(AutoContent.SHOWS) }

    /**
     * New Episodes first and the downloads next, because a car often has no signal. With nothing
     * followed, no lists at all: the root says to follow a show on the phone.
     */
    fun rootEntries(hasShows: Boolean): List<RootEntry> = if (hasShows) RootEntry.entries else emptyList()

    /** Offline, only what is on the phone; never more than the cap. */
    fun newEpisodes(inbox: List<SourcedEpisode>, canPlay: (SourcedEpisode) -> Boolean): List<SourcedEpisode> =
        inbox.filter(canPlay).take(MAX_ITEMS)

    /** Finished downloads, the newest first, from the download index alone so it works with no signal. */
    fun downloads(entries: Collection<EpisodeDownload>): List<SourcedEpisode> =
        entries.filter { it.isDone }.sortedByDescending { it.finishedAt ?: it.queuedAt }.mapNotNull { it.record.item }.take(MAX_ITEMS)

    /** One show's episodes, the newest first, only the playable ones. */
    fun showEpisodes(episodes: List<SourcedEpisode>, canPlay: (SourcedEpisode) -> Boolean): List<SourcedEpisode> =
        episodes.sortedByDescending { it.episode.publishedAt ?: it.episode.firstSeenAt }.filter(canPlay).take(MAX_ITEMS)

    /** How far into an episode, 0…1, for the row's bar; none for a played or not started one. */
    fun progress(progress: ListeningProgress?, durationMs: Long?): Double? {
        if (progress == null || durationMs == null || durationMs <= 0 || progress.played || progress.positionMs <= 0) return null
        return minOf(progress.positionMs.toDouble() / durationMs, 1.0)
    }

    /** 1× → 1.25× → … → 2× → 0.75× → 1×, the steps of the phone's speed settings. */
    val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    fun nextRate(current: Float): Float = RATES.firstOrNull { it > current + 0.01f } ?: RATES.first()
}

/**
 * The browse tree Android Auto (and any media browser) reads: the root's New Episodes, Downloads
 * and Shows, a show's episodes, and the chapters of the episode playing. Rows carry their artwork
 * and how far the listener is; an empty list or a failure is one row that says so.
 */
@UnstableApi
class AutoLibrary(
    private val library: Library,
    private val words: Words,
    /** A picture's address as the car may read it (a content URI), or null. */
    private val artwork: (String?) -> Uri? = { it?.let(Uri::parse) },
) {
    interface Library {
        val state: PodcastState
        val player: PodcastPlayer.State

        fun canPlay(item: SourcedEpisode): Boolean

        suspend fun episodes(show: SourcedShow): List<SourcedEpisode>
    }

    interface Words {
        fun title(entry: AutoContent.RootEntry): String
        val chapters: String
        val followOnPhone: String
        val noNewEpisodes: String
        val nothingDownloaded: String
        val nothingHere: String
        val noShows: String
        val noChapters: String

        fun chapter(number: Int): String

        fun failure(error: Throwable): String
    }

    /** Episodes the tree has listed, so a tap on one plays it without asking its source again. */
    private val listed = ConcurrentHashMap<String, SourcedEpisode>()

    fun known(id: String): SourcedEpisode? = listed[id]
        ?: library.state.inbox.firstOrNull { it.id == id }
        ?: library.state.downloads.entries[id]?.record?.item

    fun root(): MediaItem = folder(AutoContent.ROOT, "", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)

    suspend fun children(parentId: String): List<MediaItem> {
        val state = library.state
        return when {
            parentId == AutoContent.ROOT -> {
                val entries = AutoContent.rootEntries(state.shows.isNotEmpty())
                if (entries.isEmpty()) return listOf(message(words.followOnPhone))
                entries.map(::entry) + listOfNotNull(chaptersFolder())
            }
            parentId == AutoContent.NEW_EPISODES -> episodes(AutoContent.newEpisodes(state.inbox, library::canPlay), words.noNewEpisodes)
            parentId == AutoContent.DOWNLOADS -> episodes(AutoContent.downloads(state.downloads.entries.values), words.nothingDownloaded)
            parentId == AutoContent.SHOWS -> state.shows.take(AutoContent.MAX_ITEMS).map(::show).ifEmpty { listOf(message(words.noShows)) }
            parentId == AutoContent.CHAPTERS -> chapters().ifEmpty { listOf(message(words.noChapters)) }
            parentId.startsWith(AutoContent.SHOW_PREFIX) -> {
                val show = state.shows.firstOrNull { it.id == parentId.removePrefix(AutoContent.SHOW_PREFIX) } ?: return listOf(message(words.nothingHere))
                val all = try {
                    library.episodes(show)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return listOf(message(words.failure(error)))
                }
                episodes(AutoContent.showEpisodes(all, library::canPlay), words.nothingHere)
            }
            else -> emptyList()
        }
    }

    fun item(id: String): MediaItem? {
        val state = library.state
        return when {
            id == AutoContent.ROOT -> root()
            id == AutoContent.CHAPTERS -> chaptersFolder()
            id.startsWith(AutoContent.SHOW_PREFIX) -> state.shows.firstOrNull { it.id == id.removePrefix(AutoContent.SHOW_PREFIX) }?.let(::show)
            id.startsWith("@") -> AutoContent.RootEntry.entries.firstOrNull { it.id == id }?.let(::entry)
            else -> known(id)?.let(::episode)
        }
    }

    /** What a search in the car finds: the followed shows that match, then matching new episodes and downloads. */
    fun search(query: String): List<MediaItem> {
        val state = library.state
        val shows = VoiceSearch.rankedShows(query, state.shows).map(::show)
        val pool = (state.inbox + AutoContent.downloads(state.downloads.entries.values)).distinctBy { it.id }.filter(library::canPlay)
        val episodes = VoiceSearch.rankedEpisodes(query, pool, state).map(::episode)
        return (shows + episodes).take(AutoContent.MAX_ITEMS)
    }

    /** The chapter at [index] of the episode playing, for a tap on its row. */
    fun chapter(index: Int): SpokenChapter? = library.player.chapters.getOrNull(index)

    private fun episodes(items: List<SourcedEpisode>, empty: String): List<MediaItem> {
        items.forEach { listed[it.id] = it }
        return items.map(::episode).ifEmpty { listOf(message(empty)) }
    }

    private fun entry(entry: AutoContent.RootEntry): MediaItem = when (entry) {
        AutoContent.RootEntry.SHOWS -> folder(entry.id, words.title(entry), browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        else -> folder(entry.id, words.title(entry))
    }

    private fun chaptersFolder(): MediaItem? = if (library.player.item != null && library.player.chapters.size > 1) folder(AutoContent.CHAPTERS, words.chapters) else null

    private fun chapters(): List<MediaItem> {
        val player = library.player
        val current = player.currentChapter
        return player.chapters.take(AutoContent.MAX_ITEMS).mapIndexed { index, chapter ->
            MediaItem.Builder()
                .setMediaId("${AutoContent.CHAPTER_PREFIX}$index")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title.ifBlank { words.chapter(index + 1) })
                        .setSubtitle(ProgressText.clock(chapter.startMs))
                        .setArtist(if (chapter == current) player.item?.episode?.title else null)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
    }

    private fun folder(id: String, title: String, browsableStyle: Int? = null): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            browsableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun show(show: SourcedShow): MediaItem = MediaItem.Builder()
        .setMediaId("${AutoContent.SHOW_PREFIX}${show.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(Mojibake.repair(show.show.title))
                .setArtist(show.show.author)
                .setArtworkUri(artwork(show.show.imageUrl))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) })
                .build(),
        )
        .build()

    fun episode(item: SourcedEpisode): MediaItem {
        val state = library.state
        val show = state.show(item)
        val progress = state.progress[item.id]
        val extras = Bundle()
        val fraction = AutoContent.progress(progress, item.episode.durationMs)
        when {
            progress?.played == true -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            fraction != null -> {
                extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, fraction)
            }
            else -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
        }
        val showTitle = (show?.title ?: state.downloads.entries[item.id]?.record?.show?.title)?.let(Mojibake::repair)
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Mojibake.repair(item.episode.title))
                    .setArtist(showTitle)
                    .setSubtitle(showTitle)
                    .setArtworkUri(artwork(item.episode.imageUrl ?: show?.imageUrl))
                    .setDurationMs(item.episode.durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun message(text: String): MediaItem = MediaItem.Builder()
        .setMediaId("${AutoContent.MESSAGE_PREFIX}${text.hashCode()}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(text).setIsBrowsable(false).setIsPlayable(false).build())
        .build()
}
