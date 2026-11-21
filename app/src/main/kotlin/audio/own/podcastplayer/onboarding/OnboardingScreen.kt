package audio.own.podcastplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.brand.HeroBackground
import audio.own.core.designsystem.brand.OwnAudioMarkTribe
import audio.own.core.designsystem.brand.RotatingWordmark
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.podcastplayer.R as AppR
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.ui.ErrorLine

const val SOURCE_URL = "https://github.com/own-audio/podcast-android"

/**
 * The first screen, over the own.audio hero, while there is no show and no server: an
 * own.audio account at the top, as in Music and Books, and below it the ways in that need no
 * account at all.
 */
@Composable
fun OnboardingContent(
    tryingDemo: Boolean,
    error: UiText?,
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onTryDemo: () -> Unit,
    onFindShow: () -> Unit,
    onAddFeed: () -> Unit,
    onAddServer: () -> Unit,
) {
    val palette = LocalPalette.current
    val links = LocalUriHandler.current
    Box(Modifier.fillMaxSize()) {
        HeroBackground()
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OwnAudioMarkTribe(size = 120.dp)
                RotatingWordmark()
                Text(stringResource(AppR.string.onboarding_tagline), style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.Center)

                Spacer(Modifier.height(Tokens.Spacing.sm))
                Card {
                    Column(
                        Modifier.padding(Tokens.Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                            Text(stringResource(AppR.string.use_own_audio), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Text(stringResource(AppR.string.use_own_audio_text), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                        }
                        Button(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.create_own_audio_account), fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.sign_in)) }
                        // Someone not ready for an account can still see what the app does.
                        TextButton(onClick = onTryDemo, enabled = !tryingDemo) {
                            if (tryingDemo) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.try_the_demo), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(stringResource(AppR.string.onboarding_demo_footnote), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                    }
                }

                error?.let { ErrorLine(it.text()) }

                Text(
                    stringResource(AppR.string.onboarding_without_account),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.md),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    WayIn(Icons.Filled.Search, stringResource(AppR.string.find_a_show), onFindShow)
                    WayIn(Icons.Filled.Link, stringResource(AppR.string.add_feed_by_address), onAddFeed)
                    WayIn(Icons.Filled.Dns, stringResource(R.string.add_a_server), onAddServer)
                }
                TextButton(onClick = { links.openUri(SOURCE_URL) }) {
                    Text(stringResource(R.string.free_and_open_source), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun WayIn(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LocalPalette.current.backgroundAlt.copy(alpha = 0.92f)),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Text(label)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppBrand.radii.miniPlayer),
        color = LocalPalette.current.backgroundAlt.copy(alpha = 0.92f),
        content = content,
    )
}

/** A text field without a box of its own, for a field on a card. */
@Composable
fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    ime: ImeAction = ImeAction.Next,
    onGo: (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = ime,
            autoCorrectEnabled = keyboard == KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
