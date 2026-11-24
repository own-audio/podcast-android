package audio.own.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.core.designsystem.R as DR
import audio.own.core.strings.R

/** "Continue with Google", drawn as the iOS apps draw it, in Google's light and dark themes. */
@Composable
fun GoogleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = darkTheme()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (dark) Color(0xFF131314) else Color.White,
            contentColor = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F),
        ),
        border = BorderStroke(1.dp, if (dark) Color(0xFF8E918F) else Color(0xFF747775)),
    ) {
        Image(painterResource(DR.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.continue_with_google), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * "Continue with Apple", as Apple's guidelines draw it: black on a light screen, white on a dark
 * one, the logo and the title in the same size as the Google button beside it.
 */
@Composable
fun AppleSignInButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = darkTheme()
    val background = if (dark) Color.White else Color.Black
    val foreground = if (dark) Color.Black else Color.White
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
            disabledContainerColor = background.copy(alpha = 0.5f),
            disabledContentColor = foreground.copy(alpha = 0.7f),
        ),
    ) {
        Icon(painterResource(DR.drawable.ic_apple_logo), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.continue_with_apple), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** The theme the screen is drawn in, which a preview or test may set apart from the system's. */
@Composable
private fun darkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
