package audio.own.kit.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    @Test
    fun `an iTunes RSS feed - the show with its tags, episodes with HTML notes made text`() {
        val feed = testParser.parse(fixture("itunes.xml"))
        val show = feed.show
        assertEquals("The Workshop Hour", show.title)
        assertEquals("Jane Maker & Friends", show.author)
        assertEquals("Weekly talks about making things.\n\nTools & techniques.", show.description)
        assertEquals("itunes:image wins over the RSS image", "https://example.com/workshop/cover.jpg", show.imageUrl)
        assertEquals(listOf("Leisure", "Hobbies", "Arts & Design"), show.categories)
        assertTrue(show.isSerial && !show.isExplicit && show.language == "en-us")
        assertEquals("https://feeds.example.org/workshop", show.newFeedUrl)
        assertEquals("https://example.com/workshop", show.link)
        assertEquals(date("2026-10-16T08:30:00Z"), show.lastBuildDate)

        assertEquals(listOf("workshop-0003", "https://example.com/workshop/2", "workshop-trailer"), feed.episodes.map { it.id })
        val first = feed.episodes[0]
        assertEquals("Episode 3: Chisels", first.title)
        assertEquals(
            "content:encoded wins, lists keep their bullets",
            "We sharpen chisels.\n\n• Waterstones\n• Honing guide\n\nLinks: stones — enjoy",
            first.description,
        )
        assertEquals(3_723_000L, first.durationMs)
        assertEquals(FeedEnclosure("https://cdn.example.com/workshop/003.mp3", "audio/mpeg", 58_123_456), first.enclosure)
        assertTrue(first.season == 1 && first.episode == 3 && first.episodeType == "full" && first.isExplicit)
        assertEquals("https://example.com/workshop/3.jpg", first.imageUrl)
        assertEquals(date("2026-10-16T08:00:00Z"), first.publishedAt)
        assertEquals(2_710_000L, feed.episodes[1].durationMs)
        assertEquals("an escaped HTML summary", "Saws, mostly Japanese.", feed.episodes[1].description)
        assertEquals("a numeric zone, a one-digit day", date("2026-10-09T06:00:00Z"), feed.episodes[1].publishedAt)
        assertNull("a length of 0 is unknown", feed.episodes[1].enclosure?.length)
        assertTrue(feed.episodes[2].durationMs == 3_723_000L && feed.episodes[2].episodeType == "trailer")
        assertEquals("no seconds, a named US zone", date("2026-10-01T11:00:00Z"), feed.episodes[2].publishedAt)
    }

    @Test
    fun `Podcasting 2_0 - guid, funding, chapters, transcripts, people, inline chapters, media content`() {
        val feed = testParser.parse(fixture("podcasting20.xml"))
        assertEquals("917393e3-1b1e-5cef-ace4-edaa54e1f810", feed.show.podcastGuid)
        assertEquals(listOf(FeedLink("https://openair.example.net/support", "Support the show")), feed.show.funding)
        val rain = feed.episodes[0]
        assertEquals("https://openair.example.net/rain.chapters.json", rain.chaptersUrl)
        assertEquals(listOf("text/vtt", "application/x-subrip"), rain.transcripts.map { it.type })
        assertEquals("en", rain.transcripts[0].language)
        assertEquals(
            listOf(
                FeedPerson("Ana Rivers", "guest", "https://openair.example.net/ana.jpg", "https://ana.example.net"),
                FeedPerson("Sam Host", "host", null, null),
            ),
            rain.persons,
        )
        assertEquals("sorted by start", listOf("Intro & drizzle", "Thunder", "Puddles"), rain.inlineChapters.map { it.title })
        assertEquals(listOf(0L, 600_000L, 1_530_000L), rain.inlineChapters.map { it.startMs })
        assertEquals("closed by the next; the last stays open", listOf(600_000L, 1_530_000L, null), rain.inlineChapters.map { it.endMs })
        assertEquals("https://openair.example.net/drizzle", rain.inlineChapters[0].url)
        val wind = feed.episodes[1]
        assertEquals("media:content stands in for a missing enclosure", "https://openair.example.net/wind.m4a", wind.enclosure?.url)
        assertEquals("no guid: the enclosure URL identifies it", "https://openair.example.net/wind.m4a", wind.id)
    }

    @Test
    fun `Atom - the feed and an entry with an enclosure link`() {
        val feed = testParser.parse(fixture("atom.xml"))
        assertEquals("Atom Talk", feed.show.title)
        assertEquals("Ada Atom", feed.show.author)
        assertEquals("A feed in Atom.", feed.show.description)
        assertEquals("https://atom.example.org/", feed.show.link)
        assertEquals("https://atom.example.org/logo.png", feed.show.imageUrl)
        val entry = feed.episodes.first()
        assertEquals("tag:atom.example.org,2026:1", entry.id)
        assertEquals("First", entry.title)
        assertEquals(FeedEnclosure("https://atom.example.org/1.mp3", "audio/mpeg", 4321), entry.enclosure)
        assertEquals("https://atom.example.org/1", entry.link)
        assertEquals("Hello Atom", entry.description)
        assertEquals(date("2026-10-15T07:30:00.500Z"), entry.publishedAt)
    }

    @Test
    fun `a messy feed - bare ampersands, mis-decoded text, bad dates and durations, odd URLs, a post without audio`() {
        val feed = testParser.parse(fixture("messy.xml"))
        assertEquals("Tom & Jerry Talk", feed.show.title)
        assertEquals(4, feed.episodes.size)
        assertEquals("UTF-8 read as Latin-1 upstream, repaired", "Příběh první", feed.episodes[0].title)
        assertEquals(date("2026-10-06T05:00:00Z"), feed.episodes[0].publishedAt)
        assertNull("a zero duration is no duration", feed.episodes[0].durationMs)
        assertEquals("https://messy.example.com/a%20b.mp3", feed.episodes[0].enclosure?.url)
        assertNull(feed.episodes[1].publishedAt)
        assertNull(feed.episodes[1].durationMs)
        assertEquals("https://messy.example.com/2.mp3", feed.episodes[1].enclosure?.url)
        assertEquals(77L, feed.episodes[1].enclosure?.length)
        assertEquals(date("2026-10-01T00:00:00Z"), feed.episodes[2].publishedAt)
        assertEquals(90_500L, feed.episodes[2].durationMs)
        assertEquals("https://messy.example.com/3.mp3", feed.episodes[2].id)
        assertNull(feed.episodes[3].enclosure)
        assertEquals("https://messy.example.com/post", feed.episodes[3].id)
    }

    @Test
    fun `an HTML page or an OPML file is not a feed`() {
        assertThrows(FeedException.NotAFeed::class.java) { testParser.parse(fixture("notafeed.html")) }
        assertThrows(FeedException.NotAFeed::class.java) { testParser.parse(fixture("opml-not-feed.xml")) }
        assertThrows(FeedException::class.java) { testParser.parse("not xml at all".encodeToByteArray()) }
    }

    @Test
    fun `a feed that breaks off after some episodes keeps them`() {
        val broken = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Cut</title>
            <item><guid>1</guid><title>One</title><enclosure url="https://cut.example/1.mp3"/></item>
            <item><guid>2</guid><title>Two</title><enclosure url="https://cut.example/2.mp3"/></item>
            <item><guid>3</guid><title>Thr
        """.trimIndent()
        val feed = testParser.parse(broken.encodeToByteArray())
        assertEquals("Cut", feed.show.title)
        assertEquals(listOf("1", "2"), feed.episodes.map { it.id })
    }

    @Test
    fun `HTML entities written straight into the XML still parse`() {
        val xml = """<?xml version="1.0"?><rss version="2.0"><channel><title>A&nbsp;B &mdash; C</title>
            <item><guid>1</guid><title>x</title><enclosure url="https://e.example/1.mp3"/></item></channel></rss>"""
        val feed = testParser.parse(xml.encodeToByteArray())
        assertEquals("A B — C", feed.show.title)
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun `a show without a title is named after its site`() {
        val xml = """<rss version="2.0"><channel><link>https://untitled.example.com/show</link></channel></rss>"""
        assertEquals("untitled.example.com", testParser.parse(xml.encodeToByteArray()).show.title)
    }
}

class FeedValuesTest {
    @Test
    fun `durations in every form`() {
        val cases = listOf(
            "1:02:03" to 3_723_000L, "62:03" to 3_723_000L, "3723" to 3_723_000L, "3723.5" to 3_723_500L,
            "1:2:3" to 3_723_000L, "00:45" to 45_000L,
        )
        for ((text, ms) in cases) assertEquals(text, ms, FeedValues.durationMs(text))
    }

    @Test
    fun `not durations`() {
        for (text in listOf("", "0", "abc", "-5", "1:2:3:4", "9999999", "1e3", "NaN")) assertNull(text, FeedValues.durationMs(text))
    }

    @Test
    fun `dates as feeds write them`() {
        val cases = listOf(
            "Fri, 16 Oct 2026 08:00:00 GMT" to "2026-10-16T08:00:00Z",
            "16 Oct 2026 08:00:00 +0000" to "2026-10-16T08:00:00Z",
            "Fri, 16 Oct 2026 08:00:00 UT" to "2026-10-16T08:00:00Z",
            "Fri, 16 Oct 2026 10:00:00 CEST" to "2026-10-16T08:00:00Z",
            "Friday, 16 October 2026 08:00:00 +0000" to "2026-10-16T08:00:00Z",
            "2026-10-16T08:00:00Z" to "2026-10-16T08:00:00Z",
            "2026-10-16 08:00:00 +0000" to "2026-10-16T08:00:00Z",
            // Beyond the iOS cases: a wrong weekday, an offset with a colon, lower case.
            "Mon, 16 Oct 2026 08:00:00 +0000" to "2026-10-16T08:00:00Z",
            "Fri, 16 Oct 2026 10:00:00 +02:00" to "2026-10-16T08:00:00Z",
            "fri, 16 oct 2026 08:00:00 gmt".uppercase() to "2026-10-16T08:00:00Z",
            "2026-10-16T10:00:00+0200" to "2026-10-16T08:00:00Z",
        )
        for ((text, iso) in cases) assertEquals(text, date(iso), FeedValues.date(text))
        assertNull(FeedValues.date("not a date"))
        assertNull(FeedValues.date(""))
    }

    @Test
    fun `URLs as feeds write them`() {
        assertEquals("https://e.example/a%20b.mp3", FeedValues.url("  https://e.example/a b.mp3 "))
        assertEquals("https://e.example/x", FeedValues.url("//e.example/x"))
        assertNull(FeedValues.url("ftp://e.example/x"))
        assertNull(FeedValues.url("javascript:alert(1)"))
        assertNull(FeedValues.url("not a url"))
    }

    @Test
    fun `HTML notes become text with paragraphs and entities decoded`() {
        assertEquals("One\n\nTwo\nthree", HtmlText.plain("<p>One</p><p>Two<br/>three</p>"))
        assertEquals("A & B — C – D E", HtmlText.plain("A &amp; B &#x2014; C &#8211; D&nbsp;E"))
        assertEquals("Only this", HtmlText.plain("<script>x()</script><style>p{}</style>Only this"))
        assertEquals("no markup, just spaces", HtmlText.plain("no markup,   just   spaces"))
        assertEquals("AT&T &unknown; &", HtmlText.decodeEntities("AT&T &unknown; &"))
        assertEquals("😀", HtmlText.decodeEntities("&#x1F600;"))
    }

    @Test
    fun `only certainly broken text is repaired`() {
        assertEquals("Příběh první", Mojibake.repair("PÅ\u0099Ã­bÄ\u009Bh prvnÃ­"))
        assertEquals("Ångström", Mojibake.repair("Ångström"))
        assertEquals("plain", Mojibake.repair("plain"))
    }
}

class ChaptersTest {
    @Test
    fun `a Podcasting 2_0 chapters document, sorted and closed, toc kept`() {
        val chapters = Chapters.parse(fixture("chapters.json"))
        assertEquals(listOf("Cold open", "Sponsor", "Main", "Outro"), chapters.map { it.title })
        assertEquals(listOf(0L, 95_500L, 120_000L, 900_000L), chapters.map { it.startMs })
        assertEquals(listOf(95_500L, 120_000L, 900_000L, null), chapters.map { it.endMs })
        assertFalse(chapters[1].isInTableOfContents)
        assertNotNull(chapters[1].imageUrl)
        assertEquals("https://openair.example.net/main", chapters[2].url)
    }

    @Test
    fun `an unreadable chapters document is malformed`() {
        assertThrows(FeedException.Malformed::class.java) { Chapters.parse("{\"nope\":1}".encodeToByteArray()) }
    }
}
