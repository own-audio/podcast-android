package audio.own.podcast.library

import java.time.Instant

/**
 * What a show's download rules ask for: which episodes to download and which to let go. Pure, so
 * the app runs it whenever a show's episodes, its settings or a played state change.
 */
object DownloadRules {
    data class Item(
        val id: String,
        val publishedAt: Instant?,
        val isPlayed: Boolean,
        val isDownloaded: Boolean,
    )

    data class Plan(
        val download: List<String> = emptyList(),
        val remove: List<String> = emptyList(),
    )

    /**
     * [items] are one show's episodes; [protected] (the episode playing) is never removed. Only the
     * newest `autoDownload` episodes are ever downloaded by a rule — never the back catalogue — and
     * only unplayed ones; a manual download is kept unless a rule removes it.
     */
    fun plan(items: List<Item>, settings: ShowSettings, protected: String?): Plan {
        val newestFirst = items.sortedByDescending { it.publishedAt ?: Instant.MIN }
        val download = mutableListOf<String>()
        val remove = mutableListOf<String>()
        val removed = mutableSetOf<String>()
        if (settings.deleteWhenPlayed) {
            for (item in newestFirst) {
                if (item.isDownloaded && item.isPlayed && item.id != protected) {
                    remove += item.id
                    removed += item.id
                }
            }
        }
        var kept = newestFirst.filter { it.isDownloaded && it.id !in removed }.map { it.id }
        if (settings.autoDownload > 0) {
            for (item in newestFirst.take(settings.autoDownload)) {
                if (!item.isDownloaded && !item.isPlayed) download += item.id
            }
        }
        settings.keepAtMost?.let { limit ->
            // The new downloads count against the limit, so the two rules don't fight; the newest stay.
            val order = newestFirst.withIndex().associate { it.value.id to it.index }
            kept = (kept + download).sortedBy { order.getValue(it) }
            for (id in kept.drop(limit).reversed()) {
                if (id == protected) continue
                if (!download.remove(id)) remove += id
            }
        }
        return Plan(download, remove)
    }
}
