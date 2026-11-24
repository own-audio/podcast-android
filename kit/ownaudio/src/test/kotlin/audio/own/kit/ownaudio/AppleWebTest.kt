package audio.own.kit.ownaudio

import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppleWebTest {

    @Test
    fun `the challenge is base64url of SHA-256 without padding`() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `a fresh pair has a verifier of allowed characters and length, and differs each time`() {
        val random = SecureRandom()
        val a = Pkce.create(random)
        val b = Pkce.create(random)
        assertTrue(a.verifier.length in 43..128)
        assertTrue(a.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(Pkce.challenge(a.verifier), a.challenge)
        assertFalse('=' in a.challenge)
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(Pkce.state(random), Pkce.state(random))
    }

    @Test
    fun `the start page is under v2 with the redirect, the challenge and the state`() {
        StubServer { json("{}") }.use { stub ->
            val url = stub.client(tokens = null).appleStartUrl(AppleWeb.redirectUri("audio.own.musicplayer"), "chal", "st-1")
            assertEquals("/api/v2/auth/apple/start", url.encodedPath)
            assertEquals("audio.own.musicplayer:/apple", url.queryParameter("redirect_uri"))
            assertEquals("chal", url.queryParameter("code_challenge"))
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals("st-1", url.queryParameter("state"))
            assertTrue("the redirect is percent-encoded", "redirect_uri=audio.own.musicplayer%3A%2Fapple" in url.toString())
            assertTrue("nothing is asked of the server yet", stub.requests.isEmpty())
        }
    }

    @Test
    fun `the code goes to the token endpoint with the verifier and the device`() = runBlocking {
        StubServer { json(tokenJson()) }.use { stub ->
            val tokens = stub.client(tokens = null).signInWithApple("one-time", "verifier-1", OwnAudioClient.Device("Pixel"))
            assertEquals("a1", tokens.accessToken)
            val request = stub.requests.single()
            assertEquals("POST /api/v2/auth/token", "${request.method} ${request.url.encodedPath}")
            val fields = request.form()
            assertEquals(Grants.APPLE_CODE, fields["grant_type"])
            assertEquals("one-time", fields["code"])
            assertEquals("verifier-1", fields["code_verifier"])
            assertEquals("Pixel", fields["device_name"])
            assertEquals("android", fields["device_kind"])
            assertFalse("invite_code" in fields)
        }
    }

    @Test
    fun `a used or expired code is invalid_grant, and two-factor carries its token`() = runBlocking {
        StubServer { problem("invalid_grant", 400) }.use { stub ->
            try {
                stub.client(tokens = null).signInWithApple("old", "v", OwnAudioClient.Device("Pixel"))
                fail("expected invalid_grant")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("invalid_grant", p.code)
            }
        }
        StubServer { problem("mfa_required", 403, ""","mfa_token":"m1","mfa_methods":["totp"]""") }.use { stub ->
            try {
                stub.client(tokens = null).signInWithApple("c", "v", OwnAudioClient.Device("Pixel"))
                fail("expected mfa_required")
            } catch (p: OwnAudioException.Problem) {
                assertEquals("mfa_required", p.code)
                assertNotNull(p.mfaToken)
            }
        }
    }

    @Test
    fun `discovery offers Apple in the browser by a flag or by the grant, and nothing else`() {
        fun discovery(auth: String) = OwnAudioJson.decodeFromString(
            Discovery.serializer(),
            """{"name":"x","api":[{"version":2,"path":"/api/v2","revision":17}],"auth":$auth}""",
        )
        assertTrue(discovery("""{"apple_web":true}""").supportsAppleWeb)
        assertTrue(discovery("""{"apple_web":{"start":"/api/v2/auth/apple/start"}}""").supportsAppleWeb)
        assertTrue(discovery("""{"grants":["password","${Grants.APPLE_CODE}"]}""").supportsAppleWeb)
        assertFalse(discovery("""{"apple_web":false}""").supportsAppleWeb)
        assertFalse(discovery("""{"apple_web":{"enabled":false}}""").supportsAppleWeb)
        assertFalse(discovery("""{"apple_web":"yes"}""").supportsAppleWeb)
        assertFalse(discovery("""{"apple_web":[1]}""").supportsAppleWeb)
        assertFalse("Apple's native sign-in alone isn't the browser flow", discovery("""{"providers":["apple"],"clients":{"apple":{"web":"x"}}}""").supportsAppleWeb)
        assertFalse(discovery("{}").supportsAppleWeb)
    }
}
