package audio.own.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The neutrals every own.audio app shares, the values of the iOS apps' Theme.swift. The accent is
 * the one colour that differs per app (Accent.kt). docs/design-tokens.json mirrors these values and
 * TokenTest keeps the two in step.
 */
data class Palette(
    val background: Color,
    val backgroundAlt: Color,
    val card: Color,
    val foreground: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val error: Color,
    val markArcs: Color,
    val accent: Color,
)

object Tokens {
    val light = Palette(
        background = Color(0xFFFFFFFF),
        backgroundAlt = Color(0xFFF7F7F8),
        card = Color(0xFFFFFFFF),
        foreground = Color(0xFF1D1D1F),
        muted = Color(0xFF6E6E73),
        border = Color(0xFFE5E5E7),
        success = Color(0xFF1E8E3E),
        error = Color(0xFFC41E3A),
        markArcs = Color(0xFFD2D2D7),
        accent = AppBrand.accentLight,
    )
    val dark = Palette(
        background = Color(0xFF000000),
        backgroundAlt = Color(0xFF0C0C0E),
        card = Color(0xFF131316),
        foreground = Color(0xFFF5F5F7),
        muted = Color(0xFF98989D),
        border = Color(0xFF1D1D1F),
        success = Color(0xFF30D158),
        error = Color(0xFFFF453A),
        markArcs = Color(0xFF2C2C2E),
        accent = AppBrand.accentDark,
    )

    /** own.audio's family, clockwise from the top of the mark: one dancer per product. */
    val family = listOf(Color(0xFFFABB05), Color(0xFF29ABE2), Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759))
    val markCore = Color(0xFF6E44FF)

    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        val screenEdge = 16.dp
    }

    /** Android's minimum touch target (iOS uses 44 pt). */
    val minTarget = 48.dp
}
