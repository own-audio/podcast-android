package audio.own.player.core

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * The service rules of foundation §7.1 on a real ExoPlayer (fake renderers) playing the 3 s
 * test tones from files: start position, audio focus only from the first play, the decoder
 * released 30 s after a pause, and sound continuing after a 30 s focus loss.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRulesTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var player: ExoPlayer
    private lateinit var items: List<MediaItem>

    @Before
    fun setUp() {
        items = listOf("mp3", "flac", "m4a").map { ext ->
            val file = File(folder.root, "tone.$ext")
            file.writeBytes(javaClass.getResource("/fixtures/test-tone.$ext")!!.readBytes())
            MediaItem.Builder().setMediaId("song:$ext").setUri(Uri.fromFile(file)).build()
        }
        player = TestExoPlayerBuilder(context).build()
        PlayerSetup.configure(player, ContentKind.MUSIC)
    }

    @After
    fun tearDown() = player.release()

    @Test
    fun `the start position goes with the items`() {
        player.setMediaItems(items, 1, 1_500)
        player.prepare()
        run(player).untilState(Player.STATE_READY)
        assertEquals(1, player.currentMediaItemIndex)
        assertTrue("at ${player.currentPosition}", player.currentPosition in 1_400..1_600)
    }

    @Test
    fun `audio focus is asked for only on the first play`() {
        val focus = AudioFocusOnFirstPlay(PlayerSetup.audioAttributes(ContentKind.MUSIC))
        player.setMediaItems(items, 0, 0)
        player.prepare()
        run(player).untilState(Player.STATE_READY)
        assertNull(shadowOf(audioManager).lastAudioFocusRequest)

        focus.beforePlay(player)
        player.play()
        run(player).untilPendingCommandsAreFullyHandled()
        assertNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertTrue(focus.isEnabled)
    }

    @Test
    fun `the decoder is released 30 s after a pause and the position kept`() {
        val release = IdleDecoderRelease(player)
        player.setMediaItems(items, 0, 0)
        player.prepare()
        player.play()
        advance(player).untilPosition(0, 1_000)
        player.pause()
        val position = player.currentPosition

        ShadowLooper.idleMainLooper(29, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(Player.STATE_READY, player.playbackState)
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(position, player.currentPosition)

        IdleDecoderRelease.prepareIfIdle(player)
        player.play()
        run(player).untilState(Player.STATE_READY)
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.currentPosition >= position)
        release.dispose()
    }

    @Test
    fun `after a 30 s focus loss mid-song playback goes on with sound`() {
        val release = IdleDecoderRelease(player)
        val focus = AudioFocusOnFirstPlay(PlayerSetup.audioAttributes(ContentKind.MUSIC))
        player.setMediaItems(items, 0, 0)
        player.prepare()
        focus.beforePlay(player)
        player.play()
        advance(player).untilPosition(0, 1_000)
        run(player).untilPendingCommandsAreFullyHandled()

        val request = shadowOf(audioManager).lastAudioFocusRequest
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        run(player).untilPendingCommandsAreFullyHandled()
        assertFalse(player.isPlaying)
        assertTrue("a call is not a pause", player.playWhenReady)
        val position = player.currentPosition

        ShadowLooper.idleMainLooper(Duration.ofSeconds(30).seconds, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("not released during a call", Player.STATE_READY, player.playbackState)

        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        run(player).untilPendingCommandsAreFullyHandled()
        assertTrue(player.isPlaying)
        assertEquals(1.0f, player.volume)
        advance(player).untilPosition(0, position + 500)
        assertEquals(0, player.currentMediaItemIndex)
        release.dispose()
    }
}
