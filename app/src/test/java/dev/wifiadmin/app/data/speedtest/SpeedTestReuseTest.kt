package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Seam S1 — LibreSpeedEngine réutilisé sur le même client contre MockWebServer loopback.
 *
 * Le repository appelle engine.cancel() dans son finally même après un run réussi
 * (SpeedTestEngine.kt:313) ; le client doit donc supporter réutilisation, cancel
 * entre runs et cancel mid-run. Aucun test ne contacte Internet (127.0.0.1 uniquement).
 */
class SpeedTestReuseTest {

    private val server = MockWebServer()

    @After fun tearDown() { server.shutdown() }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun engine(policy: SpeedTestPolicy = SpeedTestPolicy()): LibreSpeedEngine {
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!
        return LibreSpeedEngine(
            endpoint = endpoint,
            baseHttpClient = httpClient(),
            policy = policy,
            clock = TestAppClock(),
        )
    }

    private fun route(downloadBytes: Long, status: Int = 200) {
        val body = bigBody(downloadBytes)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val response = if (path.contains("garbage.php")) {
                    MockResponse().setResponseCode(status)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(body.clone())
                } else if (request.method == "POST") {
                    request.body.readAll(object : okio.Sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            source.skip(byteCount)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                        override fun timeout() = okio.Timeout.NONE
                    })
                    MockResponse().setResponseCode(status).setBody("onspeedtest")
                } else {
                    MockResponse().setResponseCode(status).setBody("onspeedtest")
                }
                return response
            }
        }
    }

    private fun bigBody(bytes: Long): Buffer {
        val buffer = Buffer()
        val chunk = ByteArray(65_536)
        var remaining = bytes
        while (remaining > 0) {
            val n = minOf(chunk.size.toLong(), remaining)
            buffer.write(chunk, 0, n.toInt())
            remaining -= n
        }
        return buffer
    }

    private fun smallPolicy() = SpeedTestPolicy(
        maxDownloadBytes = 5_000_000L,
        maxUploadBytes = 500_000L,
        latencyProbes = 2,
    )

    @Test fun `deux runs successifs sur le meme moteur atteignent Done deux fois`() = runBlocking {
        server.start()
        route(100_000)
        val e = engine(smallPolicy())
        val first = e.run { }
        assertEquals(100_000L, first.downloadBytes)
        assertEquals(500_000L, first.uploadBytes)
        assertTrue(first.capped)
        val second = e.run { }
        assertEquals(100_000L, second.downloadBytes)
        assertEquals(500_000L, second.uploadBytes)
        assertTrue(second.capped)
        assertTrue(second.downloadMbps!! > 0)
    }

    @Test fun `run apres cancel entre deux runs atteint Done`() = runBlocking {
        server.start()
        route(100_000)
        val e = engine(smallPolicy())
        val first = e.run { }
        assertEquals(100_000L, first.downloadBytes)
        e.cancel()
        val second = e.run { }
        assertEquals(100_000L, second.downloadBytes)
        assertEquals(500_000L, second.uploadBytes)
        assertTrue(second.capped)
    }

    @Test fun `run apres cancel pendant un run atteint Done`() = runBlocking {
        server.start()
        val downloadSeen = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    downloadSeen.countDown()
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(bigBody(10_000_000))
                        .setBodyDelay(5, TimeUnit.SECONDS)
                } else if (request.method == "POST") {
                    request.body.readAll(object : okio.Sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            source.skip(byteCount)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                        override fun timeout() = okio.Timeout.NONE
                    })
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val e = engine(SpeedTestPolicy(latencyProbes = 1, maxDownloadBytes = 5_000_000L, maxUploadBytes = 500_000L))
        val first = async(Dispatchers.IO) { e.run { } }
        assertTrue("download doit etre en vol avant cancel", downloadSeen.await(5, TimeUnit.SECONDS))
        e.cancel()
        try {
            first.await()
            throw AssertionError("cancel mid-run doit lever CancellationException")
        } catch (ce: CancellationException) {
            assertTrue(ce is CancellationException)
        }
        route(100_000)
        val second = e.run { }
        assertEquals(100_000L, second.downloadBytes)
        assertEquals(500_000L, second.uploadBytes)
        assertTrue(second.capped)
    }
}
