package audio.own.core.downloads

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Audio as far as the payload check goes: an ID3 header and enough bytes after it. */
internal fun mp3Bytes(size: Int = 4096): ByteArray = ByteArray(size) { i -> if (i < 3) "ID3"[i].code.toByte() else (i % 251).toByte() }
internal fun flacBytes(size: Int = 4096): ByteArray = ByteArray(size) { i -> if (i < 4) "fLaC"[i].code.toByte() else (i % 13).toByte() }

/** Records what the manager asked of WorkManager, and says what "runs". */
internal class FakeScheduler : DownloadScheduler {
    val running: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val enqueued: MutableList<Pair<String, Boolean>> = java.util.concurrent.CopyOnWriteArrayList()
    var cancelledAll = 0
    override suspend fun active(): Set<String> = running.toSet()
    override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) {
        enqueued += key to wifiOnly
        running += key
    }
    override fun cancel(key: String) {
        running -= key
    }
    override fun cancelAll() {
        cancelledAll++
        running.clear()
    }
}

internal class TestSettings(var wifiOnly: Boolean = true, var cap: Long? = null, var playing: String? = null) : Downloads.Settings {
    override fun wifiOnly() = wifiOnly
    override fun capBytes() = cap
    override fun protectedKey() = playing
}

@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(404).build() }
    private lateinit var db: DownloadsDatabase
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduler = FakeScheduler()
    private val settings = TestSettings()
    private val resolves = AtomicInteger()
    private var link: () -> PlaybackLink = { PlaybackLink(server.url("/file").toString()) }
    private val holdings = java.util.concurrent.CopyOnWriteArrayList<Map<String, List<String>>>()
    private lateinit var downloads: Downloads

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
        db = DownloadsDatabase.inMemory(context)
        root = File(context.filesDir, "downloads-test-${System.nanoTime()}")
        downloads = Downloads(
            db = db,
            root = root,
            scheduler = scheduler,
            resolver = { resolves.incrementAndGet(); link() },
            fetcher = DownloadFetcher(OkHttpClient(), ProxyMemory(), progressEveryMs = 0),
            settings = settings,
            scope = scope,
            onHoldingsChanged = { holdings += it },
            holdingsDelayMs = 0,
            log = { println(it) },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
        root.deleteRecursively()
    }

    private fun request(id: String, hint: String? = null) = DownloadRequest(itemId = "srv|$id", serverId = "srv", record = """{"id":"$id"}""", extensionHint = hint, title = "Song $id")

    private fun worker(key: String, attempt: Int = 0) = TestListenableWorkerBuilder<DownloadWorker>(context)
        .setWorkerFactory(DownloadWorkerFactory({ downloads }, DownloadNotifications({ "Downloads" }, { _, t -> "Downloading $t" }, android.R.drawable.stat_sys_download)))
        .setInputData(workDataOf(DownloadWorker.KEY to key))
        .setRunAttemptCount(attempt)
        .build()

    private fun audio(bytes: ByteArray, type: String = "audio/mpeg") =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).body(Buffer().write(bytes)).build()

    @Test
    fun `a file is kept under its server with the extension its bytes call for`() = runBlocking {
        answer = { audio(flacBytes(), type = "application/octet-stream") }
        downloads.download(listOf(request("t1", hint = "mp3")))
        val result = worker("srv|t1").doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val item = db.downloads().item("srv|t1")!!
        assertEquals(DownloadEntity.DONE, item.state)
        assertTrue(item.fileName!!.endsWith(".flac"))
        val file = downloads.file("srv|t1")!!
        assertArrayEquals(flacBytes(), file.readBytes())
        assertEquals(File(root, Downloads.safeName("srv")), file.parentFile)
        assertFalse(downloads.partFile(item).exists())
        // The bare client: never an Authorization header.
        assertNull(requests.single().headers["Authorization"])
        delay(200)
        assertEquals(listOf("srv|t1"), holdings.last()["srv"])
    }

    @Test
    fun `the extension comes from the content type, then the record, then mp3`() {
        val unknown = ByteArray(16)
        assertEquals("m4a", DownloadFetcher.extension(unknown, "audio/mp4; charset=x", "flac"))
        assertEquals("flac", DownloadFetcher.extension(unknown, "application/octet-stream", "FLAC"))
        assertEquals("mp3", DownloadFetcher.extension(unknown, null, null))
        assertEquals("ogg", DownloadFetcher.extension("OggS....".toByteArray(), "audio/mpeg", null))
    }

    @Test
    fun `an error page answered with 200 is never kept as a song`() = runBlocking {
        answer = { MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"subsonic-response":{"status":"failed"}}""").build() }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.failure(), worker("srv|t1").doWork())
        val item = db.downloads().item("srv|t1")!!
        assertEquals(DownloadEntity.FAILED, item.state)
        assertEquals(DownloadFailure.NOT_AUDIO, item.error)
        assertNull(downloads.file("srv|t1"))
        assertEquals(1, downloads.state.value.let { db.downloads().items().count { it.state == DownloadEntity.FAILED } })
    }

    @Test
    fun `audio after junk bytes is kept when the server says audio`() = runBlocking {
        // An Audiobookshelf book's MP3s had 384 bytes before the first frame.
        val body = ByteArray(384) { 0x20 } + mp3Bytes()
        answer = { audio(body, type = "audio/mpeg") }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
        assertTrue(downloads.file("srv|t1")!!.name.endsWith(".mp3"))
        assertArrayEquals(body, downloads.file("srv|t1")!!.readBytes())
    }

    @Test
    fun `JSON sent as octet-stream is caught by its bytes`() = runBlocking {
        answer = { audio(("{\"error\":1}" + " ".repeat(2000)).toByteArray(), type = "application/octet-stream") }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.failure(), worker("srv|t1").doWork())
        assertEquals(DownloadFailure.NOT_AUDIO, db.downloads().item("srv|t1")!!.error)
    }

    @Test
    fun `a part left by a killed run resumes with a range`() = runBlocking {
        val whole = mp3Bytes(10_000)
        downloads.download(listOf(request("t1")))
        val part = downloads.partFile(db.downloads().item("srv|t1")!!)
        part.parentFile!!.mkdirs()
        part.writeBytes(whole.copyOf(6_000))
        answer = { r ->
            val range = r.headers["Range"]
            if (range == "bytes=6000-") {
                MockResponse.Builder().code(206).addHeader("Content-Type", "audio/mpeg")
                    .addHeader("Content-Range", "bytes 6000-9999/10000")
                    .body(Buffer().write(whole.copyOfRange(6_000, 10_000))).build()
            } else {
                audio(whole)
            }
        }
        assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
        assertEquals("bytes=6000-", requests.single().headers["Range"])
        assertArrayEquals(whole, downloads.file("srv|t1")!!.readBytes())
    }

    @Test
    fun `a server that ignores the range sends the whole file again`() = runBlocking {
        val whole = mp3Bytes(5_000)
        downloads.download(listOf(request("t1")))
        val part = downloads.partFile(db.downloads().item("srv|t1")!!)
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(3_000) { 7 })
        answer = { audio(whole) }
        assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
        assertArrayEquals(whole, downloads.file("srv|t1")!!.readBytes())
    }

    @Test
    fun `a dropped connection is tried again and keeps the item waiting`() = runBlocking {
        // Nothing listens there: the connection is refused.
        link = { PlaybackLink("http://127.0.0.1:1/file") }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.retry(), worker("srv|t1").doWork())
        assertEquals(DownloadEntity.QUEUED, db.downloads().item("srv|t1")!!.state)
        // After the last attempt it shows as failed, ready for Retry N Failed.
        assertEquals(ListenableWorker.Result.failure(), worker("srv|t1", attempt = Downloads.MAX_ATTEMPTS - 1).doWork())
        assertEquals(DownloadEntity.FAILED, db.downloads().item("srv|t1")!!.state)
        downloads.retryFailed()
        assertEquals(DownloadEntity.QUEUED, db.downloads().item("srv|t1")!!.state)
    }

    @Test
    fun `an expired link is asked for again once`() = runBlocking {
        var calls = 0
        answer = { if (calls++ == 0) MockResponse.Builder().code(403).build() else audio(mp3Bytes()) }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
        assertEquals(2, resolves.get())
    }

    @Test
    fun `a gone file fails for good`() = runBlocking {
        answer = { MockResponse.Builder().code(404).build() }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.failure(), worker("srv|t1").doWork())
        assertEquals("http_404", db.downloads().item("srv|t1")!!.error)
    }

    @Test
    fun `storage blocked on this network goes through the media proxy`() = runBlocking {
        val storage = MockWebServer()
        storage.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(403).build()
        }
        storage.start()
        try {
            answer = { r ->
                if (r.url.queryParameter("route") == "proxy") audio(mp3Bytes())
                else MockResponse.Builder().code(302).addHeader("Location", storage.url("/bucket/file").toString()).build()
            }
            link = { PlaybackLink(server.url("/api/v2/stream?id=t1&s=sig").toString()) }
            downloads.download(listOf(request("t1")))
            assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
            assertEquals(listOf(null, "proxy"), requests.map { it.url.queryParameter("route") })
        } finally {
            storage.close()
        }
    }

    @Test
    fun `two run at a time and the next starts when one finishes`() = runBlocking {
        answer = { audio(mp3Bytes()) }
        downloads.download((1..5).map { request("t$it") })
        assertEquals(listOf("srv|t1", "srv|t2"), scheduler.enqueued.map { it.first })
        assertTrue(scheduler.enqueued.all { it.second })
        worker("srv|t1").doWork()
        scheduler.running -= "srv|t1"
        assertEquals(listOf("srv|t1", "srv|t2", "srv|t3"), scheduler.enqueued.map { it.first })
        assertEquals(1, downloads.state.value.let { db.downloads().done().size })
    }

    @Test
    fun `changing Wi-Fi only starts the waiting work again under the new rule`() = runBlocking {
        downloads.download((1..3).map { request("t$it") })
        settings.wifiOnly = false
        scheduler.enqueued.clear()
        downloads.wifiOnlyChanged()
        assertEquals(1, scheduler.cancelledAll)
        assertEquals(listOf("srv|t1" to false, "srv|t2" to false), scheduler.enqueued)
    }

    @Test
    fun `progress is reported to the screens while it runs`() = runBlocking {
        answer = { audio(mp3Bytes(200_000)) }
        downloads.download(listOf(request("t1")))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val watch = scope.launch { downloads.progress.collect { map -> seen += map.values } }
        worker("srv|t1").doWork()
        delay(100)
        watch.cancel()
        assertTrue(seen.isNotEmpty())
        assertTrue(downloads.progress.value.isEmpty())
    }

    @Test
    fun `removed while downloading, nothing is kept`() = runBlocking {
        answer = { audio(mp3Bytes()) }
        downloads.download(listOf(request("t1")))
        downloads.remove("srv|t1")
        assertEquals(ListenableWorker.Result.success(), worker("srv|t1").doWork())
        assertNull(downloads.file("srv|t1"))
        assertTrue(root.walk().none { it.isFile })
    }

    @Test
    fun `failing setup is not a crash`() = runBlocking {
        link = { throw IOException("offline") }
        downloads.download(listOf(request("t1")))
        assertEquals(ListenableWorker.Result.retry(), worker("srv|t1").doWork())
    }
}
