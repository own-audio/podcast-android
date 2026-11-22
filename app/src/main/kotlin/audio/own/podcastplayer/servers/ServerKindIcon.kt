package audio.own.podcastplayer.servers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.asin
import kotlin.math.sqrt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.podcastplayer.R
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File

/** The kind's name: product names stay as they are, "gPodder sync" is translated. */
@Composable
fun kindTitle(kind: String): String = when (kind) {
    ServerKinds.OWN_AUDIO -> "own.audio"
    ServerKinds.AUDIOBOOKSHELF -> "Audiobookshelf"
    ServerKinds.GPODDER -> stringResource(R.string.kind_gpodder)
    else -> kind
}

/**
 * The server's kind at a glance. own.audio's mark ships with the app; Audiobookshelf's and
 * gPodder's artwork is GPL-licensed and the app carries none of it: [icon] is the one the server
 * itself serves ([ServerIcons]), and a symbol on a rounded tile stands in until it has loaded.
 */
@Composable
fun ServerKindIcon(kind: String, modifier: Modifier = Modifier, size: Dp = 28.dp, icon: File? = null) {
    val shape = RoundedCornerShape(size * 0.22f)
    if (icon != null && kind != ServerKinds.OWN_AUDIO) {
        AsyncImage(model = icon, contentDescription = kindTitle(kind), contentScale = ContentScale.Fit, modifier = modifier.size(size).clip(shape))
        return
    }
    if (kind == ServerKinds.OWN_AUDIO) {
        val description = kindTitle(kind)
        OwnAudioServerLogo(modifier.size(size).clip(shape).semantics { contentDescription = description })
        return
    }
    val symbol = when (kind) {
        ServerKinds.AUDIOBOOKSHELF -> Icons.AutoMirrored.Filled.LibraryBooks
        ServerKinds.GPODDER -> Icons.Filled.Sync
        else -> Icons.Filled.Dns
    }
    Box(modifier.size(size).clip(shape).background(LocalPalette.current.backgroundAlt), contentAlignment = Alignment.Center) {
        Icon(symbol, contentDescription = kindTitle(kind), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * podcast-ios's ServerLogo.svg (100 × 100): the family around a purple centre, its arcs in the
 * text colour so they show in light and dark alike.
 */
@Composable
private fun OwnAudioServerLogo(modifier: Modifier) {
    // As the SVG and its -dark twin: dark arcs on a light page, light ones on a dark page.
    val arcs = if (LocalPalette.current.background.luminance() > 0.5f) Color(0xFF2C2C2E) else Color(0xFFD2D2D7)
    Canvas(modifier) {
        val k = this.size.minDimension / 100f
        drawCircle(Color(0xFF6E44FF), radius = 11 * k, center = Offset(50 * k, 50 * k))
        // "M 35 62 A 20 20 0 0 0 65 62" and "M 28 70 A 30 30 0 0 0 72 70": the lower arcs of circles through those points.
        arc(35f, 62f, 65f, 20f, 3.5f, arcs, 1f, k)
        arc(28f, 70f, 72f, 30f, 2.5f, arcs, 0.6f, k)
        LOGO_COLOURS.forEachIndexed { i, colour ->
            rotate(72f * i, pivot = Offset(50 * k, 50 * k)) {
                drawCircle(colour, radius = 5 * k, center = Offset(50 * k, 14 * k))
                drawCircle(colour, radius = 2.5f * k, center = Offset(41.5f * k, 15 * k))
                drawCircle(colour, radius = 2.5f * k, center = Offset(58.5f * k, 15 * k))
            }
        }
    }
}

private val LOGO_COLOURS = listOf(Color(0xFFFABB05), Color(0xFF29ABE2), Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759))

/** The SVG's small arc from (x1, y) to (x2, y), bulging downwards, of radius [r]. */
private fun DrawScope.arc(x1: Float, y: Float, x2: Float, r: Float, width: Float, colour: Color, alpha: Float, k: Float) {
    val half = (x2 - x1) / 2
    val cx = x1 + half
    val cy = y - sqrt(r * r - half * half)
    val sweep = Math.toDegrees(2 * asin(half / r).toDouble()).toFloat()
    drawArc(
        colour,
        startAngle = 90f - sweep / 2,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset((cx - r) * k, (cy - r) * k),
        size = Size(2 * r * k, 2 * r * k),
        alpha = alpha,
        style = Stroke(width = width * k, cap = StrokeCap.Round),
    )
}
