package audio.own.kit.feed

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant

/**
 * Hands out a fresh pull parser for each document. On Android pass `Xml::newPullParser`; on the
 * JVM, kXML's `KXmlParser` (the parser Android's is built on).
 */
fun interface XmlParserFactory {
    fun newPullParser(): XmlPullParser
}

/**
 * Reads RSS 2.0 (and RSS 1.0) and Atom into a [FeedDocument]. Lenient where real feeds are
 * sloppy: unescaped ampersands, missing GUIDs, dates and durations in odd forms, HTML in every
 * text field, text that was decoded with the wrong encoding somewhere upstream.
 */
class FeedParser(private val parsers: XmlParserFactory) {
    @Throws(FeedException::class)
    fun parse(bytes: ByteArray): FeedDocument {
        val first = parseOnce(bytes)
        if (first.ok) return first.document()
        // The common breakage: a bare "&" in a title. Escape those and try once more; a feed that
        // broke off after the show came through is still worth its episodes so far.
        val repaired = escapeBareAmpersands(bytes)?.takeUnless { it.contentEquals(bytes) }
        val second = repaired?.let(::parseOnce)
        if (second != null && second.ok) return second.document()
        val best = listOfNotNull(second, first).firstOrNull { it.hasContent } ?: throw FeedException.Malformed(first.reason ?: "unreadable XML")
        return best.document()
    }

    private class Outcome(val reader: Reader, val ok: Boolean, val reason: String?) {
        val hasContent get() = reader.episodes.isNotEmpty() || reader.show.title.isNotEmpty()

        fun document(): FeedDocument = reader.document()
    }

    private fun parseOnce(bytes: ByteArray): Outcome {
        val reader = Reader()
        val parser = parsers.newPullParser()
        var ok = true
        var reason: String? = null
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(bytes), null)
            // HTML entities written straight into the XML (not inside CDATA) are an error to a
            // strict parser; knowing the common ones saves the whole feed.
            for ((name, value) in HtmlText.named) {
                if (name !in xmlEntities) runCatching { parser.defineEntityReplacementText(name, value) }
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && !reader.rejectedRoot) {
                when (event) {
                    XmlPullParser.START_TAG -> reader.start(parser)
                    XmlPullParser.TEXT -> reader.text.append(parser.text)
                    XmlPullParser.END_TAG -> reader.end(parser.name, parser.namespace)
                }
                event = parser.next()
            }
        } catch (error: XmlPullParserException) {
            ok = false
            reason = error.message
        } catch (error: IOException) {
            ok = false
            reason = error.message
        } catch (error: RuntimeException) {
            // kXML throws unchecked exceptions on some broken input; it is still broken XML.
            ok = false
            reason = error.message ?: error.javaClass.simpleName
        }
        if (reader.rejectedRoot) throw FeedException.NotAFeed()
        if (reader.kind == null) {
            if (ok) throw FeedException.NotAFeed()
            throw FeedException.Malformed(reason ?: "unreadable XML")
        }
        return Outcome(reader, ok, reason)
    }

    companion object {
        private val xmlEntities = setOf("amp", "lt", "gt", "quot", "apos")

        private val bareAmpersand = Regex("&(?!(?:[A-Za-z][A-Za-z0-9]{1,8}|#[0-9]{1,7}|#x[0-9A-Fa-f]{1,6});)")

        internal fun escapeBareAmpersands(bytes: ByteArray): ByteArray? {
            val text = Mojibake.decodeUtf8Strictly(bytes) ?: return null
            return bareAmpersand.replace(text, "&amp;").encodeToByteArray()
        }
    }
}

private object Namespace {
    fun isITunes(uri: String?) = uri?.lowercase() == "http://www.itunes.com/dtds/podcast-1.0.dtd"
    fun isPodcast(uri: String?): Boolean {
        val value = uri?.lowercase() ?: return false
        return value.contains("podcastindex.org/namespace") || value.contains("podcast-namespace")
    }
    fun isContent(uri: String?) = uri == "http://purl.org/rss/1.0/modules/content/"
    fun isAtom(uri: String?) = uri == "http://www.w3.org/2005/Atom"
    fun isPsc(uri: String?) = uri?.startsWith("http://podlove.org/simple-chapters") == true
    fun isMedia(uri: String?) = uri == "http://search.yahoo.com/mrss/"
    fun isPlain(uri: String?) = uri.isNullOrEmpty() || uri == "http://purl.org/rss/1.0/"
}

/**
 * Element by element. Text is gathered per element (characters and CDATA alike) and read when the
 * element ends; what it means depends on where we are — the channel, an item, the RSS `<image>`,
 * an Atom `<author>`.
 */
private class Reader {
    enum class Kind { RSS, ATOM }

    var kind: Kind? = null
    /** The root was some other XML (HTML, OPML): not a feed, whatever else is wrong with it. */
    var rejectedRoot = false
    val show = ShowDraft()
    val episodes = mutableListOf<FeedEpisode>()
    val text = StringBuilder()

    private var item: ItemDraft? = null
    private val stack = ArrayList<String>()
    private var inImage = false
    private var inAuthor = false
    private var showSummary: String? = null
    private var showContent: String? = null
    private var showAuthorFallback: String? = null
    private val categories = mutableListOf<String>()

    class ShowDraft {
        var title = ""
        var author: String? = null
        var descriptionHtml: String? = null
        var link: String? = null
        var imageUrl: String? = null
        var language: String? = null
        var isExplicit = false
        var isSerial = false
        var newFeedUrl: String? = null
        var isComplete = false
        var podcastGuid: String? = null
        val funding = mutableListOf<FeedLink>()
        var lastBuildDate: Instant? = null
    }

    /** An item while it is read; the first of `guid`, enclosure URL, link, title + date wins as its id. */
    class ItemDraft {
        var guid: String? = null
        var title = ""
        var link: String? = null
        var description: String? = null
        var content: String? = null
        var summary: String? = null
        var publishedAt: Instant? = null
        var updatedAt: Instant? = null
        var durationMs: Long? = null
        var enclosure: FeedEnclosure? = null
        var mediaContent: FeedEnclosure? = null
        var imageUrl: String? = null
        var season: Int? = null
        var episode: Int? = null
        var episodeType: String? = null
        var isExplicit = false
        var chaptersUrl: String? = null
        val chapters = mutableListOf<FeedChapter>()
        val transcripts = mutableListOf<FeedTranscript>()
        val persons = mutableListOf<FeedPerson>()
        var personAttributes: Map<String, String> = emptyMap()
    }

    fun document(): FeedDocument {
        val html = showContent ?: show.descriptionHtml ?: showSummary
        return FeedDocument(
            show = FeedShow(
                title = show.title.ifEmpty { FeedValues.host(show.link) ?: "" },
                author = show.author ?: showAuthorFallback,
                description = html?.let { Mojibake.repair(HtmlText.plain(it)) }?.ifEmpty { null },
                descriptionHtml = html,
                link = show.link,
                imageUrl = show.imageUrl,
                language = show.language,
                categories = categories.toList(),
                isExplicit = show.isExplicit,
                isSerial = show.isSerial,
                newFeedUrl = show.newFeedUrl,
                isComplete = show.isComplete,
                podcastGuid = show.podcastGuid,
                funding = show.funding.toList(),
                lastBuildDate = show.lastBuildDate,
            ),
            episodes = episodes.toList(),
        )
    }

    fun start(parser: XmlPullParser) {
        text.setLength(0)
        val name = parser.name
        val uri: String? = parser.namespace
        stack.add(name)
        if (kind == null) {
            when {
                name == "rss" || name == "RDF" -> kind = Kind.RSS
                name == "feed" && Namespace.isAtom(uri) -> kind = Kind.ATOM
                else -> rejectedRoot = true
            }
            return
        }
        val attributes = HashMap<String, String>()
        for (index in 0 until parser.attributeCount) {
            val namespace = parser.getAttributeNamespace(index)
            val key = if (namespace.isNullOrEmpty()) parser.getAttributeName(index) else "${parser.getAttributePrefix(index)}:${parser.getAttributeName(index)}"
            attributes[key] = parser.getAttributeValue(index)
        }
        val item = item

        if ((name == "item" && Namespace.isPlain(uri)) || (name == "entry" && Namespace.isAtom(uri))) {
            this.item = ItemDraft()
            return
        }
        if (name == "image" && Namespace.isPlain(uri) && item == null) inImage = true
        if (name == "author" && Namespace.isAtom(uri)) inAuthor = true

        when {
            Namespace.isITunes(uri) -> when (name) {
                "image" -> FeedValues.url(attributes["href"])?.let { url ->
                    if (item != null) item.imageUrl = url else show.imageUrl = url
                }
                "category" -> if (item == null) {
                    val category = attributes["text"]
                    if (!category.isNullOrEmpty() && category !in categories) categories.add(HtmlText.decodeEntities(category))
                }
            }
            Namespace.isPodcast(uri) -> when (name) {
                "chapters" -> item?.chaptersUrl = FeedValues.url(attributes["url"])
                "transcript" -> FeedValues.url(attributes["url"])?.let { url ->
                    item?.transcripts?.add(FeedTranscript(url, attributes["type"] ?: "text/plain", attributes["language"]))
                }
                "person" -> item?.personAttributes = attributes
                "funding" -> if (item == null) FeedValues.url(attributes["url"])?.let { show.funding.add(FeedLink(it, null)) }
            }
            Namespace.isPsc(uri) && name == "chapter" -> FeedValues.timeMs(attributes["start"])?.let { start ->
                item?.chapters?.add(
                    FeedChapter(
                        startMs = start,
                        title = Mojibake.repair(HtmlText.decodeEntities(attributes["title"] ?: "")),
                        url = FeedValues.url(attributes["href"]),
                        imageUrl = FeedValues.url(attributes["image"]),
                    ),
                )
            }
            Namespace.isMedia(uri) && name == "content" && item != null -> {
                val url = FeedValues.url(attributes["url"])
                if (url != null && item.mediaContent == null) {
                    item.mediaContent = FeedEnclosure(url, attributes["type"], length(attributes["fileSize"]))
                }
            }
            Namespace.isPlain(uri) && name == "enclosure" && item != null -> {
                val url = FeedValues.url(attributes["url"])
                if (url != null && item.enclosure == null) {
                    item.enclosure = FeedEnclosure(url, attributes["type"], length(attributes["length"]))
                }
            }
            Namespace.isAtom(uri) && name == "link" -> {
                val rel = attributes["rel"] ?: "alternate"
                val href = FeedValues.url(attributes["href"])
                if (item != null) {
                    if (rel == "enclosure" && href != null && item.enclosure == null) {
                        item.enclosure = FeedEnclosure(href, attributes["type"], length(attributes["length"]))
                    } else if (rel == "alternate" && item.link == null) {
                        item.link = href
                    }
                } else if (kind == Kind.ATOM && rel == "alternate" && show.link == null) {
                    show.link = href
                }
            }
        }
    }

    fun end(name: String, uri: String?) {
        val value = text.toString().trim()
        try {
            if ((name == "item" && Namespace.isPlain(uri)) || (name == "entry" && Namespace.isAtom(uri))) {
                item?.let { episodes.add(finish(it)) }
                item = null
                return
            }
            if (name == "image" && Namespace.isPlain(uri) && item == null) {
                inImage = false
                return
            }
            if (name == "author" && Namespace.isAtom(uri)) {
                inAuthor = false
                return
            }
            val item = item
            if (item != null) endItemElement(item, name, uri, value) else endShowElement(name, uri, value)
        } finally {
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            text.setLength(0)
        }
    }

    private val parent: String? get() = if (stack.size >= 2) stack[stack.size - 2] else null

    private fun endShowElement(name: String, uri: String?, value: String) {
        if (inImage) {
            if (name == "url" && show.imageUrl == null) show.imageUrl = FeedValues.url(value)
            return
        }
        if (inAuthor) {
            if (name == "name" && show.author == null) show.author = clean(value)
            return
        }
        when {
            Namespace.isITunes(uri) -> when (name) {
                "author" -> show.author = clean(value)
                "summary" -> showSummary = value
                "explicit" -> show.isExplicit = FeedValues.flag(value)
                "type" -> show.isSerial = value.lowercase() == "serial"
                "new-feed-url" -> show.newFeedUrl = FeedValues.url(value)
                "complete" -> show.isComplete = FeedValues.flag(value)
                "name" -> if (parent == "owner") showAuthorFallback = showAuthorFallback ?: clean(value)
            }
            Namespace.isPodcast(uri) -> when (name) {
                "guid" -> show.podcastGuid = value.ifEmpty { null }
                "funding" -> if (value.isNotEmpty() && show.funding.isNotEmpty()) {
                    show.funding[show.funding.size - 1] = show.funding.last().copy(title = clean(value))
                }
            }
            Namespace.isContent(uri) && name == "encoded" -> showContent = value
            Namespace.isPlain(uri) || Namespace.isAtom(uri) -> {
                // Only the channel's (or Atom feed's) own children, not those of nested elements.
                val parent = parent
                if (parent != "channel" && parent != "feed") return
                when (name) {
                    "title" -> show.title = clean(value) ?: ""
                    "link" -> if (Namespace.isPlain(uri)) show.link = FeedValues.url(value)
                    "description", "subtitle" -> show.descriptionHtml = value.ifEmpty { null }
                    "language" -> show.language = value.ifEmpty { null }
                    "lastBuildDate", "updated" -> show.lastBuildDate = FeedValues.date(value) ?: show.lastBuildDate
                    "pubDate" -> show.lastBuildDate = show.lastBuildDate ?: FeedValues.date(value)
                    "author", "managingEditor" -> showAuthorFallback = showAuthorFallback ?: clean(value)
                    "icon", "logo" -> show.imageUrl = show.imageUrl ?: FeedValues.url(value)
                }
            }
        }
    }

    private fun endItemElement(item: ItemDraft, name: String, uri: String?, value: String) {
        when {
            Namespace.isITunes(uri) -> when (name) {
                "duration" -> item.durationMs = FeedValues.durationMs(value)
                "summary" -> item.summary = value
                "season" -> item.season = FeedValues.int(value)
                "episode" -> item.episode = FeedValues.int(value)
                "episodeType" -> item.episodeType = value.lowercase()
                "explicit" -> item.isExplicit = FeedValues.flag(value)
                "title" -> if (item.title.isEmpty()) item.title = clean(value) ?: ""
            }
            Namespace.isPodcast(uri) -> if (name == "person" && value.isNotEmpty()) {
                val attributes = item.personAttributes
                item.persons.add(
                    FeedPerson(
                        name = clean(value) ?: value,
                        role = attributes["role"]?.lowercase() ?: "host",
                        imageUrl = FeedValues.url(attributes["img"]),
                        link = FeedValues.url(attributes["href"]),
                    ),
                )
                item.personAttributes = emptyMap()
            }
            Namespace.isContent(uri) && name == "encoded" -> item.content = value
            Namespace.isPlain(uri) || Namespace.isAtom(uri) -> when (name) {
                "title" -> item.title = clean(value) ?: ""
                "link" -> if (Namespace.isPlain(uri)) item.link = FeedValues.url(value)
                "guid", "id" -> item.guid = value.ifEmpty { null }
                "description", "summary" -> item.description = value
                "content" -> if (Namespace.isAtom(uri)) item.content = value
                "pubDate", "published", "date" -> item.publishedAt = FeedValues.date(value)
                "updated" -> item.updatedAt = FeedValues.date(value)
            }
        }
    }

    private fun finish(draft: ItemDraft): FeedEpisode {
        val enclosure = draft.enclosure ?: draft.mediaContent
        val id = draft.guid ?: enclosure?.url ?: draft.link
            ?: "${draft.title}|${secondsText(draft.publishedAt)}"
        val html = draft.content ?: draft.description ?: draft.summary
        return FeedEpisode(
            id = id,
            title = draft.title,
            description = html?.let { Mojibake.repair(HtmlText.plain(it)) }?.ifEmpty { null },
            descriptionHtml = html,
            publishedAt = draft.publishedAt ?: draft.updatedAt,
            durationMs = draft.durationMs,
            enclosure = enclosure,
            link = draft.link,
            imageUrl = draft.imageUrl,
            season = draft.season,
            episode = draft.episode,
            episodeType = draft.episodeType,
            isExplicit = draft.isExplicit,
            chaptersUrl = draft.chaptersUrl,
            inlineChapters = Chapters.closed(draft.chapters),
            transcripts = draft.transcripts.toList(),
            persons = draft.persons.toList(),
        )
    }

    // The same form the iOS app writes ("1760000000.0"), so a feed without guids, enclosures or
    // links gives the same ids on both.
    private fun secondsText(date: Instant?): String {
        if (date == null) return "0.0"
        if (date.nano == 0) return "${date.epochSecond}.0"
        return java.math.BigDecimal.valueOf(date.epochSecond).add(java.math.BigDecimal.valueOf(date.nano.toLong(), 9)).stripTrailingZeros().toPlainString()
    }

    /** Bytes as feeds write them; 0 and junk are unknown. */
    private fun length(text: String?): Long? = text?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** Entities decoded, tags dropped, mis-decoded UTF-8 repaired; empty is null. */
    private fun clean(value: String): String? {
        val plain = if (value.contains('<')) HtmlText.plain(value) else HtmlText.decodeEntities(value)
        return Mojibake.repair(plain.trim()).ifEmpty { null }
    }
}
