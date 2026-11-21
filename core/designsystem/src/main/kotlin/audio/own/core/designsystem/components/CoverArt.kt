package audio.own.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext

/**
 * A cover with the app's placeholder underneath. [model] is anything Coil loads; callers pass an
 * `ImageRequest` whose memory and disk cache keys name the record and image, not the URL, because
 * own.audio's image links carry a signature that changes (docs/android-foundation.md §7.3).
 */
@Composable
fun CoverArt(
    model: Any?,
    size: Dp,
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
    radius: Dp = AppBrand.radii.cover,
    contentDescription: String? = null,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(palette.backgroundAlt)
            .border(0.5.dp, palette.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val fallback = @Composable {
            Icon(placeholder, contentDescription = null, tint = palette.muted, modifier = Modifier.size(size * 0.4f))
        }
        if (model == null) {
            fallback()
        } else {
            val request = model as? ImageRequest ?: ImageRequest.Builder(LocalContext.current)
                .data(model)
                .crossfade(AppBrand.COVER_CROSSFADE_MS)
                .build()
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}
