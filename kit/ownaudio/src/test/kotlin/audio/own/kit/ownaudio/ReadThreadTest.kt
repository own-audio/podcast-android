package audio.own.kit.ownaudio

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadThreadTest {

    @Test
    fun `an answer is never read on the thread that asked`() = runBlocking {
        val readOn = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val watched = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readOn += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response.newBuilder().body(watched.asResponseBody(body.contentType(), body.contentLength())).build()
        }.build()
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        try {
            StubServer { json(answer("discovery")) }.use { stub ->
                withContext(caller) { stub.client(tokens = null, http = http).discover() }
            }
        } finally {
            caller.close()
        }
        assertTrue("the body was read", readOn.isNotEmpty())
        // Coroutine debug mode adds " @coroutine#n" to the name.
        readOn.forEach { assertFalse(it, it.startsWith("caller")) }
    }
}
