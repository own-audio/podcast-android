package audio.own.kit.gpodder

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Against a real gPodder server (podfetch, own.audio), named by GPODDER_HOST, GPODDER_USERNAME
 * and GPODDER_PASSWORD. Works on a device of its own and removes the feed it adds; the episode
 * action it sends stays in the account's history, as the API has no way to delete one.
 */
class GPodderLiveTest {
    private val host = System.getenv("GPODDER_HOST")
    private val username = System.getenv("GPODDER_USERNAME") ?: System.getenv("GPODDER_USER")
    private val password = System.getenv("GPODDER_PASSWORD")

    @Before fun configured() = assumeTrue("set GPODDER_HOST, GPODDER_USERNAME, GPODDER_PASSWORD", host != null && username != null && password != null)

    private fun client(password: String = this.password) = GPodderClient(host, username!!, password, OkHttpClient(), "own.audio Podcasts (tests)")

    @Test
    fun `login, a device, a subscription added and removed, an episode action round trip`() = runTest(timeout = 120.seconds) {
        val client = client()
        client.login()
        val device = "own-audio-podcasts-test"
        client.registerDevice(id = device, caption = "own.audio Podcasts tests")
        val feed = "https://feeds.example.org/own-audio-test-${UUID.randomUUID().toString().take(8)}.xml"

        val added = client.uploadSubscriptionChanges(device, add = listOf(feed), remove = emptyList())
        // The whole list (since 0) has it, on servers with per-device lists and account-wide ones.
        assertTrue("the feed added is in the device's list", feed in client.subscriptionChanges(device, since = 0).add)
        client.allSubscriptionChanges(since = 0)?.let { assertTrue("and in the account's", feed in it.add) }
        val removed = client.uploadSubscriptionChanges(device, add = emptyList(), remove = listOf(feed))
        assertTrue(removed.timestamp >= added.timestamp)
        assertFalse("and is gone once removed", feed in client.subscriptionChanges(device, since = 0).add)

        val before = client.episodeActions(since = 0)
        val action = EpisodeAction(
            podcast = feed, episode = "$feed/1.mp3", action = EpisodeAction.Kind.PLAY,
            timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS), started = 0, position = 42, total = 600, device = device,
        )
        client.uploadEpisodeActions(listOf(action))
        val back = client.episodeActions(since = before.timestamp).actions.firstOrNull { it.episode == "$feed/1.mp3" }
        assertEquals(42, back?.position)
        assertEquals(EpisodeAction.Kind.PLAY, back?.action)
    }

    @Test
    fun `a wrong password is refused`() = runTest(timeout = 60.seconds) {
        try {
            client("not-the-password").login()
            fail("a wrong password signed in")
        } catch (_: GPodderException.Unauthorized) {
        }
    }
}
