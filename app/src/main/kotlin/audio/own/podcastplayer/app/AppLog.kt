package audio.own.podcastplayer.app

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * What the app noted since it started, for the Logs page and a bug report. Kept in memory (the
 * last [capacity] lines) and written to `logs/current.log`; the previous run's file is kept as
 * `previous.log`. Nothing leaves the device unless the listener shares it.
 */
class AppLog(private val dir: File?, private val capacity: Int = 2_000, private val echo: Boolean = true) {
    enum class Level { INFO, ERROR }

    data class Line(val at: Instant, val level: Level, val category: String, val message: String) {
        val text: String get() = "${DateTimeFormatter.ISO_INSTANT.format(at)} ${level.name.lowercase()} [$category] $message"
    }

    private val buffer = ArrayDeque<Line>()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    // One writer thread keeps the lines in order and the disk off the caller's thread.
    private val writer = dir?.let { Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } } }
    private val file = dir?.let { File(it, "current.log") }

    init {
        writer?.execute {
            runCatching {
                dir!!.mkdirs()
                val previous = File(dir, "previous.log")
                if (file!!.exists()) {
                    previous.delete()
                    file.renameTo(previous)
                }
            }
        }
    }

    fun info(category: String, message: String) = add(Level.INFO, category, message)

    fun error(category: String, message: String) = add(Level.ERROR, category, message)

    private fun add(level: Level, category: String, message: String) {
        val line = Line(Instant.now(), level, category, message)
        val snapshot = synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > capacity) buffer.removeFirst()
            buffer.toList()
        }
        _lines.value = snapshot
        if (echo) runCatching { if (level == Level.ERROR) Log.w(TAG, "[$category] $message") else Log.i(TAG, "[$category] $message") }
        writer?.execute { runCatching { file!!.appendText(line.text + "\n") } }
    }

    /** The log as a text file, headed with the app and system, for the share sheet. */
    fun export(target: File, header: String): File {
        val text = buildString {
            append(header).append("\n\n")
            lines.value.forEach { append(it.text).append('\n') }
        }
        target.parentFile?.mkdirs()
        target.writeText(text)
        return target
    }

    companion object {
        private const val TAG = "own.podcast"

        /** Tokens and signatures live in query strings: an address is logged without its query. */
        fun redact(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString()

        fun clock(at: Instant): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(at)
    }
}

/** Every request in one line: method, address without its query, status and time. */
class LoggingInterceptor(private val log: AppLog) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()
        val where = "${request.method} ${AppLog.redact(request.url)}"
        return try {
            val response = chain.proceed(request)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (response.isSuccessful) log.info("network", "$where → ${response.code} ($ms ms)") else log.error("network", "$where → ${response.code} ($ms ms)")
            response
        } catch (e: java.io.IOException) {
            log.error("network", "$where failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            throw e
        }
    }
}
