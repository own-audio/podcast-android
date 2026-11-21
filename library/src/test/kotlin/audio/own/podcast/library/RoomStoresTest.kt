package audio.own.podcast.library

import org.robolectric.RuntimeEnvironment
import audio.own.kit.ownaudio.Podcast
import audio.own.podcast.library.room.PodcastDatabase
import audio.own.podcast.library.room.RoomFeedLibraryStore
import audio.own.podcast.library.room.RoomOwnAudioCatalogueStore
import audio.own.podcast.library.room.RoomValueStore
import audio.own.podcast.library.room.ValueRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import audio.own.kit.ownaudio.Episode as ServerEpisode
import audio.own.kit.ownaudio.Progress as ServerProgress

private const val FEED_URL = "https://feeds.example.com/show.xml"

/** The library's Room stores: the device's feeds, the small values and own.audio catalogues. */
@RunWith(RobolectricTestRunner::class)
class RoomStoresTest {
    private val db = PodcastDatabase.inMemory(RuntimeEnvironment.getApplication())

    @After
    fun close() = db.close()

    private fun library(world: World, store: FeedLibraryStore = RoomFeedLibraryStore(db)) =
        FeedLibrary(store, world.loader, now = { world.time })

    @Test
    fun `a followed feed, its episodes and validators come back from the database`() = runBlocking {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 600_000), Item("e2", 2), Item("e3", 3))), etag = "\"a\"")
        val show = library(world).follow(FEED_URL)
        // Another library over the same database: the app after a restart.
        val again = library(world)
        assertEquals(listOf(show.id), again.shows().map { it.id })
        assertEquals(listOf("e3", "e2", "e1"), again.episodes(show.id).map { it.id })
        assertEquals(600_000L, again.episode(EpisodeKey(show.id, "e1")).durationMs)
        assertEquals(world.time, again.episode(EpisodeKey(show.id, "e1")).firstSeenAt)
        assertEquals("the inbox's one old episode survives the restart", listOf("e3"), again.inbox().map { it.id })
        assertTrue("the stored etag makes the next fetch a 304", again.refresh().isEmpty())
        val record = RoomFeedLibraryStore(db).record(show.id)!!
        assertEquals("\"a\"", record.validators?.etag)
        assertEquals("e3", record.latestAtFollow)
    }

    @Test
    fun `positions and dismissals are written as they change and kept across a restart`() = runBlocking {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 3_600_000), Item("e2", 2, 3_600_000))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        world.advance(60)
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 3_600_000), Item("e2", 2, 3_600_000), Item("e3", 3, 3_600_000), Item("e4", 4, 3_600_000))))
        library.refresh()
        val e3 = EpisodeKey(show.id, "e3")
        val e4 = EpisodeKey(show.id, "e4")
        library.record(120_000, e3, at = world.time)
        library.dismissFromInbox(listOf(e4))
        val again = library(world)
        assertEquals(120_000L, again.progress(e3)?.positionMs)
        assertEquals(world.time, again.progress(e3)?.updatedAt)
        assertEquals(listOf("e3", "e2"), again.inbox().map { it.id })
        // An older report loses to the stored one, in the database as in memory.
        val kept = again.saveProgress(ListeningProgress(5_000, false, world.time.minusSeconds(30)), e3)
        assertEquals(120_000L, kept.positionMs)
        again.markPlayed(e3, true)
        assertEquals(true, library(world).progress(e3)?.played)
    }

    @Test
    fun `unfollowing removes the show, its episodes and its positions`() = runBlocking {
        val world = World()
        world.set(FEED_URL, feed(items = listOf(Item("e1", 1, 3_600_000))))
        world.set("https://other.example.com/feed", feed(title = "Other", items = listOf(Item("o1", 2, 3_600_000))))
        val library = library(world)
        val show = library.follow(FEED_URL)
        val other = library.follow("https://other.example.com/feed")
        library.record(60_000, EpisodeKey(show.id, "e1"))
        library.record(60_000, EpisodeKey(other.id, "o1"))
        library.unfollow(show.id)
        val store = RoomFeedLibraryStore(db)
        assertNull(store.record(show.id))
        assertEquals(listOf("Other"), store.records().map { it.show.title })
        assertEquals("the other show's position stays", setOf("${other.id}|o1"), store.state().progress.keys)
        assertTrue("the episodes went with the show", db.dao().episodes(show.id).isEmpty())
    }

    @Test
    fun `putState writes the difference - removed keys go`() = runBlocking {
        val store = RoomFeedLibraryStore(db)
        val t = Instant.parse("2026-11-21T10:00:00.123456789Z")
        store.putState(FeedLibraryState(mapOf("a|1" to ListeningProgress(1, false, t), "a|2" to ListeningProgress(2, true, t)), setOf("a|3", "a|4")))
        store.putState(FeedLibraryState(mapOf("a|1" to ListeningProgress(10, false, t.plusSeconds(1))), setOf("a|4", "a|5")))
        val state = store.state()
        assertEquals(mapOf("a|1" to ListeningProgress(10, false, t.plusSeconds(1))), state.progress)
        assertEquals(setOf("a|4", "a|5"), state.dismissed)
        assertEquals("every digit of the time is kept", t, RoomFeedLibraryStore(db).also { it.putState(FeedLibraryState(mapOf("b|1" to ListeningProgress(1, false, t)))) }.state().progress["b|1"]?.updatedAt)
    }

    @Test
    fun `a value store keeps its value, null removes it, and an unreadable one reads as nothing`() = runBlocking {
        val serializer = ListSerializer(PendingProgress.Entry.serializer())
        val store = RoomValueStore(db, "pending", serializer)
        assertNull(store.load())
        val pending = PendingProgress(store)
        pending.record(PendingProgress.Entry("phone/a|1", ListeningProgress(5, false, Instant.parse("2026-11-21T10:00:00Z"))))
        assertEquals(listOf("phone/a|1"), PendingProgress(RoomValueStore(db, "pending", serializer)).all().map { it.id })
        store.save(null)
        assertNull(db.dao().value("pending"))
        db.dao().setValue(ValueRow("pending", "{not json"))
        assertNull(store.load())
    }

    @Test
    fun `Up Next and a server's listening state survive a restart`() = runBlocking {
        val entries = RoomValueStore(db, "up-next", ListSerializer(UpNext.Entry.serializer()))
        val episode = Episode(id = "e1", showId = "s1", title = "One", firstSeenAt = Instant.parse("2026-11-01T00:00:00Z"))
        UpNext(entries).playLater(UpNext.Entry("phone/s1|e1", "phone", episode, Show(id = "s1", title = "Show", followedAt = Instant.parse("2026-10-01T00:00:00Z"))))
        val restored = UpNext(RoomValueStore(db, "up-next", ListSerializer(UpNext.Entry.serializer()))).all()
        assertEquals(listOf("phone/s1|e1"), restored.map { it.id })
        assertEquals("Show", restored.first().show?.title)

        val listening = ListeningStore(RoomValueStore(db, "srv/listening", ListeningState.serializer()))
        listening.setSettings(ShowSettings(inInbox = false, speed = 1.5f), "p1")
        assertEquals(1.5f, ListeningStore(RoomValueStore(db, "srv/listening", ListeningState.serializer())).settings("p1").speed)
    }

    private fun podcast(id: String, title: String = id) = Podcast(id = id, title = title, feedUrl = "https://feeds.example.com/$id")

    private fun episode(id: String, podcast: String) = ServerEpisode(id = id, podcastId = podcast, title = "Episode $id", publishedAt = Instant.parse("2026-11-01T00:00:00Z"))

    @Test
    fun `an own audio catalogue round-trips and a later save writes only what changed`() = runBlocking {
        val store = RoomOwnAudioCatalogueStore(db, "srv")
        assertNull("nothing synced yet", store.load())
        val at = Instant.parse("2026-11-21T10:00:00Z")
        val first = OwnAudioCatalogue(
            cursor = "c1",
            podcasts = mapOf("p1" to podcast("p1"), "p2" to podcast("p2")),
            episodes = mapOf("e1" to episode("e1", "p1"), "e2" to episode("e2", "p2")),
            progress = mapOf("e1" to ServerProgress(kind = "episode", id = "e1", positionMs = 5_000, updatedAt = at)),
            syncedAt = at,
        )
        store.save(first)
        assertEquals(first, RoomOwnAudioCatalogueStore(db, "srv").load())
        val second = first.copy(
            cursor = "c2",
            podcasts = mapOf("p1" to podcast("p1", "Renamed")),
            episodes = mapOf("e1" to episode("e1", "p1")),
            progress = emptyMap(),
        )
        store.save(second)
        val read = RoomOwnAudioCatalogueStore(db, "srv").load()
        assertEquals(second, read)
        assertEquals("c2", read?.cursor)
        assertEquals("a removed show's rows are gone", 1, db.dao().ownAudioPodcasts("srv").size)
        assertTrue(db.dao().ownAudioProgress("srv").isEmpty())
    }

    @Test
    fun `forgetting a server removes its catalogue and its values, nobody else's`() = runBlocking {
        RoomOwnAudioCatalogueStore(db, "a").save(OwnAudioCatalogue("c", mapOf("p1" to podcast("p1"))))
        RoomOwnAudioCatalogueStore(db, "b").save(OwnAudioCatalogue("c", mapOf("p2" to podcast("p2"))))
        RoomValueStore(db, "a/listening", ListeningState.serializer()).save(ListeningState(dismissed = setOf("x")))
        RoomValueStore(db, "b/listening", ListeningState.serializer()).save(ListeningState(dismissed = setOf("y")))
        db.forgetServer("a")
        assertNull(RoomOwnAudioCatalogueStore(db, "a").load())
        assertNull(RoomValueStore(db, "a/listening", ListeningState.serializer()).load())
        assertNotNull(RoomOwnAudioCatalogueStore(db, "b").load())
        assertEquals(setOf("y"), RoomValueStore(db, "b/listening", ListeningState.serializer()).load()?.dismissed)
    }
}
