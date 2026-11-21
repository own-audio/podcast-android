package audio.own.player.spoken

/** The sleep timer's two modes that end at a place in the book rather than after a time. */
enum class SleepEnd {
    /** Pause where the current chapter ends; with no chapters, where the current part ends. */
    END_OF_CHAPTER,

    /** Pause when the book is finished, not at the end of each file. */
    END_OF_BOOK,
}

/** What the player should do after asking the policy. */
enum class SleepDecision { CONTINUE, PAUSE }

/**
 * The sleep timer's End of Chapter and End of Book decisions, without a clock or a player.
 *
 * The countdown presets live in `:player:core`'s `SleepTimer`, shared with the music app. The
 * modes are exclusive: the player cancels that countdown when it arms this policy, and calls
 * [disarm] when a countdown starts.
 *
 * Both modes compare positions with where a chapter or the book ends. Neither is ever turned into
 * a countdown of "end − position": that is wrong as soon as the speed changes or the listener
 * scrubs.
 */
class SpokenSleepPolicy {
    var armed: SleepEnd? = null
        private set

    val isArmed: Boolean get() = armed != null

    fun arm(end: SleepEnd) {
        armed = end
    }

    fun disarm() {
        armed = null
    }

    /**
     * A playing tick. [fromMs] is the book position at the previous tick (or the seek target
     * after a seek), [toMs] the position now. The chapter is taken at [fromMs], so a tick that
     * already crossed into the next chapter still stops for the one being listened to.
     */
    fun onTick(timeline: BookTimeline, fromMs: Long, toMs: Long): SleepDecision {
        if (armed != SleepEnd.END_OF_CHAPTER) return SleepDecision.CONTINUE
        // A little early: ticks are coarse, and running into the next chapter's first words is
        // worse than missing the last quarter second of the one before.
        val end = timeline.endOfChapter(fromMs)
        if (toMs < end - END_OF_CHAPTER_LEAD_MS) return SleepDecision.CONTINUE
        armed = null
        return SleepDecision.PAUSE
    }

    /**
     * A part played to its end. Mid-book that is not the end of the book, so End of Book stays
     * armed and playback goes on; after the last part, any armed mode is used up.
     */
    fun onPartEnded(timeline: BookTimeline, partIndex: Int): SleepDecision {
        if (partIndex < timeline.parts.lastIndex) return SleepDecision.CONTINUE
        if (armed == null) return SleepDecision.CONTINUE
        armed = null
        return SleepDecision.PAUSE
    }

    companion object {
        const val END_OF_CHAPTER_LEAD_MS = 250L
    }
}
