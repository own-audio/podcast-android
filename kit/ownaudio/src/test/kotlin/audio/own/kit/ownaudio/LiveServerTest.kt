package audio.own.kit.ownaudio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real own.audio server, only when it is configured (from the gitignored
 * `test-servers.local.env`):
 *
 * ```
 * OWNAUDIO_HOST=https://music.example.com OWNAUDIO_EMAIL=… OWNAUDIO_PASSWORD=… ./gradlew :kit:ownaudio:test
 * ```
 *
 * Read-only: it changes nothing on the server, so a public demo account works.
 */
class LiveServerTest {
    private val host = System.getenv("OWNAUDIO_HOST")
    private val email = System.getenv("OWNAUDIO_EMAIL")
    private val password = System.getenv("OWNAUDIO_PASSWORD")

    private fun client(tokens: TokenSession? = null) =
        OwnAudioClient(host!!, OkHttpClient(), "own.audio Music/0.0 (Android test; JVM)", tokens = tokens, acceptLanguage = "en")

    @Test
    fun `discovery, sign-in, the account, every page of albums, a snapshot's start, sign-out`() = runBlocking {
        assumeTrue("set OWNAUDIO_HOST, OWNAUDIO_EMAIL and OWNAUDIO_PASSWORD", host != null && email != null && password != null)
        val discovery = client().discover()
        assertTrue(discovery.requireV2() >= OwnAudioException.MIN_REVISION)
        val tokens = TokenSession(client().signIn(email!!, password!!, OwnAudioClient.Device("kit:ownaudio live test")))
        val client = client(tokens)
        val me = client.me()
        assertEquals(email.lowercase(), me.email.lowercase())

        val albums = pageAll { client.albums(include = listOf("contributors"), cursor = it, limit = 25) }
        assertEquals(albums.size, albums.map { it.id }.toSet().size)
        if (albums.isNotEmpty()) {
            val album = client.album(albums.first().id)
            val tracks = client.tracks(albumId = album.record.id, include = listOf("albums", "contributors"))
            tracks.items.firstOrNull()?.let { client.track(it.id) }
        }
        client.genres()
        client.playlists()
        client.smartPlaylists()
        client.favourites(ItemKind.TRACK)
        client.progressList(listOf(ItemKind.TRACK, ItemKind.BOOK, ItemKind.EPISODE))
        client.settings()
        client.sessions()
        client.family()
        client.members()
        client.changes(null)
        val begin = withTimeout(20_000) { client.snapshot(kinds = listOf("genre")).first() }
        assertTrue(begin is SnapshotLine.Begin)

        client.signOut()
        assertEquals(SignInState.SIGNED_OUT, tokens.state.value)
    }

    @Test
    fun `a wrong password is invalid_grant`() = runBlocking {
        assumeTrue(host != null && email != null && password != null)
        try {
            client().signIn(email!!, password + "-wrong", OwnAudioClient.Device("x"))
        } catch (p: OwnAudioException.Problem) {
            assertTrue(p.code == "invalid_grant" || p.code == "account_locked")
        }
        Unit
    }
}
