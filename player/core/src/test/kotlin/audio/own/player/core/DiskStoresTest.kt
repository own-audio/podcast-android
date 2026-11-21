package audio.own.player.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskStoresTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `resume points survive a relaunch`() {
        val file = File(folder.root, "resume.json")
        DiskResumeStore(file).saveResume("s|album:1", "song:3", 42_000)
        val reopened = DiskResumeStore(file)
        assertEquals(ResumeStore.Resume("song:3", 42_000), reopened.loadResume("s|album:1"))
        reopened.clearResume("s|album:1")
        assertNull(DiskResumeStore(file).loadResume("s|album:1"))
    }

    @Test
    fun `the oldest container is dropped past the capacity`() {
        var now = 0L
        val store = DiskResumeStore(File(folder.root, "resume.json"), capacity = 2, now = { now++ })
        store.saveResume("a", "1", 0)
        store.saveResume("b", "1", 0)
        store.saveResume("c", "1", 0)
        assertNull(store.loadResume("a"))
        assertEquals("1", store.loadResume("c")?.itemId)
    }

    @Test
    fun `the last item is written whole, and a broken file reads as nothing`() {
        val file = File(folder.root, "last-item.json")
        val store = lastItemStore(file)
        assertNull(store.read())
        store.write(LastItem("ep:1", 1_000, containerId = "feed:2"))
        assertEquals(LastItem("ep:1", 1_000, containerId = "feed:2"), lastItemStore(file).read())
        file.writeText("{not json")
        assertNull(store.read())
    }
}
