package audio.own.kit.audiobookshelf

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

class AudiobookshelfClientTest {
    private val http = testHttp()

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName}, got $e", e)
        }
        fail("expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // ---- Before signing in

    @Test
    fun `status names the server, its version and its sign-in methods`() = runTest {
        Stub { request ->
            assertEquals("/status", request.url.encodedPath)
            assertEquals("own.audio Books/0.0.0-test (JVM)", request.headers["User-Agent"])
            json("""{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true,"language":"cs","authMethods":["local"],"authFormData":{"authLoginCustomMessage":""}}""")
        }.use { stub ->
            val s = AudiobookshelfClient.status(stub.host, http)
            assertEquals(ServerVersion("2.37.1"), s.version)
            assertTrue(s.version >= ServerVersion.ROTATING_TOKENS)
            assertEquals(listOf("local"), s.authMethods)
        }
    }

    @Test
    fun `a server that is not Audiobookshelf is refused`() = runTest {
        Stub { json("""{"app":"navidrome","serverVersion":"0.58"}""") }.use { stub ->
            assertThrows<AudiobookshelfError.Decoding> { AudiobookshelfClient.status(stub.host, http) }
        }
        Stub { json("""{"app":"navidrome"}""") }.use { stub ->
            assertThrows<AudiobookshelfError.Decoding> { AudiobookshelfClient.status(stub.host, http) }
        }
    }

    @Test
    fun `typed addresses - bare hosts get http, a scheme is kept, a slash is dropped`() {
        val bare = AudiobookshelfClient.urlFromTyped("nas.local:13378")!!
        assertEquals("http", bare.scheme)
        assertEquals("nas.local", bare.host)
        assertEquals(13378, bare.port)
        val full = AudiobookshelfClient.urlFromTyped(" https://books.example.com/ ")!!
        assertEquals("https://books.example.com/", full.toString())
        assertEquals("http://nas.local/abs", AudiobookshelfClient.urlFromTyped("nas.local/abs/").toString())
        assertNull(AudiobookshelfClient.urlFromTyped(""))
        assertNull(AudiobookshelfClient.urlFromTyped("   "))
    }

    @Test
    fun `sign-in on 2_26+ asks for tokens in the body, the JWT's exp becomes the expiry`() = runTest {
        val access = jwt(expiresInSeconds = 3600)
        Stub { request ->
            assertEquals("/login", request.url.encodedPath)
            assertEquals("true", request.headers["x-return-tokens"])
            assertEquals("booktest", request.bodyJson()["username"]!!.jsonPrimitive.content)
            assertEquals("pw", request.bodyJson()["password"]!!.jsonPrimitive.content)
            json(userJson(access))
        }.use { stub ->
            val s = AudiobookshelfClient.signIn(stub.host, "booktest", "pw", http)
            assertEquals(access, s.tokens.accessToken)
            assertEquals("r1", s.tokens.refreshToken)
            assertTrue(abs(s.tokens.expiresAtMillis - System.currentTimeMillis() - 3_600_000) < 5_000)
            assertEquals(120.25, s.user.mediaProgress.first().currentTime, 0.0)
            assertEquals(90.0, s.user.bookmarks.first().time, 0.0)
            assertEquals("lib1", s.defaultLibraryId)
            assertEquals(ServerVersion("2.37.1"), s.serverVersion)
            // The user record handed out carries no tokens.
            assertNull(s.user.accessToken)
            assertNull(s.user.refreshToken)
            assertFalse(s.toString().contains(access))
        }
    }

    @Test
    fun `sign-in before 2_26 - the one token never expires and there is nothing to refresh with`() = runTest {
        Stub { json(userJson(access = null, refresh = null, legacy = "legacy1")) }.use { stub ->
            val s = AudiobookshelfClient.signIn(stub.host, "booktest", "pw", http)
            assertEquals("legacy1", s.tokens.accessToken)
            assertNull(s.tokens.refreshToken)
            assertEquals(Tokens.NEVER, s.tokens.expiresAtMillis)
        }
    }

    @Test
    fun `a wrong password is InvalidCredentials`() = runTest {
        Stub { text("Unauthorized", 401) }.use { stub ->
            assertThrows<AudiobookshelfError.InvalidCredentials> { AudiobookshelfClient.signIn(stub.host, "booktest", "no", http) }
        }
    }

    @Test
    fun `a sign-in refused for too many attempts is RateLimited, not a wrong password`() = runTest {
        Stub { MockResponse429(retryAfter = "600") }.use { stub ->
            val e = assertThrows<AudiobookshelfError.RateLimited> { AudiobookshelfClient.signIn(stub.host, "booktest", "pw", http) }
            assertEquals(600_000L, e.retryAfterMillis)
            assertEquals(1, stub.requests.size)
        }
    }

    @Test
    fun `an error body's error text is kept`() = runTest {
        Stub { json("""{"error":"Library not found"}""", 404) }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val e = assertThrows<AudiobookshelfError.Http> { client.item("nope") }
            assertEquals(404, e.status)
            assertEquals("Library not found", e.serverMessage)
        }
    }

    // ---- Lists

    @Test
    fun `items - page, sort, direction and a URL-safe base64 filter`() = runTest {
        Stub {
            json("""{"results":[],"total":71,"limit":50,"page":1,"sortBy":"media.metadata.title","sortDesc":true,"mediaType":"book","minified":false,"collapseseries":false}""")
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val page = client.items("lib1", page = 1, limit = 50, sort = ItemSort.TITLE, descending = true, filter = ItemFilter.ByProgress(ItemFilter.ProgressState.IN_PROGRESS))
            assertEquals(71, page.total)
            assertFalse(page.hasMore)
            val url = stub.requests.first().url
            assertEquals("/api/libraries/lib1/items", url.encodedPath)
            assertEquals("1", url.queryParameter("page"))
            assertEquals("50", url.queryParameter("limit"))
            assertEquals("media.metadata.title", url.queryParameter("sort"))
            assertEquals("1", url.queryParameter("desc"))
            assertEquals("progress.aW4tcHJvZ3Jlc3M", url.queryParameter("filter"))
            assertNull(url.queryParameter("collapseseries"))
            assertEquals("Bearer t", stub.requests.first().headers["Authorization"])
            assertEquals("series.OGI1ZTU4YWY", ItemFilter.SeriesId("8b5e58af").value)
        }
    }

    @Test
    fun `allItems follows pages until the total is reached`() = runTest {
        Stub { request ->
            val page = request.url.queryParameter("page")!!.toInt()
            val row = """{"id":"li$page","libraryId":"lib1","media":{"metadata":{"title":"T$page"},"duration":1}}"""
            json("""{"results":[$row],"total":3,"limit":1,"page":$page}""")
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            assertEquals(listOf("li0", "li1", "li2"), client.allItems("lib1", pageSize = 1).map { it.id })
        }
    }

    @Test
    fun `a list row decodes with its strings, a detail with chapters, tracks, authors and series`() {
        val row = """{"id":"li1","ino":"1234","libraryId":"lib1","folderId":"f","path":"/books/x","relPath":"x","isFile":false,"mtimeMs":1,"ctimeMs":1,"birthtimeMs":1,"addedAt":1792000000000,"updatedAt":1792000000000,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"id":"b1","metadata":{"title":"1984","titleIgnorePrefix":"1984","subtitle":null,"authorName":"George Orwell","authorNameLF":"Orwell, George","narratorName":"","seriesName":"","genres":["Fiction"],"publishedYear":"1949","publishedDate":null,"publisher":null,"description":"d","isbn":null,"asin":null,"language":null,"explicit":false,"abridged":false},"coverPath":"/c.jpg","tags":[],"numTracks":10,"numAudioFiles":10,"numChapters":10,"duration":3780.85,"size":97160062},"numFiles":11,"size":97160062}"""
        val item = AbsJson.decodeFromString(LibraryItem.serializer(), row)
        assertEquals("George Orwell", item.media.metadata.authorName)
        assertEquals("1949", item.media.metadata.publishedYear)
        assertNull(item.media.chapters)

        val detail = """{"id":"li1","ino":1234,"libraryId":"lib1","addedAt":1,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"id":"b1","metadata":{"title":"Ender","authors":[{"id":"a1","name":"Orson Scott Card"}],"narrators":["Stefan Rudnicki"],"series":[{"id":"s1","name":"Ender's Game Series","sequence":"1.5"}],"genres":[],"explicit":false,"abridged":false},"coverPath":null,"tags":[],"duration":100.5,"chapters":[{"id":0,"start":0,"end":50.25,"title":"One"},{"id":1,"start":50.25,"end":100.5,"title":"Two"}],"audioFiles":[{"index":1,"ino":17088,"duration":100.5,"mimeType":"audio/mpeg","metadata":{"filename":"a.mp3","ext":".mp3","size":1000}}],"tracks":[{"index":1,"startOffset":0,"duration":100.5,"title":"a.mp3","contentUrl":"/api/items/li1/file/17088","mimeType":"audio/mpeg","ino":17088,"codec":"mp3","bitRate":128000,"metadata":{"ext":".mp3","size":1000}}]},"userMediaProgress":{"id":"p1","userId":"u1","libraryItemId":"li1","episodeId":null,"duration":100.5,"progress":0.5,"currentTime":50.25,"isFinished":false,"hideFromContinueListening":false,"lastUpdate":1,"startedAt":1,"finishedAt":null}}"""
        val book = AbsJson.decodeFromString(LibraryItem.serializer(), detail)
        assertEquals("1234", book.ino)
        assertEquals(listOf("One", "Two"), book.media.chapters?.map { it.title })
        assertEquals("17088", book.media.tracks?.first()?.ino)
        assertEquals("17088", book.media.audioFiles?.first()?.ino)
        assertEquals("/api/items/li1/file/17088", book.media.tracks?.first()?.contentUrl)
        assertEquals("1.5", book.media.metadata.series?.first()?.sequence)
        assertEquals(listOf("Stefan Rudnicki"), book.media.metadata.narrators)
        assertEquals(50.25, book.userMediaProgress!!.currentTime, 0.0)

        // Filtered by a series, a row carries that series as one object.
        val bySeries = """{"id":"li2","libraryId":"lib1","addedAt":1,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"metadata":{"title":"Speaker","series":{"id":"s1","name":"Ender's Game Series","sequence":"2"},"genres":[],"explicit":false,"abridged":false},"coverPath":null,"tags":[],"duration":1},"sequence":"2"}"""
        val second = AbsJson.decodeFromString(LibraryItem.serializer(), bySeries)
        assertEquals(listOf("2"), second.media.metadata.series?.map { it.sequence })
        assertEquals("2", second.sequence)
    }

    @Test
    fun `shelves decode each by their type, search answers its sections`() {
        val shelves = """[{"id":"continue-listening","label":"Continue Listening","type":"book","entities":[]},{"id":"newest-authors","label":"Newest Authors","type":"authors","entities":[{"id":"a1","name":"X","numBooks":2}]},{"id":"continue-series","label":"Continue Series","type":"series","entities":[]},{"id":"newest-episodes","label":"Newest Episodes","type":"episode","entities":[{"odd":true}]},{"id":"broken","type":"book","entities":[{"no":"id"}]}]"""
        val decoded = AbsJson.decodeFromString(ListSerializer(Shelf.serializer()), shelves)
        assertEquals(listOf("continue-listening", "newest-authors", "continue-series", "newest-episodes", "broken"), decoded.map { it.id })
        assertEquals("X", decoded[1].authors.first().name)
        assertTrue(decoded[4].books.isEmpty())
        val search = """{"book":[],"narrators":[{"name":"N","numBooks":1}],"tags":[],"genres":[],"series":[],"authors":[]}"""
        val s = AbsJson.decodeFromString(SearchAnswer.serializer(), search)
        assertEquals("N", s.narrators.first().name)
        val oddSeries = """{"book":[],"series":[{"odd":1}],"authors":[{"id":"a1","name":"A"}]}"""
        val o = AbsJson.decodeFromString(SearchAnswer.serializer(), oddSeries)
        assertTrue(o.series.isEmpty())
        assertEquals("A", o.authors.single().name)
    }

    // ---- Progress, bookmarks, sessions

    @Test
    fun `progress goes as a PATCH with the fraction worked out, a 404 read is null, delete uses the progress id`() = runTest {
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "PATCH" to "/api/me/progress/li1" -> {
                    val b = request.bodyJson()
                    assertEquals(120.25, b["currentTime"]!!.jsonPrimitive.double, 0.0)
                    assertFalse(b["isFinished"]!!.jsonPrimitive.boolean)
                    assertEquals(120.25 / 3780.85, b["progress"]!!.jsonPrimitive.double, 0.0001)
                    assertFalse("absent optionals are left out", b.containsKey("hideFromContinueListening"))
                    text("OK")
                }
                "GET" to "/api/me/progress/li1" -> text("Not Found", 404)
                "DELETE" to "/api/me/progress/p1" -> text("OK")
                "PATCH" to "/api/me/progress/batch/update" -> {
                    val rows = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8()).jsonArray
                    assertEquals("li1", rows.single().jsonObject["libraryItemId"]!!.jsonPrimitive.content)
                    text("OK")
                }
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            client.updateProgress("li1", ProgressUpdate.of(currentTime = 120.25, duration = 3780.85, isFinished = false))
            assertNull(client.progress("li1"))
            client.deleteProgress("p1")
            client.updateProgress(listOf("li1" to ProgressUpdate.of(1.0, 2.0, true)))
            assertEquals(
                listOf("/api/me/progress/li1", "/api/me/progress/li1", "/api/me/progress/p1", "/api/me/progress/batch/update"),
                stub.paths,
            )
        }
    }

    @Test
    fun `bookmarks - create and rename by time, delete by time in the path`() = runTest {
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "POST" to "/api/me/item/li1/bookmark", "PATCH" to "/api/me/item/li1/bookmark" -> {
                    val b = request.bodyJson()
                    json("""{"libraryItemId":"li1","time":${b["time"]},"title":${b["title"]},"createdAt":1}""")
                }
                "DELETE" to "/api/me/item/li1/bookmark/90" -> text("OK")
                "DELETE" to "/api/me/item/li1/bookmark/90.5" -> text("OK")
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            assertEquals("probe", client.createBookmark("li1", 90.0, "probe").title)
            assertEquals("renamed", client.updateBookmark("li1", 90.0, "renamed").title)
            client.deleteBookmark("li1", 90.0)
            client.deleteBookmark("li1", 90.5)
        }
    }

    @Test
    fun `a play session names the device and what plays, sync and close report seconds, local sessions go in one batch`() = runTest {
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "POST" to "/api/items/li1/play" -> {
                    val b = request.bodyJson()
                    assertEquals("own.audio Books", b["deviceInfo"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
                    assertEquals("Google", b["deviceInfo"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
                    assertEquals("ExoPlayer", b["mediaPlayer"]!!.jsonPrimitive.content)
                    assertFalse(b["forceDirectPlay"]!!.jsonPrimitive.boolean)
                    assertFalse(b["forceTranscode"]!!.jsonPrimitive.boolean)
                    assertTrue(b["supportedMimeTypes"]!!.jsonArray.any { it.jsonPrimitive.content == "audio/mpeg" })
                    json("""{"id":"s1","userId":"u1","libraryId":"lib1","libraryItemId":"li1","episodeId":null,"mediaType":"book","chapters":[],"displayTitle":"T","displayAuthor":"A","duration":3780.85,"playMethod":0,"mediaPlayer":"ExoPlayer","date":"2026-10-15","dayOfWeek":"Thursday","timeListening":0,"startTime":61.5,"currentTime":61.5,"startedAt":1,"updatedAt":1,"audioTracks":[{"index":1,"startOffset":0,"duration":86.9,"title":"01.mp3","contentUrl":"/api/items/li1/file/17088","mimeType":"audio/mpeg"}]}""")
                }
                "POST" to "/api/session/s1/sync" -> {
                    val b = request.bodyJson()
                    assertEquals(70.0, b["currentTime"]!!.jsonPrimitive.double, 0.0)
                    assertEquals(8.5, b["timeListened"]!!.jsonPrimitive.double, 0.0)
                    assertEquals(3780.85, b["duration"]!!.jsonPrimitive.double, 0.0)
                    text("OK")
                }
                "POST" to "/api/session/s1/close" -> text("OK")
                "POST" to "/api/session/local-all" -> {
                    val b = request.bodyJson()
                    val session = b["sessions"]!!.jsonArray.first().jsonObject
                    assertEquals(3, session["playMethod"]!!.jsonPrimitive.int)
                    assertEquals("2026-10-15", session["date"]!!.jsonPrimitive.content)
                    assertEquals("Thursday", session["dayOfWeek"]!!.jsonPrimitive.content)
                    assertEquals("book", session["mediaType"]!!.jsonPrimitive.content)
                    assertEquals("d1", b["deviceInfo"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
                    json("""{"results":[{"id":"local_1","success":true,"progressSynced":true}]}""")
                }
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val device = DeviceInfo(deviceId = "d1", clientName = "own.audio Books", clientVersion = "0.1.0", manufacturer = "Google", model = "Pixel 8", sdkVersion = "35")
            val s = client.play("li1", device)
            assertEquals(61.5, s.startTime, 0.0)
            assertFalse(s.isHls)
            assertEquals(1, s.audioTracks.size)
            assertEquals(stub.host.resolve("/api/items/li1/file/17088?token=t"), client.mediaUrl(s.audioTracks[0].contentUrl, "t"))
            client.syncSession("s1", currentTime = 70.0, timeListened = 8.5, duration = s.duration)
            client.closeSession("s1", currentTime = 70.0, timeListened = 0.0, duration = s.duration)
            val zone = ZoneId.of("Europe/Prague")
            val startedAt = LocalDateTime.of(2026, 10, 15, 12, 0).atZone(zone).toInstant()
            val local = LocalSession.of(
                id = "local_1", userId = "u1", libraryId = "lib1", libraryItemId = "li1", displayTitle = "T", displayAuthor = "A",
                duration = s.duration, deviceInfo = device, timeListening = 20.0, startTime = 130.0, currentTime = 150.0,
                startedAt = startedAt, updatedAt = startedAt.plusSeconds(20), zone = zone,
            )
            val results = client.sendLocalSessions(listOf(local), device)
            assertEquals(true, results.first().progressSynced)
        }
    }

    @Test
    fun `a sync whose session is gone is a 404 the caller can tell apart`() = runTest {
        Stub { text("Not Found", 404) }.use { stub ->
            val client = AudiobookshelfClient(stub.host, legacyTokens(), http)
            val e = assertThrows<AudiobookshelfError.Http> { client.syncSession("gone", 1.0, 1.0, 2.0) }
            assertEquals(404, e.status)
        }
    }

    // ---- Cache and URLs

    @Test
    fun `library reads are kept and answer offline, search and me are not`() {
        assertTrue(AudiobookshelfClient.keeps("api/libraries"))
        assertTrue(AudiobookshelfClient.keeps("api/libraries/lib1/items"))
        assertTrue(AudiobookshelfClient.keeps("api/items/li1"))
        assertFalse(AudiobookshelfClient.keeps("api/libraries/lib1/search"))
        assertFalse(AudiobookshelfClient.keeps("api/me"))
        assertFalse(AudiobookshelfClient.keeps("api/authorize"))
        assertFalse(AudiobookshelfClient.keeps("api/session/s1/sync"))
    }

    @Test
    fun `a kept answer serves offline and when the network fails, with no token in its key`() = runTest {
        val kept = HashMap<String, ByteArray>()
        val cache = object : AnswerCache {
            override fun data(key: String) = kept[key]
            override fun store(data: ByteArray, key: String) {
                kept[key] = data
            }
        }
        var offline = false
        val stub = Stub { json("""{"libraries":[{"id":"lib1","name":"Books","mediaType":"book"}]}""") }
        val host = stub.host
        val client = AudiobookshelfClient(host, legacyTokens("secret-token"), http, cache, cacheIdentity = "server-1", isOffline = { offline })
        assertEquals(listOf("lib1"), client.bookLibraries().map { it.id })
        assertEquals(setOf("server-1|api/libraries?"), kept.keys)
        offline = true
        assertEquals(listOf("lib1"), client.bookLibraries().map { it.id })
        assertThrows<AudiobookshelfError.Offline> { client.me() }
        offline = false
        stub.close()
        assertEquals(listOf("lib1"), client.bookLibraries().map { it.id })
        assertThrows<AudiobookshelfError.Network> { client.me() }
        assertFalse(kept.keys.any { it.contains("secret-token") })
    }

    @Test
    fun `file and cover URLs carry the token in the query, the cover its width`() {
        val host = AudiobookshelfClient.urlFromTyped("books.example.com:13378")!!
        val client = AudiobookshelfClient(host, TokenSession(null), http)
        assertEquals("http://books.example.com:13378/api/items/li1/file/17088?token=tok", client.fileUrl("li1", "17088", "tok").toString())
        assertEquals("http://books.example.com:13378/api/items/li1/cover?width=300&format=jpeg", client.coverUrl("li1", 300).toString())
        assertEquals("http://books.example.com:13378/api/items/li1/cover?width=600&format=jpeg&token=tok", client.coverUrl("li1", token = "tok").toString())
        assertEquals("http://books.example.com:13378/hls/s1/output.m3u8?token=tok", client.mediaUrl("/hls/s1/output.m3u8", "tok").toString())
        // A server behind a reverse proxy at a sub-path keeps it.
        val proxied = AudiobookshelfClient(AudiobookshelfClient.urlFromTyped("https://example.com/abs")!!, TokenSession(null), http)
        assertEquals("https://example.com/abs/api/items/li1/file/1?token=t", proxied.fileUrl("li1", "1", "t").toString())
        assertEquals("https://example.com/abs/api/items/li1/file/1?token=t", proxied.mediaUrl("/api/items/li1/file/1", "t").toString())
        assertEquals("https://example.com/abs/login", proxied.host.under("login").toString())
    }

    @Test
    fun `versions compare by part`() {
        assertTrue(ServerVersion("2.37.1") > ServerVersion("2.26.0"))
        assertTrue(ServerVersion("2.9.0") < ServerVersion("2.26"))
        assertEquals(ServerVersion("2.26"), ServerVersion("2.26.0"))
        assertEquals(ServerVersion("2.26").hashCode(), ServerVersion("2.26.0").hashCode())
        assertEquals("2.38.0", ServerVersion("2.38.0-beta").toString())
    }
}

@Suppress("TestFunctionName")
internal fun MockResponse429(retryAfter: String? = null): mockwebserver3.MockResponse =
    mockwebserver3.MockResponse.Builder().code(429).body("Too many requests").apply {
        retryAfter?.let { addHeader("Retry-After", it) }
    }.build()
