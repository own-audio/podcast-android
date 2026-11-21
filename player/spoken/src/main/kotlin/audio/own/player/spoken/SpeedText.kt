package audio.own.player.spoken

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * A speed as the listener reads it: "1×", "1.25×", "0.75×", with the locale's decimal sign
 * ("0,75×" in Czech). Up to two decimals: a two-significant-digit format showed 1.75 as "1.8×".
 */
object SpeedText {
    fun text(speed: Float, locale: Locale = Locale.getDefault()): String = number(speed, locale) + "×"

    /** The number alone, for what TalkBack reads. */
    fun number(speed: Float, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
            isGroupingUsed = false
        }
        // Through the float's shortest decimal form: 1.15f is 1.149999976… in binary, and
        // formatting that directly could round it down to "1.14".
        val exact = BigDecimal(speed.toString()).setScale(2, RoundingMode.HALF_UP)
        return format.format(exact)
    }
}
