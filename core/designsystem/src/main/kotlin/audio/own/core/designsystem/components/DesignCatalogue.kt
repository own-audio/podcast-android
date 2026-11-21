package audio.own.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.brand.HeroBackground
import audio.own.core.designsystem.brand.OwnAudioMark
import audio.own.core.designsystem.brand.OwnAudioMarkTribe
import audio.own.core.designsystem.brand.RotatingWordmark
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular

// A debug-only screen with every token and shared component, the subject of the screenshot
// tests. The words on it are sample content, not app text, so they aren't translated.

@Composable
fun DesignCatalogue(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.background(p.background).verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
    ) {
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            HeroBackground()
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                OwnAudioMarkTribe(size = 96.dp)
                RotatingWordmark()
            }
        }
        Row(Modifier.padding(horizontal = Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            OwnAudioMark(size = 64.dp)
            OwnAudioMark(size = 20.dp)
            OwnAudioMarkTribe(size = 20.dp)
            NowPlayingIndicator(playing = false)
            FamilyBadge()
            DownloadBadge()
        }
        Row(Modifier.padding(horizontal = Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
            listOf(p.background, p.backgroundAlt, p.card, p.foreground, p.muted, p.border, p.success, p.error, p.accent).forEach {
                Box(Modifier.size(28.dp).background(it, RoundedCornerShape(6.dp)).background(Color.Transparent))
            }
        }
        Row(Modifier.padding(horizontal = Tokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(null, 56.dp, Icons.Filled.MusicNote)
            Column(Modifier.weight(1f)) {
                Text("Sample Title", style = MaterialTheme.typography.titleMedium)
                Text("Sample Artist · 3:07", style = MaterialTheme.typography.bodySmall.tabular(), color = p.muted)
            }
            DownloadButton(DownloadState.None, {}, {})
            DownloadButton(DownloadState.Downloading(0.4f), {}, {})
            DownloadButton(DownloadState.Downloaded, {}, {})
            SetDownloadButton(3, 10, downloading = true, onDownload = {}, onRemove = {})
        }
        Column(Modifier.padding(horizontal = Tokens.Spacing.lg)) { GoogleSignInButton(onClick = {}) }
        SettingsSection(header = "Section", footer = "A footer explains the rows above.") {
            ValueRow("Value row", "Value", onClick = {})
            SwitchRow("Switch row", checked = true, onCheckedChange = {})
        }
        AccentColorPicker(selected = AccentChoice.APP, onSelect = {})
        Box(Modifier.padding(horizontal = Tokens.Spacing.lg)) { QrCode("https://own.audio", 96.dp) }
        OfflineBanner("No network · downloaded music only")
        MiniPlayerBar(
            cover = null, placeholder = Icons.Filled.MusicNote, title = "Sample Title", subtitle = "Sample Artist",
            isPlaying = true, isLoading = false, progress = 0.4f, buffered = 0.7f,
            onToggle = {}, secondaryIcon = Icons.Filled.SkipNext, secondaryLabel = "Next", onSecondary = {}, onOpen = {},
        )
        Box(Modifier.height(240.dp)) {
            EmptyState(icon = Icons.Filled.MusicNote, title = "Nothing Here Yet", text = "An empty state explains what would be here.", actionLabel = "Try Again", onAction = {})
        }
    }
}
