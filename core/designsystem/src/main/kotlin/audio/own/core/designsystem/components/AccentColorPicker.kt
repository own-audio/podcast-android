package audio.own.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R

/** Settings → Accent Color: a row of swatches, the chosen one ringed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentColorPicker(selected: AccentChoice, onSelect: (AccentChoice) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ring = LocalPalette.current.foreground
    SettingsSection(header = stringResource(R.string.accent_color), footer = stringResource(R.string.accent_footer), modifier = modifier) {
        FlowRow(
            Modifier.padding(Tokens.Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        ) {
            AppBrand.accentSwatches.forEach { choice ->
                val title = stringResource(choice.title)
                Box(
                    Modifier
                        .size(Tokens.minTarget)
                        .clip(CircleShape)
                        .selectable(selected = choice == selected, role = Role.RadioButton) { onSelect(choice) }
                        .semantics { contentDescription = title },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(2.dp, if (choice == selected) ring else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(choice.color(dark)),
                    )
                }
            }
        }
    }
}
