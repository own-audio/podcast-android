package audio.own.core.designsystem.brand

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/**
 * "own." with a slot that cycles through what own.audio is for. Every word is laid out invisibly
 * in the slot so it keeps the widest one's width and "own." never shifts.
 */
@Composable
fun RotatingWordmark(
    modifier: Modifier = Modifier,
    words: List<String> = AppBrand.wordmarkWords,
    intervalMs: Long = 1800,
) {
    val palette = LocalPalette.current
    val still = LocalReduceMotion.current
    var index by remember { mutableIntStateOf(0) }
    if (!still && words.size > 1) {
        LaunchedEffect(words) {
            while (true) {
                delay(intervalMs)
                index = (index + 1) % words.size
            }
        }
    }
    val style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
    val shift = with(LocalDensity.current) { 16.dp.roundToPx() }
    val easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    Row(modifier.clearAndSetSemantics { contentDescription = "own.audio" }) {
        Text("own", style = style, color = palette.foreground)
        Text(".", style = style, color = palette.accent)
        Box {
            words.forEach { Text(it, style = style, modifier = Modifier.alpha(0f)) }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (slideInVertically(tween(600, easing = easing)) { shift } + fadeIn(tween(600, easing = easing)))
                        .togetherWith(slideOutVertically(tween(600, easing = easing)) { -shift } + fadeOut(tween(600, easing = easing)))
                },
                label = "wordmark",
            ) { i -> Text(words[i], style = style, color = palette.muted) }
        }
    }
}
