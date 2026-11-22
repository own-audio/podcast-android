package audio.own.podcastplayer.widget

import android.net.Uri
import androidx.core.net.toUri
import audio.own.player.core.JsonFile
import audio.own.podcast.library.ListeningProgress
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.playback.PodcastPlayer
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/**
 * What the Up Next widget shows: the app writes it to `filesDir/widget/` whenever its entries
 * change, the widget only reads. Pictures sit next to it as small JPEGs, so the widget needs
 * neither the network nor a sign-in. Ported from the iOS app's `WidgetSnapshot`.
 */
@Serializable
data class WidgetSnapshot(val entries: List<Entry>, val atEpochMs: Long = 0) {
    @Serializable
    data class Entry(
        /** The episode as the app knows it (source and key), as `ownpodcast://play/<id>` wants it. */
        val id: String,
        val title: String,
        /** The show. */
        val show: String,
        /** 0…1 of the episode listened to; 0 for one not started. */
        val fraction: Double,
        val remainingMs: Long,
        /** The picture's file name in the widget's folder, or null. */
        val cover: String? = null,
    )

    /** An entry with the address its picture comes from, before the picture is on the device. */
    data class Picked(val entry: Entry, val coverUrl: String?)

    companion object {
        const val SCHEME = "ownpodcast"
        const val MAX_ENTRIES = 3

        /** The link a tap opens; the id carries a `/`, a `|` and maybe a `#`, so it is escaped whole. */
        fun playLink(id: String): Uri = "$SCHEME://play/${Uri.encode(id)}".toUri()

        /** The id back out of such a link, or null for any other link. */
        fun episodeId(link: Uri): String? {
            if (link.scheme != SCHEME || link.host != "play") return null
            val encoded = link.toString().substringAfter("$SCHEME://play/", "")
            return Uri.decode(encoded).takeIf { it.isNotEmpty() }
        }

        /**
         * The episode in the player, then the ones under way in New Episodes, then the newest
         * new ones; three at most. Fractions to the percent and time left to the minute, so a
         * playing episode rewrites the file about once a minute, not with every tick.
         */
        fun pick(
            player: PodcastPlayer.State,
            inbox: List<SourcedEpisode>,
            progress: Map<String, ListeningProgress>,
            show: (SourcedEpisode) -> Show?,
        ): List<Picked> {
            val picked = mutableListOf<SourcedEpisode>()
            player.item?.let(picked::add)
            val started = inbox.filter { (progress[it.id]?.positionMs ?: 0) > 0 && progress[it.id]?.played != true }
            for (item in started + inbox) {
                if (picked.size >= MAX_ENTRIES) break
                if (picked.none { it.id == item.id }) picked += item
            }
            return picked.map { item ->
                val playing = item.id == player.item?.id
                val itsShow = show(item) ?: player.show.takeIf { playing }
                val duration = if (playing && player.durationMs > 0) player.durationMs else item.episode.durationMs ?: 0
                val position = if (playing) player.positionMs else progress[item.id]?.takeIf { !it.played }?.positionMs ?: 0
                val fraction = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
                Picked(
                    Entry(
                        id = item.id,
                        title = item.episode.title,
                        show = itsShow?.title.orEmpty(),
                        fraction = (fraction * 100).roundToLong() / 100.0,
                        remainingMs = ((duration - position).coerceAtLeast(0) / 60_000.0).roundToLong() * 60_000,
                        cover = (item.episode.imageUrl ?: itsShow?.imageUrl)?.let(::coverName),
                    ),
                    item.episode.imageUrl ?: itsShow?.imageUrl,
                )
            }
        }

        fun coverName(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg"
    }
}

/** The snapshot's file and its pictures, in [folder] (`filesDir/widget`). */
class WidgetStore(private val folder: File) {
    private val file = JsonFile(File(folder, "snapshot.json"), WidgetSnapshot.serializer())

    fun load(): WidgetSnapshot? = runCatching { file.read() }.getOrNull()

    fun cover(name: String): File = File(folder, name)

    /**
     * Writes [snapshot] when its entries differ from the file's, with the pictures it names that
     * aren't there yet ([jpeg] makes one) and without the ones no entry names any more. Returns
     * whether anything was written, so the widget is redrawn only then.
     */
    suspend fun save(picked: List<WidgetSnapshot.Picked>, atEpochMs: Long, jpeg: suspend (String) -> ByteArray?): Boolean {
        val entries = picked.map { pick ->
            val name = pick.entry.cover ?: return@map pick.entry
            val target = cover(name)
            if (target.exists()) return@map pick.entry
            val bytes = pick.coverUrl?.let { jpeg(it) } ?: return@map pick.entry.copy(cover = null)
            folder.mkdirs()
            val partial = File(folder, "$name.part")
            partial.writeBytes(bytes)
            partial.renameTo(target)
            pick.entry
        }
        if (load()?.entries == entries) return false
        folder.mkdirs()
        file.write(WidgetSnapshot(entries, atEpochMs))
        val kept = entries.mapNotNull { it.cover }.toSet()
        folder.listFiles { f -> f.name.endsWith(".jpg") && f.name !in kept }?.forEach { it.delete() }
        return true
    }
}
