package audio.own.podcastplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import audio.own.podcastplayer.MainActivity
import audio.own.podcastplayer.R
import java.io.File

/**
 * The home-screen widget "Up Next": the episode under way and the newest ones, a tap away; one on
 * the small size, three on the medium. Everything comes from the snapshot the app writes
 * ([WidgetStore]); a tap opens the app and plays that episode. Ported from the iOS app's widget.
 */
class UpNextWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetStore(folder(context))
        val entries = store.load()?.entries.orEmpty()
        val covers = entries.mapNotNull { entry -> entry.cover?.let { name -> decode(store.cover(name))?.let { name to it } } }.toMap()
        provideContent { UpNextContent(entries, covers) }
    }

    private fun decode(file: File): Bitmap? = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)

        fun folder(context: Context) = File(context.filesDir, "widget")

        val accent = ColorProvider(day = Color(0xFF0A6E9C), night = Color(0xFF29ABE2))
        val accentSoft = ColorProvider(day = Color(0x260A6E9C), night = Color(0x2629ABE2))
        val background = ColorProvider(day = Color(0xFFF7F7F8), night = Color(0xFF0C0C0E))
        val foreground = ColorProvider(day = Color(0xFF1D1D1F), night = Color(0xFFF5F5F7))
        val muted = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFF98989D))
    }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}

@Composable
private fun UpNextContent(entries: List<WidgetSnapshot.Entry>, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    val medium = LocalSize.current.width >= UpNextWidget.MEDIUM.width
    Box(GlanceModifier.fillMaxSize().background(UpNextWidget.background).padding(12.dp)) {
        when {
            entries.isEmpty() -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_widget_mic), contentDescription = null, colorFilter = ColorFilter.tint(UpNextWidget.accent), modifier = GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.height(6.dp))
                Text(context.getString(R.string.widget_nothing_new), style = TextStyle(color = UpNextWidget.muted, fontSize = 12.sp, textAlign = TextAlign.Center))
            }
            !medium -> Small(entries.first(), covers)
            else -> Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                entries.take(WidgetSnapshot.MAX_ENTRIES).forEachIndexed { index, entry ->
                    if (index > 0) Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open(context, entry))), verticalAlignment = Alignment.CenterVertically) {
                        Cover(covers[entry.cover], 40)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(entry.title, maxLines = 1, style = TextStyle(color = UpNextWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                            if (entry.show.isNotEmpty()) Text(entry.show, maxLines = 1, style = TextStyle(color = UpNextWidget.muted, fontSize = 11.sp))
                            Bar(entry)
                        }
                        Spacer(GlanceModifier.width(8.dp))
                        Text(remaining(context, entry), style = TextStyle(color = UpNextWidget.muted, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Small(entry: WidgetSnapshot.Entry, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(open(context, entry)))) {
        Cover(covers[entry.cover], 56)
        Spacer(GlanceModifier.defaultWeight())
        Text(entry.title, maxLines = 2, style = TextStyle(color = UpNextWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Bar(entry)
        Text(remaining(context, entry), style = TextStyle(color = UpNextWidget.muted, fontSize = 11.sp))
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Int) {
    if (bitmap != null) {
        Image(ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size.dp).cornerRadius(8.dp))
    } else {
        Box(GlanceModifier.size(size.dp).cornerRadius(8.dp).background(UpNextWidget.accentSoft), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_widget_mic), contentDescription = null, colorFilter = ColorFilter.tint(UpNextWidget.accent), modifier = GlanceModifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun Bar(entry: WidgetSnapshot.Entry) {
    LinearProgressIndicator(
        progress = entry.fraction.toFloat().coerceAtLeast(0.03f),
        color = UpNextWidget.accent,
        backgroundColor = UpNextWidget.accentSoft,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp).padding(vertical = 0.dp),
    )
}

private fun remaining(context: Context, entry: WidgetSnapshot.Entry): String {
    val minutes = (entry.remainingMs / 60_000).toInt()
    val text = if (minutes >= 60) context.getString(R.string.length_hours_minutes, minutes / 60, minutes % 60) else context.getString(R.string.length_minutes, minutes)
    return context.getString(R.string.time_left, text)
}

private fun open(context: Context, entry: WidgetSnapshot.Entry): Intent =
    Intent(Intent.ACTION_VIEW, WidgetSnapshot.playLink(entry.id)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
