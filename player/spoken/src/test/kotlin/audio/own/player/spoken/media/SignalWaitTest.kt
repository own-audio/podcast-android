package audio.own.player.spoken.media

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import audio.own.player.core.PlaybackDataSources
import audio.own.player.core.PlaybackLink
import audio.own.player.core.PlaybackLinkResolver
import audio.own.player.core.PlaybackUris
import audio.own.player.spoken.SpokenBook
import audio.own.player.spoken.SpokenPart
import audio.own.player.spoken.SpokenPlayer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SignalWaitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the backoff doubles from 2 to 30 seconds`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { SignalWaitPolicy.backoffMs(it) })
    }

    @Test
    fun `with no network the player waits in buffering instead of failing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var connected = true
        // Nothing listens on port 9: every open fails as a dead network would.
        val resolver = PlaybackLinkResolver { PlaybackLink("http://127.0.0.1:9/book.mp3") }
        val sources = PlaybackDataSources(context, resolver, OkHttpClient(), cache = null)
        val policy = SignalWaitPolicy { connected }
        val exo = TestExoPlayerBuilder(context).setMediaSourceFactory(SpokenMediaSourceFactory(context, sources, policy)).build()
        val spoken = SpokenPlayer(exo, scope, idleReleaseMs = null, links = sources.links)
        connected = false
        spoken.networkChanged(false)
        val part = SpokenPart("f1", PlaybackUris.forItem("srv|b1", "f1").toString(), 60_000)
        spoken.load(SpokenBook("srv|b1", "Book", parts = listOf(part)), 10_000, autoplay = true)
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        // Held, not failed. (The test player's stuck-buffering check, which the app's player has
        // off, may turn the hold into a failed load; the wait covers both.)
        assertNull(spoken.state.value.error)
        assertTrue(exo.playWhenReady)
        assertTrue(spoken.state.value.waitingForSignal)
        assertTrue(spoken.state.value.playWhenReady)

        // The listener pauses while waiting: the signal's return doesn't start it.
        spoken.pause()
        connected = true
        spoken.networkChanged(true)
        assertFalse(spoken.state.value.waitingForSignal)
        assertFalse(exo.playWhenReady)
        spoken.release()
        scope.cancel()
    }

    @Test
    fun `a failed link is asked for once more, a second failure is the error`() {
        val server = MockWebServer()
        repeat(20) { server.enqueue(MockResponse.Builder().code(500).build()) }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var resolved = 0
        val resolver = PlaybackLinkResolver { resolved++; PlaybackLink(server.url("/book.mp3").toString()) }
        val sources = PlaybackDataSources(context, resolver, OkHttpClient(), cache = null)
        val exo = TestExoPlayerBuilder(context).setMediaSourceFactory(SpokenMediaSourceFactory(context, sources, SignalWaitPolicy { true })).build()
        val spoken = SpokenPlayer(exo, scope, idleReleaseMs = null, links = sources.links)
        var failed = 0
        spoken.onLinkFailed = { _, _ -> failed++ }
        val part = SpokenPart("f1", PlaybackUris.forItem("srv|b1", "f1").toString(), 60_000)
        spoken.load(SpokenBook("srv|b1", "Book", parts = listOf(part)), 0, autoplay = true)
        val deadline = System.currentTimeMillis() + 10_000
        while (spoken.state.value.error == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals(1, failed)
        assertEquals(2, resolved)
        assertTrue(spoken.state.value.error != null)
        assertFalse(exo.playWhenReady)
        spoken.release()
        scope.cancel()
        server.close()
    }
}
