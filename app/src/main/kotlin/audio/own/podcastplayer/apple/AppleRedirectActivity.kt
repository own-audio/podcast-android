package audio.own.podcastplayer.apple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import audio.own.kit.ownaudio.AppleWeb
import audio.own.podcastplayer.MainActivity

/**
 * Where the browser comes back to after Sign in with Apple: `<applicationId>:/apple?code=…&state=…`.
 * It reads only `code`, `error` and `state` of exactly that address, hands them to [AppleSignIn]
 * (which accepts them only with the `state` it made), and brings the app back over the browser.
 * Anything else that opens it is dropped.
 */
class AppleRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val answer = parse(intent?.data, packageName)
        if (answer != null && savedInstanceState == null) {
            (application as? AppleRedirects)?.appleRedirected(answer.code, answer.error, answer.state)
            // Closes the browser's tab above the app and shows the screen the sign-in started on.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    data class Answer(val code: String?, val error: String?, val state: String?)

    companion object {
        /** The browser's answer, when [uri] is this app's redirect address and nothing else. */
        fun parse(uri: Uri?, applicationId: String): Answer? {
            uri ?: return null
            if (uri.scheme != applicationId || !uri.isHierarchical || !uri.authority.isNullOrEmpty() || uri.path != AppleWeb.REDIRECT_PATH) return null
            if (uri.fragment != null) return null
            return Answer(uri.getQueryParameter("code"), uri.getQueryParameter("error"), uri.getQueryParameter("state"))
        }
    }
}

/** The application, taking the browser's answer to Sign in with Apple. */
interface AppleRedirects {
    fun appleRedirected(code: String?, error: String?, state: String?)
}
