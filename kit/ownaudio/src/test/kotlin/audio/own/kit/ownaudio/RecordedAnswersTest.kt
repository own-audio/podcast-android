package audio.own.kit.ownaudio

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers recorded from a real server (the public demo, addresses and signatures replaced):
 * stubs written by hand only prove we read what we wrote ourselves.
 */
class RecordedAnswersTest {
    private fun <T> read(name: String, serializer: KSerializer<T>): T = OwnAudioJson.decodeFromString(serializer, answer(name))

    @Test
    fun `every recorded answer reads into its model`() {
        assertTrue(read("albums", Page.serializer(Album.serializer())).items.isNotEmpty())
        val tracks = read("tracks", Page.serializer(Track.serializer()))
        assertTrue(tracks.items.isNotEmpty() && tracks.included!!.albums.isNotEmpty())
        assertTrue(read("artists", Page.serializer(Contributor.serializer())).items.isNotEmpty())
        assertTrue(read("genres", Page.serializer(Genre.serializer())).items.isNotEmpty())
        assertTrue(read("playlists", Page.serializer(Playlist.serializer())).items.isNotEmpty())
        assertTrue(read("smart_playlists", Page.serializer(SmartPlaylist.serializer())).items.isNotEmpty())
        assertTrue(read("smart_presets", ListSerializer(SmartPreset.serializer())).isNotEmpty())
        assertTrue(read("podcasts", Page.serializer(Podcast.serializer())).items.isNotEmpty())
        assertTrue(read("books", Page.serializer(Book.serializer())).items.isNotEmpty())
        read("family", Family.serializer())
        assertTrue(read("family_members", ListSerializer(Member.serializer())).size > 1)
        assertTrue(read("progress_list", Page.serializer(Progress.serializer())).items.isNotEmpty())
        read("favourites", Page.serializer(Favourite.serializer()))
        read("changes", ChangesAnswer.serializer())
        val search = read("search", SearchAnswer.serializer())
        assertTrue(search.tracks.isNotEmpty() || search.albums.isNotEmpty() || search.artists.isNotEmpty())
        read("settings", Settings.serializer())
        assertTrue(read("stats", ListeningStats.serializer()).buckets.isNotEmpty())
        assertTrue(read("sessions", ListSerializer(Session.serializer())).isNotEmpty())
        assertTrue(read("library_folders", LibraryFolders.serializer()).folders.isNotEmpty())
        read("family_storage", FamilyStorage.serializer())
        read("discovery", Discovery.serializer()).requireV2()
    }
}
