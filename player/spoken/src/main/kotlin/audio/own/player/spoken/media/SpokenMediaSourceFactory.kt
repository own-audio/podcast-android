package audio.own.player.spoken.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import audio.own.player.core.PlaybackDataSources

/**
 * Files through the listening cache; an HLS part (Audiobookshelf's Saver transcode) straight from
 * the network, never cached: its segments are made as it goes and named by the session.
 */
@UnstableApi
class SpokenMediaSourceFactory(
    context: Context,
    dataSources: PlaybackDataSources,
    loadErrorPolicy: LoadErrorHandlingPolicy,
) : MediaSource.Factory {
    private val files = DefaultMediaSourceFactory(dataSources.dataSourceFactory).setLoadErrorHandlingPolicy(loadErrorPolicy)
    private val hls = HlsMediaSource.Factory(DefaultDataSource.Factory(context, dataSources.linkDataSourceFactory))
        .setLoadErrorHandlingPolicy(loadErrorPolicy)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        files.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        files.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) hls.createMediaSource(mediaItem) else files.createMediaSource(mediaItem)
}
