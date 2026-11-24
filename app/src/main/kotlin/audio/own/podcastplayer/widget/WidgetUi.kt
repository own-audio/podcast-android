@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.podcastplayer.widget

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.CommandButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import audio.own.core.designsystem.theme.AppBrand
import audio.own.podcastplayer.R
import audio.own.player.core.SkipCommands

/**
 * The widgets' look: the wallpaper's colours (Material You) for the surface and text, the app's
 * own colour for the play button and the progress, the system's corner radius. Below Android 12,
 * where there are no wallpaper colours, a neutral scheme around the app's colour.
 */
object WidgetLook {
    val accent = ColorProvider(day = AppBrand.accentLight, night = AppBrand.accentDark)
    val onAccent = ColorProvider(day = Color.White, night = Color(0xFF00202E))
    val accentSoft = ColorProvider(day = AppBrand.accentLight.copy(alpha = 0.14f), night = AppBrand.accentDark.copy(alpha = 0.18f))

    private val fallback: ColorProviders = ColorProviders(
        light = lightColorScheme(
            primary = AppBrand.accentLight,
            surface = Color(0xFFF7F7F8),
            onSurface = Color(0xFF1D1D1F),
            surfaceVariant = Color(0xFFE6E6EA),
            onSurfaceVariant = Color(0xFF5F5F66),
            secondaryContainer = Color(0xFFE3EEF3),
            background = Color(0xFFF7F7F8),
        ),
        dark = darkColorScheme(
            primary = AppBrand.accentDark,
            surface = Color(0xFF17171A),
            onSurface = Color(0xFFF5F5F7),
            surfaceVariant = Color(0xFF2C2C31),
            onSurfaceVariant = Color(0xFFA9A9B0),
            secondaryContainer = Color(0xFF1C2A31),
            background = Color(0xFF17171A),
        ),
    )

    /** Wallpaper colours from Android 12; [fallback] before. */
    val colors: ColorProviders
        @Composable get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallback

    @Composable
    fun Theme(content: @Composable () -> Unit) = GlanceTheme(colors = colors, content = content)

    /** The widget's surface, with the launcher's own corner radius from Android 12. */
    @Composable
    fun surface(): GlanceModifier = GlanceModifier.fillMaxSize().appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius) else GlanceModifier.cornerRadius(20.dp))

    /** The rounding of things inside the widget, which the system sets from Android 12. */
    fun inner(modifier: GlanceModifier): GlanceModifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modifier.cornerRadius(android.R.dimen.system_app_widget_inner_radius) else modifier.cornerRadius(12.dp)

    val icons = Icons(
        play = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_PLAY),
        pause = CommandButton.getIconResIdForIconConstant(CommandButton.ICON_PAUSE),
    )

    data class Icons(val play: Int, val pause: Int) {
        /** The numbered arrows the notification shows, for the listener's skip intervals. */
        fun back(secs: Int): Int = CommandButton.getIconResIdForIconConstant(SkipCommands.backIcon(secs))

        fun forward(secs: Int): Int = CommandButton.getIconResIdForIconConstant(SkipCommands.forwardIcon(secs))
    }
}

@Composable
fun WidgetTitle(text: String, size: TextUnit = 15.sp, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) =
    Text(text, modifier = modifier, maxLines = maxLines, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size, fontWeight = FontWeight.Medium))

@Composable
fun WidgetSubtitle(text: String, size: TextUnit = 13.sp, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) =
    Text(text, modifier = modifier, maxLines = maxLines, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size))

/** "Continue" above where you left off, in the app's colour. */
@Composable
fun WidgetEyebrow(text: String) =
    Text(text, maxLines = 1, style = TextStyle(color = WidgetLook.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))

/** A cover, or the app's microphone on a soft tile when there is none. */
@Composable
fun WidgetCover(picture: ImageProvider?, size: Dp, modifier: GlanceModifier = GlanceModifier) {
    if (picture != null) {
        Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = WidgetLook.inner(modifier.size(size)))
    } else {
        Box(WidgetLook.inner(modifier.size(size)).background(WidgetLook.accentSoft), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.ic_widget_mic),
                contentDescription = null,
                colorFilter = ColorFilter.tint(WidgetLook.accent),
                modifier = GlanceModifier.size(size * 0.42f),
            )
        }
    }
}

/** Play or pause: the one control in the app's colour. 48 dp, the smallest comfortable target. */
@Composable
fun WidgetPlayButton(isPlaying: Boolean, onClick: Action, label: String, size: Dp = 48.dp) = CircleIconButton(
    imageProvider = ImageProvider(if (isPlaying) WidgetLook.icons.pause else WidgetLook.icons.play),
    contentDescription = label,
    onClick = onClick,
    modifier = GlanceModifier.size(size).semantics { testTag = if (isPlaying) "pause" else "play" },
    backgroundColor = WidgetLook.accent,
    contentColor = WidgetLook.onAccent,
)

/** A skip: no background, 48 dp. [onClick] null shows it dimmed, without an action. */
@Composable
fun WidgetControl(icon: Int, label: String, onClick: Action?, tag: String) = CircleIconButton(
    imageProvider = ImageProvider(icon),
    contentDescription = label,
    onClick = onClick ?: NoAction,
    enabled = onClick != null,
    modifier = GlanceModifier.size(48.dp).semantics { testTag = tag },
    backgroundColor = null,
    contentColor = if (onClick != null) GlanceTheme.colors.onSurface else GlanceTheme.colors.outline,
)

/** A thin progress line in the app's colour; [width] null fills the row. */
@Composable
fun WidgetProgress(progress: Float, width: Dp? = null) = LinearProgressIndicator(
    progress = progress,
    modifier = (if (width != null) GlanceModifier.width(width) else GlanceModifier.fillMaxWidth()).height(4.dp),
    color = WidgetLook.accent,
    backgroundColor = GlanceTheme.colors.surfaceVariant,
)

/** A disabled button still needs an action; this one is never sent. */
private val NoAction: Action = androidx.glance.appwidget.action.actionRunCallback<WidgetNoop>()

class WidgetNoop : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId, parameters: androidx.glance.action.ActionParameters) = Unit
}
