package audio.own.podcastplayer.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import audio.own.core.strings.R
import audio.own.kit.ownaudio.OwnAudioException

/**
 * Text a view model hands to the screen: a string resource with its arguments, so the words
 * follow the app's language, never a sentence built in Kotlin.
 */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())

    companion object {
        fun of(@StringRes id: Int, vararg args: Any) = UiText(id, args.toList())
    }
}

@Composable
fun UiText.text(): String = resolve(LocalContext.current)

/** A failure the listener should read, with its sentence. */
class UserFacingException(val text: UiText, cause: Throwable? = null) : Exception("user-facing: ${text.id}", cause) {
    constructor(@StringRes id: Int, cause: Throwable? = null) : this(UiText(id), cause)
}

/**
 * A short sentence for a failed request, chosen by the problem's code — never by its text,
 * which is the server's and in the server's language.
 */
fun Throwable.userMessage(): UiText = when (this) {
    is UserFacingException -> text
    is OwnAudioException.Offline -> UiText(R.string.error_offline)
    is OwnAudioException.Network -> UiText(R.string.error_network)
    is OwnAudioException.SignInRefused, is OwnAudioException.SignedOut -> UiText(R.string.error_sign_in_refused)
    is OwnAudioException.TooOld -> UiText(R.string.error_too_old)
    is OwnAudioException.Decoding -> UiText(R.string.error_unreadable)
    is OwnAudioException.Problem -> when {
        code == "forbidden" || status == 403 -> UiText(R.string.error_not_allowed)
        status == 404 -> UiText(R.string.error_not_found)
        status == 429 -> UiText(R.string.error_rate_limited)
        else -> UiText.of(R.string.error_http, status)
    }
    is OwnAudioException.Http -> when (status) {
        403 -> UiText(R.string.error_not_allowed)
        404 -> UiText(R.string.error_not_found)
        else -> UiText.of(R.string.error_http, status)
    }
    else -> UiText(R.string.error_generic)
}

/** Loading, failed with a sentence, or loaded: what a view model hands to `LoadStateView`. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: UiText) : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
}

@Composable
fun <T> Load<T>.forView(): audio.own.core.designsystem.components.LoadState<T> = when (this) {
    Load.Loading -> audio.own.core.designsystem.components.LoadState.Loading
    is Load.Failed -> audio.own.core.designsystem.components.LoadState.Failed(message.text())
    is Load.Loaded -> audio.own.core.designsystem.components.LoadState.Loaded(value)
}
