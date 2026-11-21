package audio.own.podcastplayer.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import audio.own.podcastplayer.ui.Section
import audio.own.core.strings.R
import audio.own.podcastplayer.ui.Page
import audio.own.podcastplayer.ui.RowDivider
import audio.own.podcastplayer.ui.TwoLines
import audio.own.podcastplayer.ui.clickableRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import audio.own.podcastplayer.ui.CardContentPadding

/** Third-party software and pictures in the app, with their licences. Names are not translated. */
data class Notice(val name: String, val licence: String, val url: String)

val NOTICES = listOf(
    Notice("AndroidX, Jetpack Compose, Material 3", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Notice("Media3 (ExoPlayer)", "Apache License 2.0", "https://github.com/androidx/media"),
    Notice("OkHttp", "Apache License 2.0", "https://square.github.io/okhttp/"),
    Notice("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "https://kotlinlang.org"),
    Notice("Coil", "Apache License 2.0", "https://coil-kt.github.io/coil/"),
    Notice("ZXing", "Apache License 2.0", "https://github.com/zxing/zxing"),
    Notice("Jellyfin logo", "CC BY-SA 4.0", "https://jellyfin.org/docs/general/contributing/branding/"),
    Notice("OpenSubsonic logo", "Apache License 2.0", "https://opensubsonic.netlify.app"),
)

@Composable
fun LicencesContent(onBack: () -> Unit) {
    val links = LocalUriHandler.current
    Page(title = stringResource(R.string.licences), onBack = onBack) {
        Section(header = null, footer = stringResource(R.string.licences_footer)) {
            NOTICES.forEachIndexed { index, notice ->
                if (index > 0) RowDivider()
                TwoLines(notice.name, notice.licence, Modifier.fillMaxWidth().clickableRow { links.openUri(notice.url) }.padding(CardContentPadding))
            }
        }
    }
}
