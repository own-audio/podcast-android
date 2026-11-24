package audio.own.podcastplayer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import audio.own.podcastplayer.R
import kotlin.math.roundToInt
import audio.own.core.strings.R as SR

/** The player strip (4×1): picture, episode and show, back, play/pause and forward. */
class PlayerStripWidget : PodcastWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(TINY, ROW, TALL_NARROW, TALL))
    override val shelfCovers = false
    override val coverPx = 320

    @Composable
    override fun Content(state: WidgetState) = PlayerStripContent(state)

    companion object {
        val TINY = DpSize(110.dp, 40.dp)
        val ROW = DpSize(220.dp, 40.dp)

        /** Stretched taller, the strip becomes the big player rather than a lonely row. */
        val TALL_NARROW = DpSize(110.dp, 110.dp)
        val TALL = DpSize(220.dp, 110.dp)
    }
}

class PlayerStripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerStripWidget()
}

/** The big player (4×2): a large picture, episode, show, progress and the controls; Up Next when nothing plays. */
class NowPlayingWidget : PodcastWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, WIDE_TALL, LARGE, LARGE_TALL))
    override val shelfCovers = false
    override val coverPx = 320

    @Composable
    override fun Content(state: WidgetState) = NowPlayingContent(state)

    companion object {
        val NARROW = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(220.dp, 110.dp)
        val WIDE_TALL = DpSize(220.dp, 170.dp)
        val LARGE = DpSize(300.dp, 140.dp)
        val LARGE_TALL = DpSize(300.dp, 220.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** What either player shows: the episode playing, or the one up next. */
private data class Showing(
    val title: String,
    val show: String,
    val cover: ImageProvider?,
    val isPlaying: Boolean,
    val progress: Float,
    val remainingMs: Long,
    /** Something is in the player: pause and the skips work. */
    val loaded: Boolean,
)

private fun WidgetState.showing(): Showing? = when {
    player != null -> Showing(player.title, player.show, player.cover?.let(covers::get), player.isPlaying, player.progress, player.remainingMs, loaded = true)
    resume != null -> Showing(resume.title, resume.show, resume.cover?.let(covers::get), false, resume.fraction.toFloat(), resume.remainingMs, loaded = false)
    else -> null
}

/** "Continue · 42%" above an episode under way, "Up Next" above a new one. */
private fun continueLabel(context: Context, showing: Showing): String {
    val percent = (showing.progress * 100).roundToInt()
    return if (percent > 0) context.getString(R.string.widget_continue_percent, percent) else context.getString(R.string.up_next)
}

@Composable
fun PlayerStripContent(state: WidgetState) {
    val size = LocalSize.current
    if (size.height >= PlayerStripWidget.TALL_NARROW.height) return NowPlayingContent(state)
    val context = LocalContext.current
    val showing = state.showing()
    Row(WidgetLook.surface().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showing == null) return@Row NothingNew(horizontal = true)
        Row(GlanceModifier.defaultWeight().fillMaxHeight().clickable(open(context, state, showing)).semantics { testTag = "open" }, verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(showing.cover, min(size.height - 16.dp, 56.dp).coerceAtLeast(32.dp))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                if (!showing.loaded) WidgetEyebrow(continueLabel(context, showing))
                WidgetTitle(showing.title)
                if (showing.show.isNotEmpty()) WidgetSubtitle(showing.show)
            }
        }
        Spacer(GlanceModifier.width(4.dp))
        Controls(state, showing, withSkips = size.width >= PlayerStripWidget.ROW.width, spread = false)
    }
}

@Composable
fun NowPlayingContent(state: WidgetState) {
    val size = LocalSize.current
    val context = LocalContext.current
    val showing = state.showing()
    Box(WidgetLook.surface().padding(14.dp)) {
        if (showing == null) return@Box NothingNew(horizontal = false)
        val open = open(context, state, showing)
        if (size.width < NowPlayingWidget.WIDE.width) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth()) {
                    WidgetCover(showing.cover, min(size.width - 28.dp - 60.dp, size.height / 2), modifier = GlanceModifier.clickable(open))
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetPlayButton(showing.isPlaying, playOrPause(context, showing), playLabel(context, showing))
                }
                Spacer(GlanceModifier.defaultWeight())
                Column(GlanceModifier.fillMaxWidth().clickable(open).semantics { testTag = "open" }) {
                    if (!showing.loaded) WidgetEyebrow(continueLabel(context, showing))
                    WidgetTitle(showing.title)
                    if (showing.show.isNotEmpty()) WidgetSubtitle(showing.show)
                }
            }
            return@Box
        }
        val cover: Dp = min(min(size.height - 28.dp, size.width * 0.42f), 180.dp)
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(showing.cover, cover, modifier = GlanceModifier.clickable(open))
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(open).semantics { testTag = "open" }) {
                    if (!showing.loaded) WidgetEyebrow(continueLabel(context, showing))
                    WidgetTitle(showing.title, size = 16.sp, maxLines = if (size.height >= NowPlayingWidget.WIDE_TALL.height) 2 else 1)
                    if (showing.show.isNotEmpty()) WidgetSubtitle(showing.show, size = 14.sp)
                    if (size.height >= NowPlayingWidget.LARGE.height && showing.remainingMs > 0) WidgetSubtitle(timeLeft(context, showing.remainingMs))
                }
                if (showing.loaded || showing.progress > 0f) {
                    WidgetProgress(showing.progress)
                    Spacer(GlanceModifier.height(4.dp))
                }
                Controls(state, showing, withSkips = true, spread = true)
            }
        }
    }
}

@Composable
private fun Controls(state: WidgetState, showing: Showing, withSkips: Boolean, spread: Boolean) {
    val context = LocalContext.current
    Row(if (spread) GlanceModifier.fillMaxWidth() else GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
        if (withSkips) {
            WidgetControl(
                WidgetLook.icons.back(state.skipBackSecs),
                context.getString(R.string.skip_back_seconds, state.skipBackSecs),
                WidgetActions.command(WidgetActions.Command.SKIP_BACK).takeIf { showing.loaded },
                tag = "back",
            )
            if (spread) Spacer(GlanceModifier.defaultWeight())
        }
        WidgetPlayButton(showing.isPlaying, playOrPause(context, showing), playLabel(context, showing))
        if (withSkips) {
            if (spread) Spacer(GlanceModifier.defaultWeight())
            WidgetControl(
                WidgetLook.icons.forward(state.skipForwardSecs),
                context.getString(R.string.skip_forward_seconds, state.skipForwardSecs),
                WidgetActions.command(WidgetActions.Command.SKIP_FORWARD).takeIf { showing.loaded },
                tag = "forward",
            )
        }
    }
}

/** Tapping the words: the player when an episode is loaded, else the one up next, which plays. */
private fun open(context: Context, state: WidgetState, showing: Showing): Action = when {
    showing.loaded -> WidgetActions.openPlayer(context)
    state.resume != null -> WidgetActions.open(context, state.resume)
    else -> WidgetActions.openApp(context)
}

private fun playOrPause(context: Context, showing: Showing): Action =
    if (showing.isPlaying) WidgetActions.command(WidgetActions.Command.PAUSE) else WidgetActions.play(context)

private fun playLabel(context: Context, showing: Showing): String =
    context.getString(if (showing.isPlaying) SR.string.pause else SR.string.play)

/** "2 h 10 min left". */
fun timeLeft(context: Context, remainingMs: Long): String {
    val minutes = ((remainingMs + 59_999) / 60_000).toInt()
    return context.getString(R.string.time_left, length(context, minutes))
}

/** "1 h 5 min", "35 min". */
fun length(context: Context, minutes: Int): String =
    if (minutes >= 60) context.getString(R.string.length_hours_minutes, minutes / 60, minutes % 60) else context.getString(R.string.length_minutes, minutes)

/** Nothing new yet: the app's microphone and a word; a tap opens the app. */
@Composable
fun NothingNew(horizontal: Boolean) {
    val context = LocalContext.current
    val icon = @Composable {
        Image(ImageProvider(R.drawable.ic_widget_mic), contentDescription = null, colorFilter = ColorFilter.tint(WidgetLook.accent), modifier = GlanceModifier.size(28.dp))
    }
    val text = @Composable { modifier: GlanceModifier ->
        Text(context.getString(R.string.widget_nothing_new), modifier = modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, textAlign = if (horizontal) TextAlign.Start else TextAlign.Center))
    }
    val modifier = GlanceModifier.fillMaxSize().clickable(WidgetActions.openApp(context))
    if (horizontal) {
        Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(GlanceModifier.width(12.dp))
            text(GlanceModifier.defaultWeight())
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(GlanceModifier.height(8.dp))
            text(GlanceModifier)
        }
    }
}
