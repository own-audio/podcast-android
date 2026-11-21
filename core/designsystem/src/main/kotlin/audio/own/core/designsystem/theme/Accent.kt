package audio.own.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// What differs between the three apps. Tier 2 in tools/sibling-drift.sh: every other file in
// :core:designsystem is the same in music-android, book-android and podcast-android.

data class Radii(
    val cover: Dp,
    val miniPlayer: Dp,
    val card: Dp,
    val miniCover: Dp,
    val rowCover: Dp,
    val sheetTop: Dp,
)

object AppBrand {
    val accentLight = Color(0xFF0A6E9C)
    val accentDark = Color(0xFF29ABE2)
    val radii = Radii(cover = 12.dp, miniPlayer = 16.dp, card = 12.dp, miniCover = 8.dp, rowCover = 8.dp, sheetTop = 28.dp)
    const val COVER_CROSSFADE_MS = 200
    val wordmarkWords = listOf("audio", "podcast", "family", "episode", "show", "feed", "talk", "morning")

    /** Settings → Accent Color, in the iOS app's order. */
    val accentSwatches = listOf(
        AccentChoice.APP, AccentChoice.BLUE, AccentChoice.INDIGO, AccentChoice.PURPLE, AccentChoice.RED,
        AccentChoice.ORANGE, AccentChoice.YELLOW, AccentChoice.GREEN, AccentChoice.TEAL, AccentChoice.BROWN,
        AccentChoice.GRAPHITE,
    )
}
