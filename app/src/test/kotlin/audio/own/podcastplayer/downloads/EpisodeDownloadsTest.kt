package audio.own.podcastplayer.downloads

import androidx.work.NetworkType
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.DownloadsDatabase
import audio.own.player.core.ProxyMemory
import audio.own.podcast.library.Episode
import audio.own.podcast.library.Playback
import audio.own.podcast.library.Show
import audio.own.podcast.library.SourceRef
import audio.own.podcast.library.SourcedEpisode
import audio.own.podcastplayer.app.AppLog
import audio.own.podcastplayer.eventually
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Remembers what was scheduled and how, and counts it as running until it's cancelled. */
private class RecordingScheduler(private val isAutomatic: (String) -> Boolean) : DownloadScheduler {
    data class Enqueued(val key: String, val wifiOnly: Boolean, val replace: Boolean, val automatic: Boolean)

    val enqueued: MutableList<Enqueued> = java.util.concurrent.CopyOnWriteArrayList()
    private val running = Collections.synchronizedSet(HashSet<String>())

    override suspend fun active(): Set<String> = running.toSet()

    override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) {
        enqueued += Enqueued(key, wifiOnly, replace, isAutomatic(key))
        running += key
    }

    override fun cancel(key: String) {
        running -= key
    }

    override fun cancelAll() = running.clear()
}

/** The podcast side of `:core:downloads`: the two queues, the records, the files and holdings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EpisodeDownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = File(context.filesDir, "episodes-${System.nanoTime()}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer().apply { start() }
    private var answer: Playback? = null
    private val holdings: MutableList<Pair<String, List<String>>> = java.util.concurrent.CopyOnWriteArrayList()
    private lateinit var scheduler: RecordingScheduler

    private val serverId = UUID.fromString("6f1c0a52-0000-4000-8000-000000000001")
    private val show = Show(id = "p1", title = "Server Show")
    private val onServer = SourcedEpisode(SourceRef.Server(serverId), Episode(id = "e1", showId = "p1", title = "One", audioType = "audio/mpeg"))
    private val onDevice = SourcedEpisode(SourceRef.Phone, Episode(id = "e2", showId = "s2", title = "Two", audioUrl = "https://cdn.example.com/two.m4a?x=1"))

    private val downloads = EpisodeDownloads(
        context = context,
        http = OkHttpClient(),
        userAgent = "test",
        proxyMemory = ProxyMemory(),
        playback = { answer ?: Playback(server.url("/e.mp3").toString(), isServerFile = true) },
        wifiOnly = { true },
        playing = { null },
        reportHoldings = { id, episodes -> holdings += id to episodes },
        log = AppLog(null, echo = false),
        scope = scope,
        root = root,
        db = DownloadsDatabase.inMemory(context),
        schedulerFor = { automatic -> RecordingScheduler(automatic).also { scheduler = it } },
    )

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        root.deleteRecursively()
    }

    private fun mp3(size: Int = 4096) = Buffer().write(ByteArray(size) { i -> if (i < 3) "ID3"[i].code.toByte() else (i % 251).toByte() })

    @Test
    fun `the listener's download starts now, a rule's waits, and asking for it moves it to the quick queue`() = runBlocking {
        downloads.download(onServer, show, automatic = true)
        assertEquals(listOf(RecordingScheduler.Enqueued(onServer.id, wifiOnly = true, replace = false, automatic = true)), scheduler.enqueued.toList())

        downloads.download(onServer, show)
        assertEquals("moved, keeping what came", RecordingScheduler.Enqueued(onServer.id, wifiOnly = true, replace = true, automatic = false), scheduler.enqueued.last())

        scheduler.enqueued.clear()
        downloads.download(onDevice, null)
        downloads.download(onDevice, null, automatic = true)
        assertTrue("the listener's, started at once", scheduler.enqueued.all { !it.automatic })
        eventually { downloads.state.value.entries.size == 2 }
        assertFalse("a rule doesn't take over the listener's", downloads.state.value.entries.getValue(onDevice.id).record.automatic)
    }

    @Test
    fun `a downloaded episode keeps its record, plays from its file and is reported to own_audio`() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/octet-stream").body(mp3()).build())
        downloads.download(onServer, show)
        eventually { onServer.id in downloads.state.value.entries }
        val outcome = downloads.core.run(onServer.id, attempt = 0)
        assertEquals(audio.own.core.downloads.Downloads.Outcome.DONE, outcome)
        eventually { downloads.state.value.isDownloaded(onServer.id) }
        val entry = downloads.state.value.entries.getValue(onServer.id)
        assertEquals("Server Show", entry.record.show?.title)
        assertEquals(onServer, entry.record.item)
        assertEquals(4096L, entry.bytes)
        val file = downloads.localFile(onServer.id)
        assertNotNull(file)
        assertTrue("named by its bytes: $file", file!!.path!!.endsWith(".mp3"))
        eventually(6_000) { holdings.isNotEmpty() }
        assertEquals(serverId.toString() to listOf("e1"), holdings.last())

        downloads.remove(listOf(onServer.id))
        eventually { downloads.state.value.entries.isEmpty() }
        assertNull(downloads.localFile(onServer.id))
    }

    @Test
    fun `an episode the server only streams fails, saying why`() = runBlocking {
        answer = Playback("https://server.example/hls/master.m3u8", isServerFile = true, isHls = true)
        downloads.download(onServer, show)
        downloads.core.run(onServer.id, attempt = 0)
        eventually { downloads.state.value.status(onServer.id) == EpisodeDownload.Status.FAILED }
        assertEquals(EpisodeDownloads.STREAM_ONLY, downloads.state.value.entries.getValue(onServer.id).error)
        assertEquals(1, downloads.state.value.failedCount)
    }

    @Test
    fun `a rule's work waits for Wi-Fi and a battery that isn't low, the listener's is expedited`() {
        val rule = PodcastDownloadScheduler.request("k", wifiOnly = true, automatic = true).workSpec
        assertEquals(NetworkType.UNMETERED, rule.constraints.requiredNetworkType)
        assertTrue(rule.constraints.requiresBatteryNotLow())
        assertFalse(rule.expedited)
        val asked = PodcastDownloadScheduler.request("k", wifiOnly = false, automatic = false).workSpec
        assertEquals(NetworkType.CONNECTED, asked.constraints.requiredNetworkType)
        assertFalse(asked.constraints.requiresBatteryNotLow())
        assertTrue(asked.expedited)
    }

    @Test
    fun `an extension from the feed's type or address, for bytes that say nothing`() {
        assertEquals("mp3", EpisodeDownloads.extensionHint(onServer.episode))
        assertEquals("m4a", EpisodeDownloads.extensionHint(onDevice.episode))
        assertNull(EpisodeDownloads.extensionHint(Episode(id = "x", showId = "s", title = "x")))
    }
}
