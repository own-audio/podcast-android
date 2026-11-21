package audio.own.podcast.library

import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.ProgressUpdate
import audio.own.kit.audiobookshelf.TokenSession
import audio.own.kit.audiobookshelf.deleteProgress
import audio.own.kit.audiobookshelf.episodeProgress
import audio.own.kit.audiobookshelf.updateEpisodeProgress
import audio.own.kit.feed.FeedFetcher
import audio.own.kit.feed.FeedParser
import audio.own.kit.ownaudio.ItemKind
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.deleteProgress
import audio.own.kit.ownaudio.discover
import audio.own.kit.ownaudio.progress
import audio.own.kit.ownaudio.saveProgress
import audio.own.kit.ownaudio.signIn
import audio.own.kit.ownaudio.signOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant
import audio.own.kit.ownaudio.TokenSession as OwnAudioTokens

private const val USER_AGENT = "own.audio Podcasts/0-live-test (JVM)"

/** A range of [url] as the player's look-ahead fetches it, with the plain client: (status, Content-Range). */
private suspend fun range(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
    OkHttpClient().newCall(Request.Builder().url(url).header("Range", "bytes=0-100").header("User-Agent", USER_AGENT).build()).execute().use {
        it.code to it.header("Content-Range")
    }
}

/**
 * Both server sources against real servers, from the environment (`AUDIOBOOKSHELF_*`,
 * `OWNAUDIO_*`); skipped without them. Each signs in once (servers limit sign-ins), reads, plays
 * a range and makes one position round trip, putting the position back as it was. Nothing is
 * followed or unfollowed: that is tested against stubs only.
 */
class LiveSourcesTest {
    private val env: Map<String, String> = System.getenv()

    @Test
    fun `Audiobookshelf - shows, New Episodes, a show's episodes, a playable file, a position round trip`(): Unit = runBlocking {
        val host = env["AUDIOBOOKSHELF_HOST"]?.let(AudiobookshelfClient::urlFromTyped)
        val user = env["AUDIOBOOKSHELF_USERNAME"]
        val password = env["AUDIOBOOKSHELF_PASSWORD"]
        assumeTrue("AUDIOBOOKSHELF_HOST, _USERNAME and _PASSWORD not set", host != null && user != null && password != null)
        val http = AudiobookshelfHttp(OkHttpClient(), USER_AGENT)
        val signIn = AudiobookshelfClient.signIn(host!!, user!!, password!!, http)
        val client = AudiobookshelfClient(host, TokenSession(signIn.tokens), http)
        val fetcher = FeedFetcher(OkHttpClient(), FeedParser { KXmlParser() }, USER_AGENT)
        try {
            val abs = AudiobookshelfPodcastSource(client, ListeningStore(InMemoryValueStore()), FeedLoader(fetcher::fetch), signIn.user.permissions.upload)
            val shows = abs.shows()
            assertTrue("the server needs a podcast library with shows", shows.isNotEmpty())
            val inbox = abs.inbox(50)
            assertTrue("the recent episodes make New Episodes", inbox.isNotEmpty())
            val withFeed = shows.firstOrNull { it.feedUrl != null }
            if (withFeed != null) {
                val episodes = abs.episodes(withFeed.id)
                println("${withFeed.title}: ${episodes.count { !AudiobookshelfPodcastSource.isFeedOnly(it.key) }} on the server, ${episodes.count { AudiobookshelfPodcastSource.isFeedOnly(it.key) }} from the feed only")
                assertTrue(episodes.all { it.showId == withFeed.id })
            }
            val episode = inbox.first { !AudiobookshelfPodcastSource.isFeedOnly(it.key) }
            val playback = abs.playback(episode.key)
            assertTrue(playback.isServerFile)
            if (!playback.isHls) {
                val (code, contentRange) = range(playback.url)
                assertEquals("the server file answers a range", 206, code)
                assertTrue("Content-Range with a length: $contentRange", contentRange?.substringAfterLast('/')?.toLongOrNull() != null)
            }

            val before = client.episodeProgress(episode.showId, episode.id)
            val saved = abs.saveProgress(ListeningProgress(61_000, false, Instant.now()), episode.key)
            assertEquals(61_000L, saved.positionMs)
            val server = client.episodeProgress(episode.showId, episode.id)!!
            assertEquals(61.0, server.currentTime, 0.5)
            if (before != null) {
                client.updateEpisodeProgress(episode.showId, episode.id, ProgressUpdate.of(before.currentTime, before.duration, before.isFinished))
            } else {
                client.deleteProgress(server.id)
            }
        } finally {
            client.signOut()
        }
    }

    @Test
    fun `own_audio - the catalogue from the feed, New Episodes, a stream, a position round trip`(): Unit = runBlocking {
        val host = env["OWNAUDIO_HOST"]
        val email = env["OWNAUDIO_EMAIL"]
        val password = env["OWNAUDIO_ACCOUNT_PASSWORD"]
        assumeTrue("OWNAUDIO_HOST, _EMAIL and _ACCOUNT_PASSWORD not set", host != null && email != null && password != null)
        val bare = OwnAudioClient(host!!, OkHttpClient(), USER_AGENT)
        val discovery = bare.discover()
        val tokens = bare.signIn(email!!, password!!, OwnAudioClient.Device("own.audio Podcasts live test"))
        val client = OwnAudioClient(host, OkHttpClient(), USER_AGENT, tokens = OwnAudioTokens(tokens))
        try {
            val modules = discovery.modules.keys.filter(discovery::has).toSet()
            val catalogue = InMemoryValueStore<OwnAudioCatalogue>()
            val oa = OwnAudioPodcastSource(client, ListeningStore(InMemoryValueStore()), catalogue, modules)
            val shows = oa.shows()
            assertTrue("the account needs podcasts", shows.isNotEmpty())
            val status = oa.status()
            println("own.audio: ${status.shows} shows, ${status.episodes} episodes on the device; modules ${modules.sorted()}")
            val withEpisodes = shows.first { oa.episodes(it.id).isNotEmpty() }
            assertTrue(oa.episodes(withEpisodes.id).all { it.showId == withEpisodes.id })
            val inbox = oa.inbox(50)
            assertTrue("first sight: at most the newest episode of each show (${inbox.size} for ${shows.size})", inbox.isNotEmpty() && inbox.size <= shows.size)
            assertTrue(oa.unplayedCounts().isNotEmpty())

            val episode = inbox.first()
            val playback = oa.playback(episode.key)
            println("playback: ${if (playback.isServerFile) "the server's copy" else "the publisher's file"}, transcode ${playback.isTranscode}")
            val (code, _) = range(playback.url)
            assertTrue("the stream answers without a token: $code", code == 200 || code == 206)

            val before = client.progress(ItemKind.EPISODE, episode.id)
            val saved = oa.saveProgress(ListeningProgress(61_000, false, Instant.now()), episode.key)
            assertEquals(61_000L, saved.positionMs)
            assertEquals(61_000L, client.progress(ItemKind.EPISODE, episode.id)?.positionMs)
            if (before != null) {
                client.saveProgress(ItemKind.EPISODE, episode.id, before.positionMs, before.finished)
            } else {
                client.deleteProgress(ItemKind.EPISODE, episode.id)
            }
            assertFalse("a sync right after reads only the changes", oa.sync(force = true).any { it.id == episode.id })
        } finally {
            client.signOut()
        }
    }
}
