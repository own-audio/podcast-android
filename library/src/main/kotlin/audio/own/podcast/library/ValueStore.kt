package audio.own.podcast.library

/**
 * Where a small piece of state is kept between launches (Up Next, pending positions, a server's
 * listening state, a gPodder account's sync state). The library writes through it before a call
 * returns, so a killed app loses nothing. Saving null removes it.
 */
interface ValueStore<T : Any> {
    suspend fun load(): T?

    suspend fun save(value: T?)
}
