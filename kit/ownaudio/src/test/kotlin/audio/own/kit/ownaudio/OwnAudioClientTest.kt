package audio.own.kit.ownaudio

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class OwnAudioClientTest {

    // region Before signing in

    @Test
    fun `discovery reads the v2 API, modules and the revision gate`() = runBlocking {
        StubServer { json(answer("discovery")) }.use { stub ->
            val d = stub.client(tokens = null).discover()
            assertEquals("/.well-known/own-audio", stub.requests.single().url.encodedPath)
            assertEquals("/api/v2", d.v2?.path)
            assertEquals(500, d.limits.listLimitMax)
            assertTrue(d.has("identify_library"))
            assertTrue("a module with settings counts as on", d.has("auth"))
            assertFalse(d.has("billing"))
            assertFalse("a missing module is off", d.has("no_such_module"))
            assertEquals(false, d.flags["narration"])
            assertTrue(d.requireV2() >= 16)
            assertEquals(listOf("public", "lan"), d.addresses.map { it.scope })
            assertNull(d.demo)
        }
    }

    @Test
    fun `an old server is too old`() {
        val old = OwnAudioJson.decodeFromString(
            Discovery.serializer(),
            """{"name":"x","edition":"core","version":"1.0.0-beta.7","api":[{"version":1,"path":"/api/v1","revision":8,"status":"stable"},{"version":2,"path":"/api/v2","revision":15,"status":"draft"}],"auth":{"password":true,"providers":[]},"modules":{},"addresses":[],"limits":{"list_limit_default":100,"list_limit_max":500},"setup":{"complete":true}}""",
        )
        try {
            old.requireV2()
            fail("expected TooOld")
        } catch (e: OwnAudioException.TooOld) {
            assertEquals(15, e.revision)
            assertEquals("This own.audio server is too old for this app. Update it to 1.0.4 or later.", e.message)
        }
        val noV2 = old.copy(api = old.api.filter { it.version == 1 })
        try {
            noV2.requireV2()
            fail("expected TooOld")
        } catch (e: OwnAudioException.TooOld) {
            assertNull(e.revision)
        }
    }

    @Test
    fun `password sign-in is a form with sorted keys, answered with tokens`() = runBlocking {
        StubServer { json(tokenJson()) }.use { stub ->
            val tokens = stub.client(tokens = null).signIn("a@example.com", "p w&", OwnAudioClient.Device("Pixel 9"))
            val sent = stub.requests.single()
            assertEquals("/api/v2/auth/token", sent.url.encodedPath)
            assertEquals("application/x-www-form-urlencoded", sent.headers["Content-Type"]?.substringBefore(';'))
            val fields = sent.form()
            assertEquals("password", fields["grant_type"])
            assertEquals("a@example.com", fields["username"])
            assertEquals("p w&", fields["password"])
            assertEquals("android", fields["device_kind"])
            assertEquals("Pixel 9", fields["device_name"])
            val keys = sent.text().split("&").map { it.substringBefore('=') }
            assertEquals(keys.sorted(), keys)
            assertEquals("a1", tokens.accessToken)
            assertTrue(Duration.between(Instant.now(), tokens.expiresAt).seconds in 3590..3600)
        }
    }

    @Test
    fun `two-factor - the first step answers mfa_required with the token`() = runBlocking {
        StubServer {
            problem("mfa_required", 403, ""","error":"mfa_required","mfa_token":"m1","mfa_methods":["totp","recovery"]""")
        }.use { stub ->
            try {
                stub.client(tokens = null).signIn("a", "b", OwnAudioClient.Device("Phone"))
                fail("expected a problem")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("mfa_required", p.code)
                assertEquals("m1", p.mfaToken)
                assertEquals(listOf("totp", "recovery"), p.mfaMethods)
            }
        }
        StubServer { json(tokenJson("a2", "r2")) }.use { stub ->
            stub.client(tokens = null).finishTwoFactor("m1", "123456")
            val fields = stub.requests.single().form()
            assertEquals(Grants.MFA_OTP, fields["grant_type"])
            assertEquals("m1", fields["mfa_token"])
            assertEquals("123456", fields["otp"])
        }
    }

    @Test
    fun `a wrong password is invalid_grant`() = runBlocking {
        StubServer { problem("invalid_grant", 400, ""","error":"invalid_grant"""") }.use { stub ->
            try {
                stub.client(tokens = null).signIn("a", "b", OwnAudioClient.Device("Phone"))
                fail("expected a problem")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("invalid_grant", p.code)
                assertEquals(400, p.status)
            }
        }
    }

    @Test
    fun `google sign-in sends the id token and the invite code`() = runBlocking {
        StubServer { json(tokenJson()) }.use { stub ->
            stub.client(tokens = null).signIn(provider = "google", idToken = "jwt", device = OwnAudioClient.Device("Pixel"), inviteCode = "ABC")
            val fields = stub.requests.single().form()
            assertEquals(Grants.JWT_BEARER, fields["grant_type"])
            assertEquals("google", fields["provider"])
            assertEquals("jwt", fields["assertion"])
            assertEquals("ABC", fields["invite_code"])
            assertFalse("what is not set is left out", "full_name" in fields)
        }
    }

    @Test
    fun `register is v2 only and answers the account`() = runBlocking {
        StubServer { json(ME, 201) }.use { stub ->
            val me = stub.client(tokens = null).register("a@example.com", "secret", "A")
            assertEquals("u1", me.id)
            assertEquals(listOf("POST /api/v2/auth/register"), stub.paths())
            assertEquals("""{"display_name":"A","email":"a@example.com","password":"secret"}""", stub.requests.single().text())
        }
    }

    // endregion

    // region Transport

    @Test
    fun `every request carries the user agent and the language`() = runBlocking {
        StubServer { if (it.url.encodedPath.endsWith("/token")) json(tokenJson()) else json(ME) }.use { stub ->
            runCatching { stub.client(tokens = null).discover() }
            stub.client(tokens = null).signIn("a", "b", OwnAudioClient.Device("x"))
            stub.client().me()
            assertEquals(3, stub.requests.size)
            stub.requests.forEach {
                assertEquals(StubServer.USER_AGENT, it.headers["User-Agent"])
                assertEquals("cs-CZ", it.headers["Accept-Language"])
            }
            assertEquals("Bearer a", stub.requests.last().headers["Authorization"])
        }
    }

    @Test
    fun `problem details decode with field errors, the feature and Retry-After`() = runBlocking {
        StubServer {
            when (it.url.encodedPath) {
                "/api/v2/family/billing" -> problem("feature_unavailable", 501, ""","detail":"this server does not offer billing","feature":"billing"""")
                "/api/v2/playlists" -> problem("invalid_body", 400, ""","errors":[{"field":"name","code":"too_long","detail":"at most 200"}]""")
                "/api/v2/me" -> mockwebserver3.MockResponse.Builder().code(429).addHeader("Retry-After", "30").addHeader("Content-Type", "application/problem+json")
                    .body("""{"type":"x","title":"Too Many Requests","status":429,"code":"rate_limited"}""").build()
                else -> mockwebserver3.MockResponse.Builder().code(502).body("<html>bad gateway</html>").build()
            }
        }.use { stub ->
            val client = stub.client()
            val billing = runCatching { client.familyBilling() }.exceptionOrNull() as OwnAudioException.Problem
            assertEquals(501, billing.status)
            assertEquals("feature_unavailable", billing.code)
            assertEquals("billing", billing.feature)
            val invalid = runCatching { client.createPlaylist("x") }.exceptionOrNull() as OwnAudioException.Problem
            assertEquals(listOf(FieldError("name", "too_long", "at most 200")), invalid.errors)
            val limited = runCatching { client.me() }.exceptionOrNull() as OwnAudioException.Problem
            assertEquals(30L, limited.retryAfterSeconds)
            val proxy = runCatching { client.genres() }.exceptionOrNull()
            assertTrue(proxy is OwnAudioException.Http && proxy.status == 502)
        }
    }

    @Test
    fun `an unreachable server is a network error`() = runBlocking {
        val stub = StubServer { json("{}") }
        val client = stub.client()
        stub.close()
        val error = runCatching { client.genres() }.exceptionOrNull()
        assertTrue("$error", error is OwnAudioException.Network)
    }

    @Test
    fun `reads are kept and answer offline, offline without a kept answer fails fast`() = runBlocking {
        var offline = false
        val cache = MapCache()
        StubServer { json("""{"items":[{"id":"g1","name":"Jazz"}]}""") }.use { stub ->
            val client = stub.client(cache = cache, isOffline = { offline })
            assertEquals("Jazz", client.genres().items.single().name)
            offline = true
            assertEquals("Jazz", client.genres().items.single().name)
            assertEquals(1, stub.requests.size)
            try {
                client.search("x")
                fail("expected Offline")
            } catch (_: OwnAudioException.Offline) {
            }
            assertEquals(1, stub.requests.size)
            assertTrue(cache.map.keys.single().startsWith("server-1|api/v2/genres?"))
            assertFalse("tokens never end up in a key", cache.map.keys.single().contains("Bearer"))
        }
        assertTrue(OwnAudioClient.keeps("albums/x"))
        assertFalse(OwnAudioClient.keeps("tracks/random"))
        assertFalse(OwnAudioClient.keeps("me"))
        assertFalse(OwnAudioClient.keeps("search"))
        assertFalse(OwnAudioClient.keeps("podcasts/directory/search"))
    }

    @Test
    fun `query parameters are sorted and nulls left out`() {
        val client = OwnAudioClient("https://music.example.com/", okhttp3.OkHttpClient(), "ua")
        val url = client.url("albums", Query("sort" to "title", "q" to null, "cursor" to "c").add("include", listOf("contributors", "genres")))
        assertEquals("https://music.example.com/api/v2/albums?cursor=c&include=contributors%2Cgenres&sort=title", url.toString())
    }

    // endregion

    // region Canonical JSON

    @Test
    fun `equal bodies give the same bytes, keys sorted at every depth`() {
        val a = Json.parseToJsonElement("""{"b":1,"a":{"z":[{"y":1,"x":2}],"c":null},"files":[]}""")
        val b = Json.parseToJsonElement("""{"files":[],"a":{"c":null,"z":[{"x":2,"y":1}]},"b":1}""")
        assertEquals(CanonicalJson.encode(a), CanonicalJson.encode(b))
        assertEquals("""{"a":{"c":null,"z":[{"x":2,"y":1}]},"b":1,"files":[]}""", CanonicalJson.encode(a))
    }

    @Test
    fun `the same book encodes to the same bytes every time`() {
        val book = NewBook(title = "Live test", author = "Test Author", narrator = "Test Narrator", visibility = "private", files = listOf(NewBook.File("u/1", "001 - silence.wav", "Silence")))
        val first = CanonicalJson.encode(NewBook.serializer(), book)
        repeat(200) { assertEquals(first, CanonicalJson.encode(NewBook.serializer(), book)) }
        assertEquals(
            """{"author":"Test Author","files":[{"relative_path":"001 - silence.wav","title":"Silence","upload_id":"u/1"}],"narrator":"Test Narrator","title":"Live test","visibility":"private"}""",
            first,
        )
    }

    // endregion

    // region Records

    @Test
    fun `a page of tracks decodes, times with fractions included`() = runBlocking {
        StubServer {
            json("""{"items":[{"id":"t1","title":"One","album_id":"al1","recording_id":null,"credits":[{"contributor_id":"c1","role":"artist","position":0,"join_phrase":" feat. "},{"contributor_id":"c2","role":"featured","position":1,"join_phrase":""}],"genre_ids":["g1"],"disc_number":1,"track_number":3,"duration_ms":180000,"visibility":"family","owner_id":"u1","source":"upload","content_type":"audio/flac","size_bytes":123,"image":null,"created_at":"2026-10-11T12:00:00.123456Z","updated_at":"2026-10-11T12:00:00Z"}],"next":"abc","included":{"albums":[{"id":"al1","title":"A","credits":[],"release_group_id":"rg1","release_date":null,"country":null,"label":null,"barcode":null,"is_compilation":false,"musicbrainz_release_id":null,"track_count":1,"duration_ms":180000,"disc_count":1,"image":null,"created_at":"2026-10-11T12:00:00Z"}]}}""")
        }.use { stub ->
            val page = stub.client().tracks(include = listOf("albums"))
            assertEquals("albums", stub.requests.single().url.queryParameter("include"))
            val track = page.items.single()
            assertEquals(listOf("artist", "featured"), track.credits.map { it.role })
            assertEquals(180_000L, track.durationMs)
            assertEquals(Instant.parse("2026-10-11T12:00:00.123456Z"), track.createdAt)
            assertEquals(0L, track.chapterCount)
            assertEquals("abc", page.next)
            assertEquals("rg1", page.included?.albums?.single()?.releaseGroupId)
        }
    }

    @Test
    fun `times with an offset read as the same instant`() {
        assertEquals(Instant.parse("2026-11-21T02:00:25.487751Z"), Rfc3339.parse("2026-11-21T03:00:25.487751+01:00"))
        assertEquals("2026-11-21T02:00:25Z", Rfc3339.format(Instant.parse("2026-11-21T02:00:25Z")))
    }

    @Test
    fun `progress goes as snake_case JSON`() = runBlocking {
        StubServer {
            json("""{"progress":{"kind":"track","id":"t1","position_ms":30000,"finished":false,"updated_at":"2026-10-11T12:00:00Z"},"applied":true}""")
        }.use { stub ->
            val answer = stub.client().saveProgress(ItemKind.TRACK, "t1", positionMs = 30_000)
            assertTrue(answer.applied)
            val sent = stub.requests.single()
            assertEquals("PUT", sent.method)
            assertEquals("/api/v2/me/progress/track/t1", sent.url.encodedPath)
            assertEquals("""{"finished":false,"position_ms":30000}""", sent.text())
        }
    }

    @Test
    fun `a position never saved is null, other kinds of the list in one query`() = runBlocking {
        StubServer {
            when {
                it.url.encodedPath.endsWith("/t1") -> json("""{"kind":"track","id":"t1","position_ms":2830000,"finished":false,"updated_at":"2026-10-21T19:35:01Z","devices":[{"device_id":"d1","device_name":"Pixel","device_kind":"android","position_ms":2830000,"finished":false,"at":"2026-10-21T19:35:01Z","current":true}]}""")
                it.url.encodedPath == "/api/v2/me/progress" -> json(answer("progress_list"))
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val client = stub.client()
            val p = client.progress(ItemKind.TRACK, "t1")
            assertEquals(2_830_000L, p?.positionMs)
            assertEquals(true, p?.devices?.single()?.current)
            assertNull(client.progress(ItemKind.TRACK, "t2"))
            val list = client.progressList(listOf(ItemKind.BOOK, ItemKind.EPISODE), unfinished = true)
            assertEquals(2, list.items.size)
            assertEquals("book,episode", stub.requests.last().url.queryParameter("kind"))
            assertEquals("true", stub.requests.last().url.queryParameter("unfinished"))
        }
    }

    @Test
    fun `unknown enum values and unknown fields never fail an answer`() {
        val play = OwnAudioJson.decodeFromString(
            Favourite.serializer(),
            """{"kind":"hologram","id":"x","created_at":"2026-10-11T12:00:00Z","new_field":{"a":1}}""",
        )
        assertEquals("hologram", play.kind)
        assertEquals(ItemKind.UNKNOWN, OwnAudioJson.decodeFromString(ItemKind.serializer(), "\"hologram\""))
        assertEquals(SmartRule.Arc.UNKNOWN, OwnAudioJson.decodeFromString(SmartRule.serializer(), """{"arc":"spiral"}""").arc)
    }

    // endregion

    // region Lists

    @Test
    fun `playlists, search, random, plays and lyrics - paths, bodies and answers`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "GET" to "/api/v2/playlists" -> json("""{"items":[$PLAYLIST]}""")
                "GET" to "/api/v2/playlists/p1/entries" -> json("""{"items":[{"id":"e1","position":1,"added_at":"2026-10-11T12:00:00Z","track":$TRACK}]}""")
                "POST" to "/api/v2/playlists", "POST" to "/api/v2/playlists/p1/entries" -> json(PLAYLIST)
                "PATCH" to "/api/v2/playlists/p1" -> json(PLAYLIST)
                "DELETE" to "/api/v2/playlists/p1", "DELETE" to "/api/v2/playlists/p1/entries/e1", "PUT" to "/api/v2/playlists/p1/entries/order" -> noContent()
                "GET" to "/api/v2/search" -> json("""{"artists":[],"albums":[],"tracks":[$TRACK]}""")
                "GET" to "/api/v2/tracks/random" -> json("""{"items":[$TRACK]}""")
                "GET" to "/api/v2/me/played" -> json("""{"items":[{"kind":"album","id":"a1","play_count":3,"last_played_at":"2026-10-11T12:00:00Z"}]}""")
                "GET" to "/api/v2/tracks/t1/lyrics" -> json("""{"text":"[00:01.00]Hi","synced":true,"lines":[{"start_ms":1000,"text":"Hi"}]}""")
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val client = stub.client()
            assertEquals(1L, client.playlists().items.single().trackCount)
            assertEquals("t1", client.playlistEntries("p1").items.single().track.id)
            client.createPlaylist("Road", trackIds = listOf("t1"))
            client.addToPlaylist("p1", listOf("t2"))
            client.setPlaylistVisibility("p1", "family")
            client.removeFromPlaylist("p1", "e1")
            client.orderPlaylist("p1", listOf("e2", "e1"))
            client.deletePlaylist("p1")
            assertEquals(listOf("t1"), client.search("one").tracks.map { it.id })
            assertEquals(1, client.randomTracks(limit = 5).items.size)
            assertEquals(3L, client.played(ItemKind.ALBUM, order = "most").items.single().playCount)
            val words = client.lyrics("t1")
            assertTrue(words.synced)
            assertEquals(1000L, words.lines.single().startMs)

            val made = stub.requests.first { it.method == "POST" && it.url.encodedPath == "/api/v2/playlists" }
            assertEquals("""{"name":"Road","track_ids":["t1"]}""", made.text())
            assertEquals("""{"visibility":"family"}""", stub.requests.first { it.method == "PATCH" }.text())
            assertEquals("""{"entry_ids":["e2","e1"]}""", stub.requests.first { it.url.encodedPath.endsWith("/order") }.text())
            val played = stub.requests.first { it.url.encodedPath == "/api/v2/me/played" }
            assertEquals("most", played.url.queryParameter("order"))
            assertEquals("album", played.url.queryParameter("kind"))
        }
    }

    @Test
    fun `paging follows next until it is absent`() = runBlocking {
        StubServer {
            when (it.url.queryParameter("cursor")) {
                null -> json("""{"items":[{"id":"g1","name":"A"},{"id":"g2","name":"B"}],"next":"c2"}""")
                "c2" -> json("""{"items":[{"id":"g3","name":"C"}],"next":"c3"}""")
                else -> json("""{"items":[{"id":"g4","name":"D"}],"next":null}""")
            }
        }.use { stub ->
            val client = stub.client()
            val all = pageAll { client.genres(cursor = it, limit = 2) }
            assertEquals(listOf("g1", "g2", "g3", "g4"), all.map { it.id })
            assertEquals(listOf(null, "c2", "c3"), stub.requests.map { it.url.queryParameter("cursor") })
            assertTrue(stub.requests.all { it.url.queryParameter("limit") == "2" })
        }
    }

    // endregion

    // region Smart playlists

    @Test
    fun `a smart rule goes as the server's rule - snake_case, its limit kinds, nothing unset`() {
        val rule = SmartRule(
            filters = SmartRule.Filters(playedByOthersNotMe = true, genres = SmartRule.Names(include = listOf("jazz"))),
            limit = SmartRule.Limit.Minutes(90),
            arc = SmartRule.Arc.WARMUP_SUSTAIN_COOLDOWN,
            sort = SmartRule.Sort.LEAST_RECENTLY_PLAYED,
        )
        val obj = OwnAudioJson.encodeToJsonElement(SmartRule.serializer(), rule).jsonObject
        val filters = obj["filters"]!!.jsonObject
        assertEquals("true", filters["played_by_others_not_me"]!!.jsonPrimitive.content)
        assertEquals("jazz", filters["genres"]!!.jsonObject["include"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse("what is not set is not sent", "bpm" in filters || "min_rating" in filters)
        assertEquals(JsonObject(mapOf("kind" to kotlinx.serialization.json.JsonPrimitive("duration"), "minutes" to kotlinx.serialization.json.JsonPrimitive(90))), obj["limit"])
        assertEquals("warmup-sustain-cooldown", obj["arc"]!!.jsonPrimitive.content)
        assertEquals("least-recently-played", obj["sort"]!!.jsonPrimitive.content)
        assertEquals(2, obj["sequence"]!!.jsonObject["max_per_artist"]!!.jsonPrimitive.int)

        val server = """{"arc":"none","filters":{"not_played_days":365,"starred":true},"limit":{"kind":"count","n":50},"sequence":{"max_per_artist":2,"no_consecutive_artist":true},"sort":"weighted-random"}"""
        val decoded = OwnAudioJson.decodeFromString(SmartRule.serializer(), server)
        assertEquals(365, decoded.filters.notPlayedDays)
        assertEquals(true, decoded.filters.starred)
        assertEquals(SmartRule.Limit.Count(50), decoded.limit)
        assertEquals("a rule with nothing set is the defaults", SmartRule(), OwnAudioJson.decodeFromString(SmartRule.serializer(), "{}"))
    }

    @Test
    fun `smart playlists - list, presets, resolve with include`() = runBlocking {
        StubServer {
            when (it.url.encodedPath) {
                "/api/v2/smart-playlists" -> json(answer("smart_playlists"))
                "/api/v2/smart-playlists/presets" -> json(answer("smart_presets"))
                else -> json("""{"items":[$TRACK],"duration_ms":1000,"candidates":7}""")
            }
        }.use { stub ->
            val client = stub.client()
            assertTrue(client.smartPlaylists().items.isNotEmpty())
            assertTrue(client.smartPresets().isNotEmpty())
            val resolved = client.resolve(SmartRule(), include = listOf("albums"))
            assertEquals(7, resolved.candidates)
            val sent = stub.requests.last()
            assertEquals("albums", sent.url.queryParameter("include"))
            assertTrue(sent.text().startsWith("""{"rule":{"arc":"none","filters":{},"limit":{"kind":"count","n":50}"""))
        }
    }

    // endregion
}
