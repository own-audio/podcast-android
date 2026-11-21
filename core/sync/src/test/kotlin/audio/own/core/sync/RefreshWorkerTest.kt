package audio.own.core.sync

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RefreshWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun worker(work: suspend () -> Boolean) = TestListenableWorkerBuilder<RefreshWorker>(context)
        .setWorkerFactory(RefreshWorkerFactory(work))
        .build()

    @Test
    fun `the app's work runs, and a failure is tried again later`() = runBlocking {
        var ran = 0
        assertEquals(ListenableWorker.Result.success(), worker { ran++; true }.doWork())
        assertEquals(ListenableWorker.Result.retry(), worker { false }.doWork())
        assertEquals(ListenableWorker.Result.retry(), worker { error("boom") }.doWork())
        assertEquals(1, ran)
    }

    @Test
    fun `scheduled hourly at most, on a connected network, not on a low battery`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val wm = WorkManager.getInstance(context)
        PeriodicRefresh.schedule(wm, everyHours = 0)
        val info = wm.getWorkInfosForUniqueWork(PeriodicRefresh.NAME).get().single()
        assertEquals(TimeUnit.HOURS.toMillis(1), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }
}
