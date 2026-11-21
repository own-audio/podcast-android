package audio.own.podcast.library

import java.time.Instant

/**
 * Which downloads to let go when they outgrow the space the listener allowed: played episodes
 * first, then the ones not played for the longest, never the one playing now.
 */
class EvictionPolicy(val capBytes: Long?) {
    data class Candidate(
        val id: String,
        val bytes: Long,
        val isFinished: Boolean,
        /** The last time it was played, or downloaded when it never was. */
        val lastUsedAt: Instant,
    )

    /**
     * The ids to remove, in order, so that what is left fits under the cap; [protected] is never
     * among them. Nothing with no cap.
     */
    fun evictions(candidates: List<Candidate>, protected: String?): List<String> {
        val cap = capBytes ?: return emptyList()
        var total = candidates.sumOf { it.bytes }
        if (total <= cap) return emptyList()
        val order = candidates
            .filter { it.id != protected }
            .sortedWith(compareByDescending<Candidate> { it.isFinished }.thenBy { it.lastUsedAt })
        val out = mutableListOf<String>()
        for (candidate in order) {
            if (total <= cap) break
            out += candidate.id
            total -= candidate.bytes
        }
        return out
    }
}
