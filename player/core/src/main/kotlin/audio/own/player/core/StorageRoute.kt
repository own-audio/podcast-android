package audio.own.player.core

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The way to an own.audio file when the network blocks cloud storage.
 *
 * An own.audio server hands out a signed stream link (`…/api/v2/stream?…&s=…`) that answers with
 * a redirect to the file in cloud storage, on the storage's own host. Some networks (a company
 * firewall, mostly) block that host while the server's own host works: the name does not
 * resolve, the connection is refused or reset, TLS fails, or a proxy in between answers 403. The
 * same link with `route=proxy` added redirects to the server's media proxy on its own domain
 * instead. The parameter is not part of the signature, and a server without a proxy ignores it,
 * so adding it is always safe, but only on that link: no other server's address is ever changed.
 *
 * Links are strings (what OkHttp's `HttpUrl` and Media3's `Uri` both print) and are edited as
 * text, so the signed parameters stay byte for byte as the server wrote them.
 */
object StorageRoute {
    internal const val PARAMETER = "route"
    internal const val PROXY_VALUE = "proxy"

    /** What a filtering proxy answers in place of the file. */
    internal val blockingStatuses = setOf(403, 407, 451)

    /** An own.audio stream link: the only kind of address that takes `route=proxy`. */
    fun takesProxyRoute(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme?.lowercase().let { it != "http" && it != "https" }) return false
        if (uri.rawPath?.endsWith("/api/v2/stream") != true) return false
        return queryItems(url).any { it.first == "s" }
    }

    fun isProxied(url: String): Boolean =
        queryItems(url).any { it.first == PARAMETER && it.second == PROXY_VALUE }

    /** The link through the media proxy. */
    fun proxied(url: String): String {
        if (!takesProxyRoute(url) || isProxied(url)) return url
        return withRoute(url, PROXY_VALUE)
    }

    /** The link as the server gave it: straight to storage. */
    fun direct(url: String): String = withRoute(url, null)

    /**
     * The other way to fetch [url], for when the way tried was blocked; null for an address that
     * is not an own.audio stream link.
     */
    fun alternate(url: String): String? {
        if (!takesProxyRoute(url)) return null
        return if (isProxied(url)) direct(url) else proxied(url)
    }

    /**
     * Whether [error] is the kind of failure a blocked host gives. Java's exceptions carry no URL,
     * so the caller says where the request was when it failed: [failingUrl] (after redirects), or
     * a [Failure] wrapping the error. Without either nothing can be told and the answer is no.
     *
     * A failure at the link's own host is the server being unreachable, not storage being
     * blocked. With no network at all Android also reports [UnknownHostException]; that costs one
     * extra request that fails too, and nothing is remembered (only a success is).
     */
    fun isBlocked(error: Throwable, link: String, failingUrl: String? = null): Boolean {
        val failure = error as? Failure
        if (!isBlockingError(failure?.cause ?: error)) return false
        val failing = failingUrl ?: failure?.failingUrl ?: return false
        return !sameHost(failing, link)
    }

    /**
     * A network error that knows where its request was when it failed. The data source wraps its
     * I/O errors in this, since Java's own network exceptions don't say.
     */
    class Failure(val failingUrl: String, cause: Throwable) : java.io.IOException(cause.message, cause)

    /**
     * An answer to a request for [link]: blocked when a host other than the link's own refused it
     * ([responseUrl] is where the answer came from, after redirects). A 403 from the server
     * itself is an expired link (`invalid_link`), renewed elsewhere.
     */
    fun isBlocked(status: Int, responseUrl: String, link: String): Boolean =
        status in blockingStatuses && !sameHost(responseUrl, link)

    /**
     * DNS, refused or reset connections, timeouts and TLS failures. [SocketException] covers
     * `ConnectException` and `NoRouteToHostException`; "Socket closed" is what a cancelled call
     * gives, and a cancel is not a block.
     */
    internal fun isBlockingError(error: Throwable): Boolean = when (error) {
        is UnknownHostException, is SocketTimeoutException, is SSLException -> true
        is SocketException -> error.message?.equals("Socket closed", ignoreCase = true) != true
        else -> false
    }

    internal fun sameHost(a: String, b: String): Boolean {
        val ua = parse(a) ?: return false
        val ub = parse(b) ?: return false
        return ua.host?.lowercase() == ub.host?.lowercase() && port(ua) == port(ub)
    }

    private fun port(uri: URI): Int? = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> null
    }

    internal fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }

    /** Name and raw (still percent-encoded) value of each query item. */
    private fun queryItems(url: String): List<Pair<String, String?>> {
        val query = split(url).second ?: return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { item ->
            val eq = item.indexOf('=')
            if (eq < 0) item to null else item.substring(0, eq) to item.substring(eq + 1)
        }
    }

    /** `base`, `query` (without `?`), `fragment` (without `#`). */
    private fun split(url: String): Triple<String, String?, String?> {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val fragment = if (hash < 0) null else url.substring(hash + 1)
        val question = beforeFragment.indexOf('?')
        return if (question < 0) {
            Triple(beforeFragment, null, fragment)
        } else {
            Triple(beforeFragment.substring(0, question), beforeFragment.substring(question + 1), fragment)
        }
    }

    private fun withRoute(url: String, value: String?): String {
        val (base, query, fragment) = split(url)
        val items = (query?.split('&') ?: emptyList())
            .filter { it.isNotEmpty() && it.substringBefore('=') != PARAMETER }
            .toMutableList()
        if (value != null) items += "$PARAMETER=$value"
        val rebuilt = StringBuilder(base)
        if (items.isNotEmpty()) rebuilt.append('?').append(items.joinToString("&"))
        if (fragment != null) rebuilt.append('#').append(fragment)
        return rebuilt.toString()
    }
}

/**
 * Remembers, per server and network, that storage is blocked there and the media proxy works,
 * so later streams and downloads go straight to the proxy. Kept a week; a network where the
 * proxy fails and storage works again forgets it at once.
 *
 * [network] is the app's signature of the current network (from `ConnectivityManager`); the app
 * keeps it current. Safe to use from any thread.
 */
class ProxyMemory(
    private val store: Store = InMemoryStore(),
    private val lifetimeMs: Long = DEFAULT_LIFETIME_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where the entries live between launches: "host:port|network" → when the proxy worked (ms). */
    interface Store {
        fun load(): Map<String, Long>
        fun save(entries: Map<String, Long>)
    }

    class InMemoryStore : Store {
        @Volatile private var entries: Map<String, Long> = emptyMap()
        override fun load(): Map<String, Long> = entries
        override fun save(entries: Map<String, Long>) {
            this.entries = entries.toMap()
        }
    }

    /** Where an answer came from and what it said, for [fetch] to tell a block. */
    data class Answer(val status: Int, val url: String)

    @Volatile var network: String = ""

    private val lock = Any()
    private val entries: MutableMap<String, Long> =
        now().let { current -> store.load().filterValues { current - it < lifetimeMs }.toMutableMap() }

    /** Whether streams of [link]'s server go through the proxy on this network. */
    fun usesProxy(link: String): Boolean {
        val key = entryKey(link) ?: return false
        synchronized(lock) {
            val since = entries[key] ?: return false
            if (now() - since < lifetimeMs) return true
            entries.remove(key)
            store.save(entries)
            return false
        }
    }

    /** [link] as it is to be fetched here: through the proxy where storage was found blocked. */
    fun route(link: String): String {
        if (!StorageRoute.takesProxyRoute(link)) return link
        return if (usesProxy(link)) StorageRoute.proxied(link) else StorageRoute.direct(link)
    }

    /**
     * [url] worked after the other way had been blocked: through the proxy is remembered,
     * straight to storage forgets the proxy.
     */
    fun worked(url: String) {
        val key = entryKey(url) ?: return
        synchronized(lock) {
            if (StorageRoute.isProxied(url)) {
                entries[key] = now()
                if (entries.size > CAPACITY) {
                    entries.minByOrNull { it.value }?.let { entries.remove(it.key) }
                }
            } else {
                if (entries.remove(key) == null) return
            }
            store.save(entries)
        }
    }

    /**
     * One request for [link], the way remembered for this network; when that one is blocked, once
     * more the other way, and the way that worked is remembered. A second failure is thrown or
     * returned as it is. [answer] reads the status and final URL of a result; [release] closes a
     * result that is dropped for the retry (an OkHttp `Response` must be closed).
     */
    suspend fun <T> fetch(
        link: String,
        answer: (T) -> Answer,
        release: (T) -> Unit = {},
        attempt: suspend (String) -> T,
    ): T {
        val first = route(link)
        val result = try {
            attempt(first)
        } catch (error: Throwable) {
            val other = StorageRoute.alternate(first)
            if (other == null || !StorageRoute.isBlocked(error, first)) throw error
            return retry(other, answer, attempt)
        }
        val a = answer(result)
        val other = StorageRoute.alternate(first)
        if (other == null || !StorageRoute.isBlocked(a.status, a.url, first)) return result
        release(result)
        return retry(other, answer, attempt)
    }

    private suspend fun <T> retry(url: String, answer: (T) -> Answer, attempt: suspend (String) -> T): T {
        val result = attempt(url)
        if (answer(result).status in 200..299) worked(url)
        return result
    }

    private fun entryKey(link: String): String? {
        val uri = StorageRoute.parse(link) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) "" else uri.port.toString()
        return "$host:$port|$network"
    }

    companion object {
        const val DEFAULT_LIFETIME_MS = 7L * 24 * 3600 * 1000
        /** A few servers on a few networks; more is an old phone's history. */
        internal const val CAPACITY = 32
    }
}
