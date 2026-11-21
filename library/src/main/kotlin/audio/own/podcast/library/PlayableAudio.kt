package audio.own.podcast.library

/**
 * Which audio the device plays itself, by content type. Media3 reads MP3, AAC/MP4, FLAC, WAV,
 * Ogg, Opus and WebM/Matroska on every Android this app runs on, so far less goes through a
 * server's transcoder than on iOS. The app can pass a stricter check built on `MediaCodecList`
 * where it knows better; this list is the default.
 */
object PlayableAudio {
    val MIME_TYPES: Set<String> = setOf(
        "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/m4b", "audio/x-m4b",
        "audio/aac", "audio/x-aac", "audio/aacp", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave",
        "audio/ogg", "audio/x-ogg", "audio/vorbis", "audio/opus", "audio/webm", "audio/x-matroska",
        "video/mp4", "video/webm", "video/x-matroska",
    )

    /** Null (unknown) counts as playable: most feeds that say nothing send MP3. */
    fun isPlayable(mimeType: String?): Boolean {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (type.isNullOrEmpty()) return true
        return type in MIME_TYPES
    }
}
