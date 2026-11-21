package audio.own.player.spoken

/**
 * How far to go back when playback resumes after a pause: nothing after a short one, up to half
 * a minute after hours, because the listener has lost the thread by then. The steps are the ones
 * most audiobook players settled on.
 */
data class SmartRewind(val isEnabled: Boolean = true) {
    /** Milliseconds to rewind after a pause of [pausedMs]. */
    fun rewindMs(pausedMs: Long): Long {
        if (!isEnabled) return 0
        return when {
            pausedMs < 10_000 -> 0
            pausedMs < 60_000 -> 3_000
            pausedMs < 600_000 -> 10_000
            pausedMs < 3_600_000 -> 20_000
            else -> 30_000
        }
    }
}
