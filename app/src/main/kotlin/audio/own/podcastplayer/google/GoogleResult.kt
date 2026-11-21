package audio.own.podcastplayer.google

/** What asking Google for an id token came to. */
sealed interface GoogleResult {
    data class Token(val idToken: String) : GoogleResult

    data object Cancelled : GoogleResult

    /** Usually a configuration problem (this app's client isn't known to the server's project). */
    data class Failed(val reason: String) : GoogleResult
}
