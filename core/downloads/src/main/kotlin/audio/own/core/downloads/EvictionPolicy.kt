package audio.own.core.downloads

/**
 * Which downloads to let go when they outgrow the space the listener allowed (Keep at Most):
 * finished items first (a played episode, a finished book), then the ones not played for the
 * longest, never the one playing now. A song is never "finished", so for music only the order
 * by last use counts. Port of the iOS apps' `EvictionPolicy`.
 */
class EvictionPolicy(val capBytes: Long?) {
    data class Candidate(
        val id: String,
        val bytes: Long,
        val isFinished: Boolean,
        /** The last time it was played, or downloaded when it never was (ms). */
        val lastUsedAt: Long,
    )

    /** The ids to remove, in order, so what is left fits under the cap; never [protected]. */
    fun evictions(candidates: List<Candidate>, protected: String?): List<String> {
        val cap = capBytes ?: return emptyList()
        var total = candidates.sumOf { it.bytes }
        if (total <= cap) return emptyList()
        val order = candidates
            .filter { it.id != protected }
            .sortedWith(compareByDescending<Candidate> { it.isFinished }.thenBy { it.lastUsedAt })
        val out = ArrayList<String>()
        for (candidate in order) {
            if (total <= cap) break
            out += candidate.id
            total -= candidate.bytes
        }
        return out
    }
}
