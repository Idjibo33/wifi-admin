package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Seam S1 : moteur réel contre un serveur loopback local.
 * Aucun test ne contacte Internet ; seul 127.0.0.1 est servi.
 */
class LibreSpeedEngineTest {

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
            client = LibreSpeedClient(httpClient(), endpoint),
            policy = policy,
            clock = TestAppClock(),
        )
    }

    /**
     * Routes by path (not by FIFO order): ping/upload on empty.php,
     * download on garbage.php. The upload body is fully consumed so
     * the client never blocks on a full socket buffer.
     */
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

    @Test fun `provider label shows host only`() {
        server.start()
        val e = engine()
        assertTrue(e.providerLabel.contains("LibreSpeed"))
        assertTrue(e.providerLabel.contains(server.url("/").host))
    }

    @Test fun `download counts real bytes from server`() = runBlocking {
        server.start()
        route(1_000_000)
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 5_000_000,
            maxUploadBytes = 2_000_000,
            latencyProbes = 3,
        )
        val result = engine(policy).run { }
        assertEquals(1_000_000L, result.downloadBytes)
        assertEquals(2_000_000L, result.uploadBytes)
        // The upload streams exactly the cap, so the result is a
        // floor ("≥"), never a precise bitrate: capped is honest.
        assertTrue(result.capped)
        assertNotNull(result.downloadMbps)
        assertTrue(result.downloadMbps!! > 0)
        assertNotNull(result.latencyMillis)
        assertTrue(result.providerLabel.contains(server.url("/").host))
    }

    @Test fun `cap is enforced exactly and marked capped`() = runBlocking {
        server.start()
        route(10_000_000)
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 2_000_000,
            maxUploadBytes = 1_000_000,
            latencyProbes = 2,
        )
        val result = engine(policy).run { }
        assertEquals(2_000_000L, result.downloadBytes)
        assertTrue(result.capped)
    }

    @Test fun `http error maps to ServerUnavailable`() = runBlocking {
        server.start()
        route(0, status = 503)
        try {
            engine().run { }
            throw AssertionError("expected SpeedTestFailureException")
        } catch (e: SpeedTestFailureException) {
            assertEquals(SpeedTestFailure.ServerUnavailable, e.failure)
        }
    }

    @Test fun `cancel closes the in-flight call`() = runBlocking {
        server.start()
        route(10_000_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val slow = MockResponse().setResponseCode(200)
                    .setBody(bigBody(10_000_000))
                    .setBodyDelay(5, TimeUnit.SECONDS)
                return slow
            }
        }
        val e = engine(SpeedTestPolicy(latencyProbes = 1))
        val job = async { e.run { } }
        Thread.sleep(300)
        e.cancel()
        try {
            job.await()
            throw AssertionError("expected cancellation")
        } catch (ce: CancellationException) {
            assertTrue(ce is CancellationException)
        }
    }
}
