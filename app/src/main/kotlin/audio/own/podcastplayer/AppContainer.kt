package audio.own.podcastplayer

import android.content.Context

/**
 * Hand-wired dependencies. Android Auto, the assistant and the widget can bind to the media
 * service without any screen having started, so the playback dependencies will be created here,
 * eagerly, rather than from the UI.
 */
class AppContainer(@Suppress("unused") private val context: Context)
