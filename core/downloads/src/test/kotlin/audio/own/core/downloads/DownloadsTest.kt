package audio.own.core.downloads

import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

/** The index: sharing between collections, removal, Keep at Most, the launch reconcile. */
@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = DownloadsDatabase.inMemory(context)
    private val root = File(context.filesDir, "downloads-index-${System.nanoTime()}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduler = FakeScheduler()
    private val settings = TestSettings()
    private var clock = 1_000L
    private val downloads = Downloads(
        db, root, scheduler,
        resolver = { PlaybackLink("http://localhost/none") },
        fetcher = DownloadFetcher(OkHttpClient(), ProxyMemory()),
        settings = settings,
        scope = scope,
        now = { clock },
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
    }

    private fun request(id: String, server: String = "srv") = DownloadRequest("$server|$id", server, """{"id":"$id"}""")

    private fun collection(id: String, kind: String = "album", server: String = "srv") =
        DownloadCollection("$kind:$server|$id", kind, server, "$server|$id", "Name $id")

    /** Marks an item downloaded with a file of [bytes], as a worker would. */
    private suspend fun finish(id: String, bytes: Int = 100, server: String = "srv") {
        val key = "$server|$id"
        val item = db.downloads().item(key)!!
        val relative = "${Downloads.safeName(server)}/${Downloads.safeName(key)}.mp3"
        File(root, relative).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }
        clock += 10
        db.downloads().upsert(item.copy(state = DownloadEntity.DONE, fileName = relative, bytes = bytes.toLong(), downloadedAt = clock))
    }

    @Test
    fun `a song shared by two collections stays until neither needs it`() = runBlocking {
        downloads.download(listOf(request("a"), request("shared")), collection("album1"))
        downloads.download(listOf(request("shared"), request("b")), collection("pl1", "playlist"))
        for (id in listOf("a", "shared", "b")) finish(id)

        downloads.removeCollection("album:srv|album1")
        val keys = db.downloads().items().map { it.key }.toSet()
        assertEquals(setOf("srv|shared", "srv|b"), keys)

        downloads.removeCollection("playlist:srv|pl1")
        assertTrue(db.downloads().items().isEmpty())
        assertTrue(root.walk().none { it.isFile })
    }

    @Test
    fun `a song downloaded on its own outlives its album, and the album keeps it when removed alone`() = runBlocking {
        downloads.download(listOf(request("s")))
        downloads.download(listOf(request("s"), request("t")), collection("al"))
        finish("s"); finish("t")
        // Removing the song alone: the album still lists it, so the file stays.
        downloads.remove("srv|s")
        assertNotNull(db.downloads().item("srv|s"))
        assertFalse(db.downloads().item("srv|s")!!.standalone)
        downloads.removeCollection("album:srv|al")
        assertTrue(db.downloads().items().isEmpty())

        downloads.download(listOf(request("u")))
        downloads.download(listOf(request("u")), collection("al2"))
        finish("u")
        downloads.removeCollection("album:srv|al2")
        assertNotNull(db.downloads().item("srv|u"))
    }

    @Test
    fun `the snapshot counts what is kept, waiting and failed`() = runBlocking {
        downloads.download(listOf(request("a"), request("b"), request("c")), collection("al"))
        downloads.download(listOf(request("loose")))
        finish("a", bytes = 300); finish("loose", bytes = 50)
        db.downloads().upsert(db.downloads().item("srv|c")!!.copy(state = DownloadEntity.FAILED))
        val snap = waitFor { downloads.state.value.takeIf { s -> s.items.size == 4 && s.failedCount == 1 && s.totalBytes == 350L } }
        assertEquals(1, snap.pendingCount)
        assertEquals(1 to 3, snap.progress("album:srv|al"))
        assertEquals(listOf("srv|loose"), snap.loose.map { it.key })
        assertTrue(snap.isDownloading("album:srv|al"))
    }

    @Test
    fun `Keep at Most lets the least recently played go, never the playing one`() = runBlocking {
        downloads.download(listOf(request("a"), request("b"), request("c")))
        finish("a"); finish("b"); finish("c")
        downloads.notePlayed("srv|a")
        clock += 10
        downloads.notePlayed("srv|c")
        settings.cap = 250
        settings.playing = "srv|b"
        downloads.enforceCap()
        // b is the oldest by download, but it is playing; a was played before c.
        assertEquals(setOf("srv|b", "srv|c"), db.downloads().items().map { it.key }.toSet())
        settings.cap = null
        downloads.enforceCap()
        assertEquals(2, db.downloads().items().size)
    }

    @Test
    fun `the launch reconcile deletes unknown files and forgets lost ones`() = runBlocking {
        downloads.download(listOf(request("kept"), request("lost"), request("waiting")))
        finish("kept"); finish("lost")
        File(root, db.downloads().item("srv|lost")!!.fileName!!).delete()
        val part = downloads.partFile(db.downloads().item("srv|waiting")!!).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        val orphan = File(root, "old/forgotten.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        scheduler.running.clear()
        scheduler.enqueued.clear()

        downloads.reconcile()

        assertNull(db.downloads().item("srv|lost"))
        assertNotNull(downloads.file("srv|kept"))
        assertTrue(part.exists())
        assertFalse(orphan.exists())
        assertFalse(orphan.parentFile!!.exists())
        assertEquals(listOf("srv|waiting"), scheduler.enqueued.map { it.first })
    }

    @Test
    fun `remove all of one server leaves the others`() = runBlocking {
        downloads.download(listOf(request("a", "one"), request("b", "two")))
        finish("a", server = "one"); finish("b", server = "two")
        downloads.removeAll("one")
        assertEquals(listOf("two|b"), db.downloads().items().map { it.key })
        downloads.removeAll()
        assertTrue(db.downloads().items().isEmpty())
    }

    @Test
    fun `keys name one file of an item`() {
        assertEquals("srv|book", Downloads.key("srv|book", null))
        assertEquals("srv|book#f2", Downloads.key("srv|book", "f2"))
        assertFalse(Downloads.safeName("srv|a/b+c").contains('/'))
    }

    private suspend fun <T : Any> waitFor(read: () -> T?): T {
        repeat(100) {
            read()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
        return read() ?: error("timed out")
    }
}
