package audio.own.kit.feed

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The lenient readers for the values feeds get wrong: dates in a dozen forms, durations in four,
 * numbers with stray spaces.
 */
object FeedValues {
    // Zone names the formatters below don't read, or read differently across JDKs and Android.
    private val namedZones = listOf(
        " UT" to " +0000", " GMT" to " +0000", " Z" to " +0000",
        " EDT" to " -0400", " EST" to " -0500", " CDT" to " -0500", " CST" to " -0600",
        " MDT" to " -0600", " MST" to " -0700", " PDT" to " -0700", " PST" to " -0800",
        " CEST" to " +0200", " CET" to " +0100",
    )

    // The weekday is dropped before parsing: feeds get it wrong often enough, and java.time
    // refuses a date whose weekday doesn't match.
    private val weekday = Regex("^[A-Za-z]+,?\\s+(?=\\d)")

    private class Pattern(val formatter: DateTimeFormatter, val hasZone: Boolean, val hasTime: Boolean)

    private val rfc822: List<Pattern> = listOf(
        "d MMM uuuu HH:mm:ss [xxx][xx]" to true, "d MMM uuuu HH:mm [xxx][xx]" to true,
        "d MMMM uuuu HH:mm:ss [xxx][xx]" to true, "d MMMM uuuu HH:mm [xxx][xx]" to true,
        "d MMM uuuu HH:mm:ss" to false, "d MMM uuuu HH:mm" to false, "d MMM uuuu" to false,
        "uuuu-MM-dd HH:mm:ss [xxx][xx]" to true, "uuuu-MM-dd HH:mm:ss" to false,
    ).map { (format, zone) ->
        Pattern(
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(Locale.US),
            hasZone = zone,
            hasTime = format.contains("HH"),
        )
    }

    /**
     * RFC 822 as feeds actually write it — with or without the weekday or seconds, with named or
     * numeric zones, single-digit days — and ISO 8601 for Atom and the careless.
     */
    fun date(text: String?): Instant? {
        var value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        isoDate(value)?.let { return it }
        for ((name, offset) in namedZones) {
            if (value.endsWith(name)) {
                value = value.dropLast(name.length) + offset
                break
            }
        }
        value = value.replace(weekday, "").replace(Regex("\\s+"), " ")
        for (pattern in rfc822) {
            try {
                return when {
                    pattern.hasZone -> OffsetDateTime.parse(value, pattern.formatter).toInstant()
                    pattern.hasTime -> LocalDateTime.parse(value, pattern.formatter).toInstant(ZoneOffset.UTC)
                    else -> LocalDate.parse(value, pattern.formatter).atStartOfDay().toInstant(ZoneOffset.UTC)
                }
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private val isoWithCompactOffset: DateTimeFormatter =
        DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHMM", "Z").toFormatter(Locale.US)

    private fun isoDate(text: String): Instant? {
        if (!text.first().isDigit() || !text.contains('-')) return null
        if (text.length == 10) {
            return try {
                LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
        }
        if (!text.contains('T')) return null
        for (formatter in listOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME, isoWithCompactOffset)) {
            try {
                return OffsetDateTime.parse(text, formatter).toInstant()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    /**
     * `itunes:duration` in every form seen: "1:02:03", "62:03", "3723", "3723.5", "1:2:3".
     * Null for zero, junk, or anything over a week.
     */
    fun durationMs(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = value.split(':').map { it.trim() }
        if (parts.size !in 1..3) return null
        var seconds = 0.0
        for (part in parts) {
            val number = part.toPlainDouble() ?: return null
            if (number < 0) return null
            seconds = seconds * 60 + number
        }
        if (!(seconds > 0 && seconds < 7 * 86_400)) return null
        return (seconds * 1000).roundToLong()
    }

    /** Chapter times: "01:02:03.500", "62:03", "3723.5", "3723". */
    fun timeMs(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value == "0" || value == "00:00:00" || value == "00:00:00.000") return 0
        return durationMs(value)
    }

    fun int(text: String?): Int? = text?.trim()?.toIntOrNull()

    /** "yes", "true", "explicit" are explicit; "no", "false", "clean" are not. */
    fun flag(text: String?): Boolean {
        val value = text?.trim()?.lowercase() ?: return false
        return value == "yes" || value == "true" || value == "explicit"
    }

    /** A URL as feeds write them: trimmed, spaces escaped, protocol-relative made https. */
    fun url(text: String?): String? {
        var value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("//")) value = "https:$value"
        value = value.replace(" ", "%20")
        if (!httpUrl.matches(value)) return null
        return value
    }

    private val httpUrl = Regex("(?i)^https?://[^/?#\\s]+([/?#][^\\s]*)?$")

    // Kotlin's toDouble also takes "1e3", "NaN" and hex floats; feeds mean none of them.
    private fun String.toPlainDouble(): Double? =
        if (isNotEmpty() && all { it.isDigit() || it == '.' } && count { it == '.' } <= 1 && this != ".") toDoubleOrNull() else null

    /** The host of a URL, for a show without a title. */
    internal fun host(url: String?): String? =
        url?.let { Regex("(?i)^https?://(?:[^@/?#]*@)?([^:/?#]+)").find(it)?.groupValues?.get(1) }
}
