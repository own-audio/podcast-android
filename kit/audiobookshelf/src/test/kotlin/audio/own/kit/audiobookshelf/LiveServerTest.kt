package audio.own.kit.audiobookshelf

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Against a real Audiobookshelf, with a throwaway user. Skipped unless the environment names
 * one:
 *
 * ```
 * AUDIOBOOKSHELF_HOST=http://nas.local:13378 AUDIOBOOKSHELF_USERNAME=booktest \
 *   AUDIOBOOKSHELF_PASSWORD=… ./gradlew :kit:audiobookshelf:test
 * ```
 *
 * Servers limit sign-ins (429), so this signs in once and walks one flow: libraries, one book,
 * a play session that syncs and closes. The book's position is put back as it was found.
 */
class LiveServerTest {
    private class Config(val host: okhttp3.HttpUrl, val username: String, val password: String)

    private val config: Config? = run {
        val env = System.getenv()
        val host = env["AUDIOBOOKSHELF_HOST"]?.let { AudiobookshelfClient.urlFromTyped(it) ?: it.toHttpUrlOrNull() }
        val username = env["AUDIOBOOKSHELF_USERNAME"] ?: env["AUDIOBOOKSHELF_USER"]
        val password = env["AUDIOBOOKSHELF_PASSWORD"]
        if (host != null && username != null && password != null) Config(host, username, password) else null
    }

    @Test
    fun `sign in, list libraries, open a book, play, sync and close`(): Unit = runBlocking {
        val config = config
        assumeTrue("AUDIOBOOKSHELF_HOST, _USERNAME and _PASSWORD not set", config != null)
        config!!
        val http = AudiobookshelfHttp(testOkHttp, userAgent = "own.audio Books/0.0.0-live-test (JVM)")

        val status = AudiobookshelfClient.status(config.host, http)
        assertEquals("audiobookshelf", status.app)

        val signIn = AudiobookshelfClient.signIn(config.host, config.username, config.password, http)
        if (status.version >= ServerVersion.ROTATING_TOKENS) {
            assertNotNull(signIn.tokens.refreshToken)
            assertTrue(signIn.tokens.expiresAtMillis < System.currentTimeMillis() + 2 * 3_600_000)
        }
        val saved = mutableListOf<Tokens?>()
        val client = AudiobookshelfClient(config.host, TokenSession(signIn.tokens, { saved += it }), http)
        try {
            assertEquals(signIn.user.id, client.authorize().user.id)

            val library = client.bookLibraries().first()
            val row = client.items(library.id, limit = 1, sort = ItemSort.DURATION).results.first()
            val detail = client.item(row.id)
            val tracks = detail.media.tracks.orEmpty()
            assertTrue(tracks.isNotEmpty() && tracks.all { it.duration > 0 })
            assertTrue(abs(tracks.sumOf { it.duration } - detail.media.duration) < 2)

            // One ranged read of the first file, the way the player and downloads ask.
            val token = client.freshToken()
            val ranged = Request.Builder()
                .url(client.mediaUrl(tracks.first().contentUrl, token))
                .header("Range", "bytes=0-99")
                .header("User-Agent", http.userAgent)
                .build()
            testOkHttp.newCall(ranged).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals(100, response.body.bytes().size)
            }

            val before = client.progress(row.id)
            val device = DeviceInfo("live-tests", "own.audio Books", "0.0.0", "JVM", "test", null)
            val session = client.play(row.id, device)
            assertEquals(row.id, session.libraryItemId)
            assertFalse(session.audioTracks.isEmpty())
            client.syncSession(session.id, currentTime = 20.0, timeListened = 5.0, duration = session.duration)
            assertEquals(20.0, client.progress(row.id)?.currentTime ?: 0.0, 0.01)
            client.closeSession(session.id, currentTime = 25.0, timeListened = 5.0, duration = session.duration)
            try {
                client.syncSession(session.id, currentTime = 26.0, timeListened = 1.0, duration = session.duration)
                throw AssertionError("a closed session should refuse a sync")
            } catch (e: AudiobookshelfError.Http) {
                assertEquals(404, e.status)
            }

            // Leave the account as it was found.
            if (before == null) {
                client.progress(row.id)?.let { client.deleteProgress(it.id) }
            } else {
                client.updateProgress(row.id, ProgressUpdate.of(before.currentTime, before.duration, before.isFinished))
            }
        } finally {
            client.signOut()
        }
    }
}
