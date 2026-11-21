package audio.own.podcastplayer.google

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Sign in with Google through Credential Manager (the `play` build). The id token is made for
 * the own.audio server's web client, which the server names in its discovery.
 */
object GoogleSignIn {
    const val isAvailable = true

    suspend fun idToken(context: Context, serverClientId: String): GoogleResult {
        val manager = CredentialManager.create(context)
        val accounts = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()
        return try {
            read(manager.getCredential(context, GetCredentialRequest(listOf(accounts))).credential)
        } catch (e: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (e: NoCredentialException) {
            // No account on the device yet: Google's own sheet lets one be added.
            try {
                val button = GetSignInWithGoogleOption.Builder(serverClientId).build()
                read(manager.getCredential(context, GetCredentialRequest(listOf(button))).credential)
            } catch (e: GetCredentialCancellationException) {
                GoogleResult.Cancelled
            } catch (e: GetCredentialException) {
                GoogleResult.Failed("${e.type}: ${e.message}")
            }
        } catch (e: GetCredentialException) {
            GoogleResult.Failed("${e.type}: ${e.message}")
        }
    }

    private fun read(credential: androidx.credentials.Credential): GoogleResult =
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            GoogleResult.Failed("unexpected credential ${credential.type}")
        }
}
