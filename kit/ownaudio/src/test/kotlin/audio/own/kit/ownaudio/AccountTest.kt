package audio.own.kit.ownaudio

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountTest {

    @Test
    fun `statistics are asked of v2 with the range, the kinds and the phone's offset`() = runBlocking {
        StubServer {
            when (it.url.encodedPath) {
                "/api/v2/me/stats" -> json(answer("stats"))
                else -> json("""[{"user_id":"u1","listened_ms":5400000,"plays":3,"by_kind":[{"kind":"book","listened_ms":5400000,"plays":3}]}]""")
            }
        }.use { stub ->
            val client = stub.client()
            val from = Instant.parse("2026-10-01T00:00:00Z")
            val to = Instant.parse("2026-11-01T00:00:00Z")
            val stats = client.listeningStats(from, to, group = "week", kinds = listOf("book", "episode"), zone = ZoneId.of("Europe/Prague"))
            assertTrue(stats.listenedMs > 0 && stats.buckets.isNotEmpty() && stats.byKind.isNotEmpty())
            val url = stub.requests[0].url
            assertEquals("2026-10-01T00:00:00Z", url.queryParameter("from"))
            assertEquals("week", url.queryParameter("group"))
            assertEquals("book,episode", url.queryParameter("kind"))
            assertEquals("60", url.queryParameter("tz_offset_minutes"))
            val family = client.familyStats()
            assertEquals(5_400_000L, family.single().listenedMs)
            assertEquals("/api/v2/family/stats", stub.requests[1].url.encodedPath)
        }
    }

    @Test
    fun `settings - read, and the admin's view of statistics switched with a patch`() = runBlocking {
        StubServer { json(answer("settings")) }.use { stub ->
            val client = stub.client()
            assertNotNull(client.settings().playback)
            client.setStatsVisibleToAdmin(true)
            val patch = stub.requests.last()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/v2/me/settings", patch.url.encodedPath)
            assertEquals("""{"stats_visible_to_admin":true}""", patch.text())
        }
    }

    @Test
    fun `deleting the account is DELETE me with the password and signs out`() = runBlocking {
        StubServer { noContent() }.use { stub ->
            val session = TokenSession(farTokens())
            stub.client(tokens = session).deleteAccount(password = "secret")
            assertEquals(listOf("DELETE /api/v2/me"), stub.paths())
            assertEquals("""{"password":"secret"}""", stub.requests.single().text())
            assertNull(session.current)
        }
        StubServer { noContent() }.use { stub ->
            stub.client().deleteAccount()
            assertEquals("a provider-only account sends an empty object", "{}", stub.requests.single().text())
        }
    }

    @Test
    fun `the last admin keeps the account and the tokens`() = runBlocking {
        StubServer { problem("last_admin", 409) }.use { stub ->
            val session = TokenSession(farTokens())
            try {
                stub.client(tokens = session).deleteAccount("secret")
                fail("expected last_admin")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("last_admin", p.code)
            }
            assertNotNull(session.current)
        }
    }

    @Test
    fun `the device list decodes, one device is signed out by id, everywhere signs this one out too`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "GET" to "/api/v2/me/sessions" -> json(answer("sessions"))
                else -> noContent()
            }
        }.use { stub ->
            val tokens = TokenSession(farTokens())
            val client = stub.client(tokens = tokens)
            val list = client.sessions()
            assertEquals(1, list.count { it.current })
            assertEquals("android", list.single { it.current }.deviceKind)
            assertTrue(list.any { it.deviceName == null && it.lastUsedAt == null })
            client.revokeSession("d2")
            client.revokeAllSessions()
            assertNull(tokens.current)
            assertEquals(listOf("GET /api/v2/me/sessions", "DELETE /api/v2/me/sessions/d2", "DELETE /api/v2/me/sessions"), stub.paths())
        }
    }

    @Test
    fun `the household - family, members, invites, joining, storage`() = runBlocking {
        StubServer {
            when (it.method to it.url.encodedPath) {
                "GET" to "/api/v2/family" -> json(answer("family"))
                "GET" to "/api/v2/family/members" -> json(answer("family_members"))
                "GET" to "/api/v2/family/storage" -> json(answer("family_storage"))
                "POST" to "/api/v2/family/invites" -> json("""{"id":"i1","kind":"link","code":"ABCD","role":"member","max_uses":3,"use_count":0,"expires_at":"2026-12-01T00:00:00Z","join_url":"https://music.example.com/join/ABCD","created_at":"2026-11-21T00:00:00Z"}""", 201)
                "POST" to "/api/v2/family/join/preview" -> json("""{"family":{"name":"Novak","image":null},"kind":"link","role":"member","member_count":4,"uses_left":2,"inviter_name":"Mira","expires_at":"2026-12-01T00:00:00Z"}""")
                "POST" to "/api/v2/family/join" -> json(answer("family"))
                else -> noContent()
            }
        }.use { stub ->
            val client = stub.client()
            assertEquals("member", client.family().me.role)
            val members = client.members()
            assertEquals("Mira", members.first().name)
            assertEquals("Guest", members[1].name)
            assertEquals(3, client.createInvite(maxUses = 3).maxUses)
            assertEquals("""{"kind":"link","max_uses":3}""", stub.requests.last().text())
            assertEquals(4L, client.joinPreview("ABCD").memberCount)
            client.join("ABCD")
            assertEquals("""{"code":"ABCD"}""", stub.requests.last().text())
            client.deleteInvite("i1")
            assertTrue(client.familyStorage().totalBytes >= 0)
        }
    }
}
