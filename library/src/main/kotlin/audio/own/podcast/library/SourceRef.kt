package audio.own.podcast.library

import java.util.UUID

/** Which source something belongs to, as the app names it in ids that cross sources. */
sealed interface SourceRef {
    val id: String

    val serverId: UUID?

    /** The device's own feeds. */
    data object Phone : SourceRef {
        override val id = "phone"
        override val serverId: UUID? = null
    }

    data class Server(val uuid: UUID) : SourceRef {
        override val id get() = uuid.toString()
        override val serverId: UUID get() = uuid
    }

    /**
     * One library of a server (Audiobookshelf), for the switch and for adding shows; items
     * themselves always belong to [Server].
     */
    data class Library(val uuid: UUID, val libraryId: String) : SourceRef {
        override val id get() = "$uuid#$libraryId"
        override val serverId: UUID get() = uuid
    }

    companion object {
        fun parse(id: String): SourceRef? {
            if (id == Phone.id) return Phone
            val hash = id.indexOf('#')
            if (hash >= 0) return uuid(id.substring(0, hash))?.let { Library(it, id.substring(hash + 1)) }
            return uuid(id)?.let(::Server)
        }

        private fun uuid(text: String): UUID? = try {
            UUID.fromString(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** An episode with its source. */
data class SourcedEpisode(val source: SourceRef, val episode: Episode) {
    /** `<source>/<showId>|<episodeId>`: unique across every source, and the player's id for it. */
    val id: String get() = id(source, episode.key)

    companion object {
        fun id(source: SourceRef, key: EpisodeKey) = "${source.id}/$key"

        /** Source ids hold no `/`, so the first one splits; the episode id may hold more. */
        fun parse(id: String): Pair<SourceRef, EpisodeKey>? {
            val slash = id.indexOf('/')
            if (slash < 0) return null
            val source = SourceRef.parse(id.substring(0, slash)) ?: return null
            val key = EpisodeKey.parse(id.substring(slash + 1)) ?: return null
            return source to key
        }
    }
}

/** A show with its source; `<source>/<showId>`. */
data class SourcedShow(val source: SourceRef, val show: Show) {
    val id: String get() = "${source.id}/${show.id}"
}
