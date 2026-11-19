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
import okio.buffer
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

    private fun httpClient(paceUpload: Boolean = false): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (paceUpload) {
                // Test-only throttle: borne le débit d'écriture indépendamment
                // de la vitesse de la machine (le CI écrit ~385 Mio/s sinon).
                addInterceptor { chain ->
                    val request = chain.request()
                    if (request.method != "POST") return@addInterceptor chain.proceed(request)
                    val original = request.body!!
                    val paced = object : okhttp3.RequestBody() {
                        override fun contentType() = original.contentType()
                        override fun contentLength(): Long = -1L
                        override fun writeTo(sink: okio.BufferedSink) {
                            val paced = PacedSink(sink).buffer()
                            original.writeTo(paced)
                            paced.flush()
                        }
                    }
                    chain.proceed(request.newBuilder().post(paced).build())
                }
            }
        }
        .build()

    private fun engine(policy: SpeedTestPolicy = SpeedTestPolicy(), paceUpload: Boolean = false): LibreSpeedEngine {
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!
        return LibreSpeedEngine(
            endpoint = endpoint,
            baseHttpClient = httpClient(paceUpload),
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

    /** Test-only sink that sleeps 1 ms per flushed chunk: bounds upload rate. */
    private class PacedSink(private val delegate: okio.Sink) : okio.Sink {
        override fun write(source: Buffer, byteCount: Long) {
            Thread.sleep(1)
            delegate.write(source, byteCount)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
        override fun timeout(): okio.Timeout = delegate.timeout()
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
        assertEquals(5_000_000L, result.downloadBytes)
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
        val downloadSeen = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if ((request.path ?: "").contains("garbage.php")) {
                    downloadSeen.countDown()
                    MockResponse().setResponseCode(200)
                        .setBody(bigBody(10_000_000))
                        // Short enough for MockWebServer.shutdown() to
                        // drain its queue within its own 5 s budget, long
                        // enough that missing cancellation is observable
                        // via the elapsed-time assertion below.
                        .setBodyDelay(2, TimeUnit.SECONDS)
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val e = engine(SpeedTestPolicy(latencyProbes = 1))
        val job = async(kotlinx.coroutines.Dispatchers.IO) { e.run { } }
        assertTrue("download doit etre en vol avant cancel", downloadSeen.await(5, TimeUnit.SECONDS))
        val cancelAt = System.nanoTime()
        e.cancel()
        try {
            job.await()
            throw AssertionError("expected cancellation")
        } catch (ce: CancellationException) {
            // Proves the socket was closed: a cooperative-only cancel
            // would have waited for the 2 s body delay.
            val elapsedMillis = (System.nanoTime() - cancelAt) / 1_000_000L
            assertTrue(
                "cancel doit fermer l'appel sans attendre le corps: ${elapsedMillis}ms",
                elapsedMillis < 1_500L,
            )
        }
        job.cancel()
    }

    /**
     * Mission 16 — the reduced debug preset is enforced by the engine
     * itself, not only by the repository: exactly the cap leaves the
     * phone, and the result is honestly marked capped.
     */
    @Test fun `reduced debug budget stops at the exact caps`() = runBlocking {
        server.start()
        route(2_000_000)
        val reduced = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 1L shl 20,
            maxUploadBytes = 256L * 1024,
            maxDurationMillis = 10_000L,
        ).copy(latencyProbes = 1)
        val result = engine(reduced).run { }
        assertEquals(1L shl 20, result.downloadBytes)
        assertEquals(256L * 1024, result.uploadBytes)
        assertTrue(result.capped)
    }


    /**
     * Mission 17 — latence : une sonde bloquante qui dépasse le budget
     * de latence déclenche un Timeout, pas une mesure complète.
     */
    @Test fun `latency deadline exceeded throws timeout`() = runBlocking(Dispatchers.IO) {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(2_000)
                return MockResponse().setResponseCode(200).setBody("onspeedtest")
            }
        }
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 1_000_000,
            maxUploadBytes = 500_000,
            maxDurationMillis = 10_000,
            latencyProbes = 1,
        )
        try {
            engine(policy).run { }
            throw AssertionError("expected timeout")
        } catch (e: SpeedTestFailureException) {
            assertEquals(SpeedTestFailure.Timeout, e.failure)
        }
    }


    /**
     * Mission 17 — réponse HTTP 200 vide (download) → Incomplete.
     */
    @Test fun `empty http 200 response yields incomplete`() = runBlocking(Dispatchers.IO) {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody("")
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 1_000_000,
            maxUploadBytes = 100_000,
            maxDurationMillis = 10_000,
            latencyProbes = 1,
        )
        try {
            engine(policy).run { }
            throw AssertionError("expected Incomplete")
        } catch (e: SpeedTestFailureException) {
            assertEquals(SpeedTestFailure.Incomplete, e.failure)
        }
    }


    /**
     * Mission 17 — arrêt prématuré après téléchargement partiel.
     * Le serveur envoie 100 000 octets puis une réponse vide : la fenêtre
     * n'a PAS expiré et le plafond n'a PAS été atteint. L'arrêt doit être
     * classifié PREMATURE, jamais annoncé comme mesure stabilisée.
     */
    @Test fun `partial download then empty response is premature`() = runBlocking(Dispatchers.IO) {
        server.start()
        var reqCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    reqCount++
                    if (reqCount == 1) {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .setBody(bigBody(100_000))
                    } else {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .setBody("")
                    }
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 1_000_000,
            maxUploadBytes = 100_000,
            maxDurationMillis = 10_000,
            latencyProbes = 1,
        )
        val result = engine(policy).run { }
        assertEquals(100_000L, result.downloadBytes)
        assertFalse(result.cappedDownload)
        assertEquals(PhaseEndReason.PREMATURE, result.downloadEndReason)
        assertFalse(SpeedTestCalculator.isStabilised(result))
        assertNotNull(result.downloadMbps)
    }


    /**
     * Mission 17 — Upload plafonné, Download non plafonné.
     */
    @Test fun `upload capped download not capped`() = runBlocking(Dispatchers.IO) {
        server.start()
        var reqCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    reqCount++
                    if (reqCount == 1) {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .setBody(bigBody(100))
                    } else {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .setBody("")
                    }
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 1_000_000,
            maxUploadBytes = 100,
            maxDurationMillis = 10_000,
            latencyProbes = 1,
        )
        val result = engine(policy).run { }
        assertFalse(result.cappedDownload)
        assertTrue(result.cappedUpload)
        assertTrue(result.capped)
    }


    /**
     * Mission 17 — (1) le Download se termine par expiration de sa fenêtre,
     * sans atteindre le plafond. Le serveur débite lentement mais ne renvoie
     * jamais de réponse vide : seule la deadline peut arrêter la phase.
     */
    @Test fun `download ends by time window without cap`() = runBlocking(Dispatchers.IO) {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(bigBody(2_000_000))
                        .throttleBody(64L * 1_024, 100, TimeUnit.MILLISECONDS)
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
            }
        }
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 3_000_000,
            maxUploadBytes = 100_000,
            maxDurationMillis = 6_000,
            latencyProbes = 1,
        )
        val result = engine(policy).run { }
        assertEquals(PhaseEndReason.TIME_WINDOW, result.downloadEndReason)
        assertFalse(result.cappedDownload)
        assertTrue(result.downloadBytes > 256L * 1_024)
        assertTrue(result.downloadBytes < 3_000_000L)
        assertFalse(SpeedTestCalculator.isTooShortToTrust(result.downloadBytes, result.downloadDurationMillis))
        assertNotNull(result.downloadMbps)
    }

    /**
     * Mission 17 — (2) l'Upload se termine par expiration de sa fenêtre,
     * sans atteindre le plafond. Le plafond de test est volontairement très
     * au-dessus de ce qui peut être écrit pendant la fenêtre.
     */
    @Test fun `upload ends by time window without cap`() = runBlocking(Dispatchers.IO) {
        server.start()
        route(100_000)
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 100_000,
            maxUploadBytes = 500_000_000,
            maxDurationMillis = 3_000,
            latencyProbes = 1,
        )
        val result = engine(policy, paceUpload = true).run { }
        assertEquals(PhaseEndReason.TIME_WINDOW, result.uploadEndReason)
        assertFalse(result.cappedUpload)
        assertTrue(result.uploadBytes > 0L)
        // La deadline (et non le plafond) a bien arrêté l'upload : la fenêtre
        // dure au moins le budget de phase et le volume reste loin du cap.
        assertTrue("fenêtre upload trop courte: ${result.uploadDurationMillis}",
            result.uploadDurationMillis >= 1_000L)
        assertTrue("volume upload proche du cap: ${result.uploadBytes}",
            result.uploadBytes < 250_000_000L)
        assertNotNull(result.uploadMbps)
    }

    /**
     * Mission 17 — (3) run complet : les deux phases finissent par leur
     * fenêtre temporelle, aucun plafond, aucun arrêt prématuré. Le résultat
     * peut donc être annoncé comme stabilisé.
     */
    @Test fun `full run ends by both time windows without cap`() = runBlocking(Dispatchers.IO) {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(bigBody(2_000_000))
                        .throttleBody(64L * 1_024, 100, TimeUnit.MILLISECONDS)
                } else if (request.method == "POST") {
                    request.body.readAll(object : okio.Sink {
                        override fun write(source: Buffer, byteCount: Long) { source.skip(byteCount) }
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
        val policy = SpeedTestPolicy(
            maxDownloadBytes = 3_000_000,
            maxUploadBytes = 500_000_000,
            maxDurationMillis = 6_000,
            latencyProbes = 1,
        )
        val result = engine(policy, paceUpload = true).run { }
        assertEquals(PhaseEndReason.TIME_WINDOW, result.downloadEndReason)
        assertEquals(PhaseEndReason.TIME_WINDOW, result.uploadEndReason)
        assertFalse(result.cappedDownload)
        assertFalse(result.cappedUpload)
        assertFalse(result.capped)
        assertTrue(SpeedTestCalculator.isStabilised(result))
        assertNotNull(result.downloadMbps)
        assertNotNull(result.uploadMbps)
    }
}
