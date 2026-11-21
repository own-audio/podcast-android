package audio.own.player.spoken.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * With no network, a failed load is retried for as long as it takes (2 s doubling to 30 s)
 * rather than failing the item: the player stays buffering, the foreground service stays up, and
 * the book goes on by itself when the signal returns ("No signal — waiting"). With a network,
 * Media3's usual few retries, then the error.
 */
@UnstableApi
class SignalWaitPolicy(private val isConnected: () -> Boolean) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (!isConnected()) return backoffMs(loadErrorInfo.errorCount)
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (!isConnected()) Int.MAX_VALUE else super.getMinimumLoadableRetryCount(dataType)

    companion object {
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L

        fun backoffMs(errorCount: Int): Long {
            val doublings = (errorCount - 1).coerceIn(0, 4)
            return (FIRST_RETRY_MS shl doublings).coerceAtMost(MAX_RETRY_MS).also { check(it != C.TIME_UNSET) }
        }
    }
}
