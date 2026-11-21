package audio.own.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The background refresh: no server pushes anything, so at most every hour, on a connected
 * network and not on a low battery, the app syncs its catalogues, flushes its outboxes and
 * refreshes its widget. What that means is the app's: [work] answers whether it all went.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val work: suspend () -> Boolean,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (work()) Result.success() else Result.retry()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}

object PeriodicRefresh {
    const val NAME = "periodic-refresh"

    /** At least an hour apart; scheduling again keeps the one already planned. */
    fun schedule(workManager: WorkManager, everyHours: Long = 1) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(everyHours.coerceAtLeast(1), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(workManager: WorkManager) {
        workManager.cancelUniqueWork(NAME)
    }
}

/** Builds [RefreshWorker]s with the app's work; add it to the app's `DelegatingWorkerFactory`. */
class RefreshWorkerFactory(private val work: suspend () -> Boolean) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == RefreshWorker::class.java.name) RefreshWorker(appContext, workerParameters, work) else null
}
