package audio.own.podcastplayer.podcasts

import android.content.res.Resources
import android.text.format.DateFormat
import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import audio.own.podcast.library.Episode
import audio.own.podcast.library.ListeningProgress
import audio.own.podcastplayer.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Durations, dates and positions as the screens say them; the iOS app's `ProgressText`. */
object ProgressText {
    /** "1 h 12 min", "38 min". */
    fun length(res: Resources, ms: Long): String {
        val minutes = (ms / 60_000.0).roundToLong()
        val hours = minutes / 60
        if (hours > 0) {
            val rest = (minutes % 60).toInt()
            return if (rest == 0) res.getString(R.string.length_hours, hours.toInt()) else res.getString(R.string.length_hours_minutes, hours.toInt(), rest)
        }
        return res.getString(R.string.length_minutes, maxOf(minutes, 1).toInt())
    }

    /** "1:02:07" or "3:07". */
    fun clock(ms: Long): String {
        val total = maxOf(ms, 0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "16 Oct · 48 min", "16 Oct · 12 min left", "16 Oct · Played"; the year when not this one. */
    fun line(res: Resources, episode: Episode, progress: ListeningProgress?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        episode.publishedAt?.let { parts += date(res, it, now, zone) }
        val duration = episode.durationMs
        when {
            progress?.played == true -> parts += res.getString(R.string.played)
            progress != null && progress.positionMs > 0 && duration != null && duration > progress.positionMs ->
                parts += res.getString(R.string.time_left, length(res, duration - progress.positionMs))
            duration != null && duration > 0 -> parts += length(res, duration)
        }
        return parts.joinToString(" · ")
    }

    fun date(res: Resources, at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val locale = res.configuration.locales[0] ?: Locale.getDefault()
        val day = at.atZone(zone)
        val skeleton = if (day.year == now.atZone(zone).year) "dMMM" else "dMMMyyyy"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
    }

    /**
     * "2 h 10 min left" at the speed the episode plays at; "Played" once nothing is left, where
     * [length]'s floor of a minute would say "1 min left" at the very end.
     */
    fun leftAtSpeed(res: Resources, ms: Long, rate: Float): String {
        if (ms < END_MS) return res.getString(R.string.played)
        return res.getString(R.string.time_left, length(res, (ms / maxOf(rate, 0.1f).toDouble()).roundToLong()))
    }

    /** Less than this before the end is the end: a player stops a few frames short of it. */
    private const val END_MS = 1_000L
}

/** Plain text with its web addresses made into links, opened by the system. */
fun linkedText(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val matcher = Patterns.WEB_URL.matcher(text)
    val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    while (matcher.find()) {
        val found = matcher.group()
        // Only full addresses: a bare "example.com" in prose is left as words.
        if (!found.startsWith("http://") && !found.startsWith("https://")) continue
        addLink(LinkAnnotation.Url(found, styles), matcher.start(), matcher.end())
    }
}
