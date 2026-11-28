package audio.own.core.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One file, as a foreground worker with a progress notification. */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloads: Downloads,
    private val notifications: DownloadNotifications,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : CoroutineWorker(context, params) {
    private var title: String? = null

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY) ?: return Result.failure()
        val throttle = ForegroundThrottle(clock) { title, fraction -> foreground(title, fraction) }
        // At once, before the database or the network: a late foreground notification is what
        // the user sees as a download that didn't start.
        throttle.update(null, null)
        val outcome = downloads.run(
            key = key,
            attempt = runAttemptCount,
            onStart = { item ->
                title = item.title
                throttle.update(title, null)
            },
            onProgress = { fraction ->
                setProgress(workDataOf(PROGRESS to (fraction ?: -1f)))
                throttle.update(title, fraction)
            },
        )
        return when (outcome) {
            Downloads.Outcome.DONE -> Result.success()
            Downloads.Outcome.RETRY -> Result.retry()
            Downloads.Outcome.FAILED -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(applicationContext, id.hashCode(), title, null)

    /**
     * A foreground service can't be started from the background on Android 12+ in some states;
     * the download still runs then, as ordinary work.
     */
    private suspend fun foreground(title: String?, fraction: Float?) {
        try {
            setForeground(notifications.foregroundInfo(applicationContext, id.hashCode(), title, fraction))
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val KEY = "key"
        const val PROGRESS = "progress"
        const val TAG = "download"
        fun workName(key: String) = "download:$key"
    }
}

/**
 * Decides which progress updates become a notification. Android sheds a package's notifications
 * past five a second, and with progress twice a second next to the media session's own updates
 * the download's foreground notification arrived seconds late or never. So: the first one at
 * once, a new title at once, the finished state at once, and otherwise at most one every
 * [everyMs], and only when the whole percent changed.
 */
internal class ForegroundThrottle(
    private val now: () -> Long,
    private val everyMs: Long = EVERY_MS,
    private val post: suspend (title: String?, fraction: Float?) -> Unit,
) {
    private var posted = false
    private var lastAt = 0L
    private var lastTitle: String? = null
    private var lastPercent = UNKNOWN

    suspend fun update(title: String?, fraction: Float?) {
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: UNKNOWN
        val t = now()
        if (posted) {
            if (title == lastTitle && percent == lastPercent) return
            val urgent = title != lastTitle || percent == 100
            if (!urgent && t - lastAt < everyMs) return
        }
        posted = true
        lastAt = t
        lastTitle = title
        lastPercent = percent
        post(title, fraction)
    }

    companion object {
        const val EVERY_MS = 1_200L
        private const val UNKNOWN = -1
    }
}

/** The texts and icon of the download notification, from the app (core modules carry no app words). */
class DownloadNotifications(
    private val channelName: (Context) -> String,
    /** "Downloading ‹title›", or a general line when the title isn't known. */
    private val text: (Context, String?) -> String,
    @param:DrawableRes private val smallIcon: Int,
) {
    fun foregroundInfo(context: Context, id: Int, title: String?, fraction: Float?): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, channelName(context), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(text(context, title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val CHANNEL = "downloads"
    }
}

/** Builds [DownloadWorker]s with their dependencies; add it to the app's `DelegatingWorkerFactory`. */
class DownloadWorkerFactory(private val downloads: () -> Downloads, private val notifications: DownloadNotifications) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == DownloadWorker::class.java.name) DownloadWorker(appContext, workerParameters, downloads(), notifications) else null
}

/**
 * Downloads as WorkManager jobs: one unique job per file, on any network or only an unmetered one
 * (Download on Wi-Fi Only), with backoff for passing failures.
 */
class WorkManagerScheduler(private val workManager: () -> WorkManager) : DownloadScheduler {
    override suspend fun active(): Set<String> = withContext(Dispatchers.IO) {
        workManager().getWorkInfosByTag(DownloadWorker.TAG).get()
            .filter { !it.state.isFinished }
            .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG) }
            .toSet()
    }

    override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag(KEY_TAG + key)
            .build()
        workManager().enqueueUniqueWork(DownloadWorker.workName(key), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(key: String) {
        workManager().cancelUniqueWork(DownloadWorker.workName(key))
    }

    override fun cancelAll() {
        workManager().cancelAllWorkByTag(DownloadWorker.TAG)
    }

    private companion object {
        const val KEY_TAG = "download-key:"
    }
}
