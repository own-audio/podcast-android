package audio.own.player.spoken

import kotlin.math.roundToInt

/** The speeds a book plays at: 0.5–3.0 in steps of 0.05, as on iOS. */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 3.0f
    const val DEFAULT = 1.0f

    // Rounded through hundredths so 1.35 stays 1.35f instead of drifting to 1.3499999f.
    fun normalized(speed: Float): Float {
        if (speed.isNaN()) return DEFAULT
        val steps = (speed * 20).roundToInt()
        return (steps * 5 / 100f).coerceIn(MIN, MAX)
    }
}
