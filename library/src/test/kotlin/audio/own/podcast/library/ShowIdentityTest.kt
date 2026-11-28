package audio.own.podcast.library

import audio.own.kit.feed.FeedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowIdentityTest {
    private val followed = listOf(
        Show(id = "a", title = "Alpha", feedUrl = "https://feeds.example.com/alpha.xml", link = "https://alpha.example.com", podcastGuid = "GUID-A"),
        Show(id = "b", title = "Beta", feedUrl = "https://feeds.example.com/beta.xml?key=1", link = "https://beta.example.com/"),
    )

    @Test
    fun `podcast guid first, whatever the address`() {
        assertEquals("a", ShowIdentity.of(followed, FeedShow(title = "Renamed", podcastGuid = "guid-a"), listOf("https://elsewhere.example/feed"))?.id)
    }

    @Test
    fun `then the address, spelled differently`() {
        assertEquals("a", ShowIdentity.of(followed, FeedShow(title = "x"), listOf("http://WWW.feeds.example.com:80/alpha.xml/"))?.id)
        assertNull("another token is another address", ShowIdentity.byAddress(followed, listOf("https://feeds.example.com/beta.xml?key=2")))
    }

    @Test
    fun `then title and website together`() {
        assertEquals("b", ShowIdentity.of(followed, FeedShow(title = "beta ", link = "http://beta.example.com"), listOf("https://other.example/b.xml"))?.id)
        assertNull("the title alone is not enough", ShowIdentity.of(followed, FeedShow(title = "Beta"), listOf("https://other.example/b.xml")))
    }
}
