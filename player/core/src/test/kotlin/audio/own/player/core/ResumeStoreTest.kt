package audio.own.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of music-ios `ResumeStoreTests`: "continue this album where I left off". */
class ResumeStoreTest {
    @Test
    fun unknownContainerIsNil() {
        val store = InMemoryResumeStore()
        assertNull(store.loadResume("album:Queen/A Day at the Races"))
    }

    @Test
    fun roundTrips() {
        val store = InMemoryResumeStore()
        store.saveResume("album:x", "t3", 91_500)

        val resume = store.loadResume("album:x")!!
        assertEquals("t3", resume.itemId)
        assertEquals(91_500L, resume.positionMs)
    }

    @Test
    fun containersAreIndependent() {
        val store = InMemoryResumeStore()
        store.saveResume("album:x", "t1", 10_000)
        store.saveResume("playlist:y", "t9", 200_000)

        assertEquals("t1", store.loadResume("album:x")!!.itemId)
        assertEquals("t9", store.loadResume("playlist:y")!!.itemId)
    }

    @Test
    fun saveOverwrites() {
        val store = InMemoryResumeStore()
        store.saveResume("album:x", "t1", 10_000)
        store.saveResume("album:x", "t2", 3_000)

        assertEquals(ResumeStore.Resume("t2", 3_000), store.loadResume("album:x"))
    }

    @Test
    fun clearResets() {
        val store = InMemoryResumeStore()
        store.saveResume("album:x", "t8", 240_000)
        store.clearResume("album:x")

        assertNull(store.loadResume("album:x"))
    }
}
