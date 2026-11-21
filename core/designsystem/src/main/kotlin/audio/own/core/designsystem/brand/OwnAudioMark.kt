package audio.own.core.designsystem.brand

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.LocalReduceMotion
import audio.own.core.designsystem.theme.Tokens

private val DANCER_ANGLES = listOf(0f, 72f, 144f, 216f, 288f)
private val COMPACT_THRESHOLD = 24.dp

/**
 * The own.audio mark: a pulsing core, two slowly spinning arcs and five dancers waltzing around
 * it — the construction of the app icon, in motion, on a 100-unit grid. [tribe] is own.audio
 * itself (one dancer per product, in its colour); the default is this app speaking, every dot in
 * its accent. Below 24 dp the choreography stops being legible and five still dots stand in.
 */
@Composable
fun OwnAudioMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    colors: List<Color> = listOf(LocalPalette.current.accent),
    core: Color = LocalPalette.current.accent,
) {
    val still = LocalReduceMotion.current
    val arcColor = LocalPalette.current.markArcs
    val transition = rememberInfiniteTransition(label = "mark")
    val pulse by transition.animateFloat(
        1f, if (still) 1f else 1.25f,
        infiniteRepeatable(tween(420, easing = CubicBezierEasing(0.21f, 0.98f, 0.6f, 0.99f)), RepeatMode.Reverse),
        label = "pulse",
    )
    val spin by transition.animateFloat(
        0f, if (still) 0f else 360f,
        infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val sway by transition.animateFloat(
        0f, if (still) 0f else 1f,
        infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    // Still, the dancers rest where the iOS mark starts (−8°, 0.98).
    val swayAngle = -8f + 16f * sway
    val swayScale = 0.98f + 0.04f * sway

    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val u = this.size.minDimension / 100f
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        if (size >= COMPACT_THRESHOLD) {
            rotate(spin, centre) { arcs(u, centre, arcColor) }
            rotate(swayAngle, centre) {
                scale(swayScale, centre) {
                    DANCER_ANGLES.forEachIndexed { i, angle ->
                        rotate(angle, centre) { dancer(u, centre, colors[i % colors.size]) }
                    }
                }
            }
        } else {
            DANCER_ANGLES.forEachIndexed { i, angle ->
                rotate(angle, centre) {
                    drawCircle(colors[i % colors.size], 4.5f * u, centre + Offset(0f, -36f * u))
                }
            }
        }
        drawCircle(core, 11f * u * pulse, centre)
    }
}

/** own.audio's own mark: the family colours and the violet core. */
@Composable
fun OwnAudioMarkTribe(modifier: Modifier = Modifier, size: Dp = 64.dp) =
    OwnAudioMark(modifier, size, colors = Tokens.family, core = Tokens.markCore)

/** The master mark's two short arcs under the core, from circles centred a little above the mark's. */
private fun DrawScope.arcs(u: Float, centre: Offset, color: Color) {
    arc(centre + Offset(0f, -1.23f * u), 20f * u, 0.1151f, 0.3849f, 3.5f * u, color)
    arc(centre + Offset(0f, -0.4f * u), 30f * u, 0.1190f, 0.3810f, 2.5f * u, color.copy(alpha = 0.6f * color.alpha))
}

// SwiftUI's Circle().trim starts at 3 o'clock and runs clockwise, as Compose's drawArc does.
private fun DrawScope.arc(c: Offset, radius: Float, from: Float, to: Float, width: Float, color: Color) {
    drawArc(
        color = color,
        startAngle = from * 360f,
        sweepAngle = (to - from) * 360f,
        useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/** One dancer: a head and two hands, the icon's three-dot cluster. */
private fun DrawScope.dancer(u: Float, centre: Offset, color: Color) {
    drawCircle(color, 5f * u, centre + Offset(0f, -36f * u))
    drawCircle(color, 2.5f * u, centre + Offset(-8.5f * u, -35f * u))
    drawCircle(color, 2.5f * u, centre + Offset(8.5f * u, -35f * u))
}
