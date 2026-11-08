package dev.wifiadmin.app.data.speedtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import okio.Buffer
import okio.Sink
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Seam S1 : LibreSpeedClient.upload() contre MockWebServer loopback (127.0.0.1 uniquement).
 *
 * Aucun test ne contacte Internet. Le compteur mesure les octets écrits dans le flux
 * applicatif (BufferedSink), pas les octets physiques réseau : les assertions de volume
 * comparent la valeur retournée et le corps POST capturé côté serveur Dispatcher.
 */
class LibreSpeedUploadTest {

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

    private fun client(): LibreSpeedClient {
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!
        return LibreSpeedClient(httpClient(), endpoint)
    }

    private fun capturingDispatcher(captured: AtomicReference<ByteArray?>): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    val tmp = Buffer()
                    request.body.readAll(object : Sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            tmp.write(source, byteCount)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                        override fun timeout() = Timeout.NONE
                    })
                    captured.set(tmp.readByteArray())
                    return MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
                return MockResponse().setResponseCode(200).setBody("onspeedtest")
            }
        }

    @Test fun `deux blocs consecutifs de 64 KiB du corps POST different`() = runBlocking {
        server.start()
        val captured = AtomicReference<ByteArray?>()
        server.dispatcher = capturingDispatcher(captured)
        val maxBytes = 3L * 65_536L
        val returned = client().upload(maxBytes) { }
        val body = captured.get()!!
        assertEquals(maxBytes, body.size.toLong())
        assertEquals(maxBytes, returned)
        val block0 = body.copyOfRange(0, 65_536)
        val block1 = body.copyOfRange(65_536, 2 * 65_536)
        assertFalse(
            "Anti-repetition : deux blocs consecutifs de 64 KiB doivent differer " +
                "(memes octets consecutifs = meme bloc re-ecrit)",
            block0.contentEquals(block1),
        )
    }

    @Test fun `upload retourne au plus maxBytes et serveur recoit exactement ce retour`() = runBlocking {
        server.start()
        val captured = AtomicReference<ByteArray?>()
        server.dispatcher = capturingDispatcher(captured)
        val maxBytes = 200_000L
        val returned = client().upload(maxBytes) { }
        val body = captured.get()!!
        assertTrue("retourne doit etre <= maxBytes, etait $returned", returned <= maxBytes)
        assertEquals(
            "serveur doit recevoir exactement le nombre d'octets retourne (octets ecrits dans le flux applicatif)",
            returned,
            body.size.toLong(),
        )
    }

    @Test fun `cancel pendant upload lent leve CancellationException octets inferieurs a maxBytes`() = runBlocking {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    return MockResponse().setResponseCode(200).setBody("onspeedtest").apply {
                        socketPolicy = SocketPolicy.DO_NOT_READ_REQUEST_BODY
                    }
                }
                return MockResponse().setResponseCode(200).setBody("onspeedtest")
            }
        }
        // Compteur applicatif : compte les octets réellement écrits dans le
        // BufferedSink (même compteur que LibreSpeedClient.written), pas les
        // octets physiques du réseau. Le 1er chunk bloque sur un latch
        // applicatif déterministe (pas de sleep) pour que cancel() tombe
        // mid-upload quelle que soit la taille des buffers TCP loopback.
        val counted = AtomicLong(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!
        val countingHttp = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                val body = req.body
                if (req.method == "POST" && body != null) {
                    val wrapped = object : RequestBody() {
                        override fun contentLength(): Long = body.contentLength()
                        override fun contentType() = body.contentType()
                        override fun writeTo(sink: BufferedSink) {
                            // Staging : le corps d'origine est matérialisé en mémoire
                            // puis retransmis en chunks comptés vers le sink réseau.
                            val staging = Buffer()
                            body.writeTo(staging)
                            var first = true
                            while (staging.size > 0) {
                                val n = minOf(staging.size, 65_536L)
                                sink.write(staging, n)
                                counted.addAndGet(n)
                                if (first) {
                                    first = false
                                    started.countDown()
                                    release.await(10, TimeUnit.SECONDS)
                                }
                            }
                            sink.flush()
                        }
                    }
                    chain.proceed(req.newBuilder().post(wrapped).build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
        val c = LibreSpeedClient(countingHttp, endpoint)
        val maxBytes = 5_000_000L
        val thrownRef = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker = Thread {
            try {
                runBlocking { c.upload(maxBytes) { } }
            } catch (e: Throwable) {
                thrownRef.set(e)
            } finally {
                done.countDown()
            }
        }
        worker.start()
        assertTrue("upload doit etre en vol avant cancel", started.await(10, TimeUnit.SECONDS))
        c.cancel()
        val countedBeforeThrow = counted.get()
        release.countDown()
        assertTrue("upload doit se terminer apres cancel", done.await(10, TimeUnit.SECONDS))
        worker.join(5_000)
        release.countDown()
        val thrown = thrownRef.get()
        assertTrue(
            "cancel() doit lever CancellationException, mais a leve $thrown (countedBeforeThrow=$countedBeforeThrow)",
            thrown is CancellationException,
        )
        assertTrue("client doit etre marque cancelled", c.isCancelled())
        assertTrue(
            "octets ecrits dans le flux applicatif apres cancel ($countedBeforeThrow) doivent etre < maxBytes ($maxBytes)",
            countedBeforeThrow < maxBytes,
        )
        // Ressources fermées : un nouvel upload sur un client cancellé échoue immédiatement.
        try {
            c.upload(1_000L) { }
            fail("un nouvel upload apres cancel() doit lever CancellationException immediatement")
        } catch (e: CancellationException) {
            // Attendu.
        }
    }

    @Test fun `deux uploads avec meme graine produisent le meme premier bloc`() = runBlocking {
        server.start()
        val captures = Collections.synchronizedList(mutableListOf<ByteArray>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    val tmp = Buffer()
                    request.body.readAll(object : Sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            tmp.write(source, byteCount)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                        override fun timeout() = Timeout.NONE
                    })
                    captures.add(tmp.readByteArray())
                    return MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
                return MockResponse().setResponseCode(200).setBody("onspeedtest")
            }
        }
        val c = client()
        val maxBytes = 2L * 65_536L
        val first = c.upload(maxBytes) { }
        val second = c.upload(maxBytes) { }
        assertEquals(first, second)
        assertEquals(2, captures.size)
        // Propriete observee (pas de recompute PRNG dans le test) : le 1er bloc capture
        // du 1er upload est egal au 1er bloc capture du 2e upload.
        assertTrue(
            "premier bloc reproductible avec meme graine",
            captures[0].copyOfRange(0, 65_536).contentEquals(captures[1].copyOfRange(0, 65_536)),
        )
    }
}
