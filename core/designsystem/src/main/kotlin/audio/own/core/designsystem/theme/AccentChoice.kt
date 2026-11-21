package audio.own.core.designsystem.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import audio.own.core.strings.R

/**
 * The colour buttons, links and highlights take: the app's own unless the listener picks another
 * in Settings. The icon, the widgets and the player keep the app's colour. The other swatches are
 * iOS's system colours, so a listener who picks "Teal" on both phones sees the same teal.
 */
enum class AccentChoice(val key: String, @StringRes val title: Int, val light: Color?, val dark: Color?) {
    APP("app", R.string.accent_app, null, null),
    BLUE("blue", R.string.accent_blue, Color(0xFF007AFF), Color(0xFF0A84FF)),
    INDIGO("indigo", R.string.accent_indigo, Color(0xFF5856D6), Color(0xFF5E5CE6)),
    PURPLE("purple", R.string.accent_purple, Color(0xFFAF52DE), Color(0xFFBF5AF2)),
    PINK("pink", R.string.accent_pink, Color(0xFFFF2D55), Color(0xFFFF375F)),
    RED("red", R.string.accent_red, Color(0xFFFF3B30), Color(0xFFFF453A)),
    ORANGE("orange", R.string.accent_orange, Color(0xFFFF9500), Color(0xFFFF9F0A)),
    YELLOW("yellow", R.string.accent_yellow, Color(0xFFFFCC00), Color(0xFFFFD60A)),
    GREEN("green", R.string.accent_green, Color(0xFF34C759), Color(0xFF30D158)),
    TEAL("teal", R.string.accent_teal, Color(0xFF30B0C7), Color(0xFF40C8E0)),
    BROWN("brown", R.string.accent_brown, Color(0xFFA2845E), Color(0xFFAC8E68)),
    GRAPHITE("graphite", R.string.accent_graphite, Color(0xFF8E8E93), Color(0xFF8E8E93)),
    ;

    fun color(dark: Boolean): Color = when {
        this == APP -> if (dark) AppBrand.accentDark else AppBrand.accentLight
        dark -> this.dark!!
        else -> light!!
    }

    companion object {
        /** The DataStore key the choice is saved under, the same name as the iOS setting. */
        const val STORAGE_KEY = "accentColor"

        fun fromKey(key: String?): AccentChoice = entries.firstOrNull { it.key == key } ?: APP
    }
}
