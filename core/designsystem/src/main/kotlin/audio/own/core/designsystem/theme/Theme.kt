package audio.own.core.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

val LocalPalette = staticCompositionLocalOf { Tokens.light }

/**
 * True when the system's "Remove animations" is on (animator duration scale 0) or a test asked for
 * stillness. Moving brand elements stand still then, as Reduce Motion does on iOS — and a mark
 * that never stops moving would never let a UI test see the screen as idle.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** The app's colours as Material 3 roles, so stock components pick them up. No dynamic colour. */
fun colorScheme(palette: Palette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.accent,
        onPrimary = Color.Black,
        primaryContainer = palette.accent.copy(alpha = 0.22f).compositeOver(palette.background),
        onPrimaryContainer = palette.foreground,
        secondary = palette.accent,
        secondaryContainer = palette.accent.copy(alpha = 0.22f).compositeOver(palette.background),
        onSecondaryContainer = palette.foreground,
        background = palette.background,
        onBackground = palette.foreground,
        surface = palette.background,
        onSurface = palette.foreground,
        surfaceVariant = palette.backgroundAlt,
        onSurfaceVariant = palette.muted,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.backgroundAlt,
        surfaceContainer = palette.backgroundAlt,
        surfaceContainerHigh = palette.card,
        surfaceContainerHighest = palette.card,
        outline = palette.border,
        outlineVariant = palette.border,
        error = palette.error,
    )
} else {
    lightColorScheme(
        primary = palette.accent,
        onPrimary = Color.White,
        primaryContainer = palette.accent.copy(alpha = 0.14f).compositeOver(palette.background),
        onPrimaryContainer = palette.foreground,
        secondary = palette.accent,
        secondaryContainer = palette.accent.copy(alpha = 0.14f).compositeOver(palette.background),
        onSecondaryContainer = palette.foreground,
        background = palette.background,
        onBackground = palette.foreground,
        surface = palette.background,
        onSurface = palette.foreground,
        surfaceVariant = palette.backgroundAlt,
        onSurfaceVariant = palette.muted,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.backgroundAlt,
        surfaceContainer = palette.backgroundAlt,
        surfaceContainerHigh = palette.backgroundAlt,
        surfaceContainerHighest = palette.border,
        outline = palette.border,
        outlineVariant = palette.border,
        error = palette.error,
    )
}

/** Figures of equal width, for every clock and track number (iOS `.monospacedDigit()`). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun typography(): Typography {
    val base = Typography()
    // iOS titles are bold; Material's are regular.
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun OwnAudioTheme(
    accent: AccentChoice = AccentChoice.APP,
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) Tokens.dark else Tokens.light
    val palette = base.copy(accent = accent.color(darkTheme))
    val context = LocalContext.current
    val still = reduceMotion ?: remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val r = AppBrand.radii
    CompositionLocalProvider(LocalPalette provides palette, LocalReduceMotion provides still) {
        MaterialTheme(
            colorScheme = colorScheme(palette, darkTheme),
            typography = typography(),
            shapes = Shapes(
                small = RoundedCornerShape(r.rowCover),
                medium = RoundedCornerShape(r.cover),
                large = RoundedCornerShape(r.card),
                extraLarge = RoundedCornerShape(r.sheetTop),
            ),
            content = content,
        )
    }
}
