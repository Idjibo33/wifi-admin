package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
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
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

/**
 * Seam S1-R : routage Wi-Fi exclusif, sans Android, sans Internet.
 * Les fakes prouvent le branchement (sockets + DNS) ; ils ne prouvent
 * pas la liaison physique sur OEM (recette appareil, jamais un test
 * automatisé vers Internet).
 */
class SpeedTestRoutingTest {

    private val server = MockWebServer()

    @After fun tearDown() { server.shutdown() }

    private var resolutions = 0
    private var lookedUpHosts = mutableListOf<String>()

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun engine(
        provider: WifiNetworkRouteProvider,
        policy: SpeedTestPolicy = SpeedTestPolicy(
            maxDownloadBytes = 5_000_000,
            maxUploadBytes = 500_000,
            latencyProbes = 2,
        ),
    ): LibreSpeedEngine {
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!
        return LibreSpeedEngine(
            endpoint = endpoint,
            baseHttpClient = httpClient(),
            policy = policy,
            clock = TestAppClock(),
            routeProvider = provider,
        )
    }

    private fun route(downloadBytes: Long) {
        val body = bigBody(downloadBytes)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("garbage.php")) {
                    MockResponse().setResponseCode(200)
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
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                } else {
                    MockResponse().setResponseCode(200).setBody("onspeedtest")
                }
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

    @Test fun `no wifi route fails before any network call`() = runBlocking {
        server.start()
        route(100_000)
        val e = engine(provider = WifiNetworkRouteProvider { null })
        try {
            e.run { }
            throw AssertionError("expected NoWiFi")
        } catch (ex: SpeedTestFailureException) {
            assertEquals(SpeedTestFailure.NoWiFi, ex.failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `route dns and socket factory are used by the run`() = runBlocking {
        server.start()
        route(100_000)
        val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = SocketFactory.getDefault().createSocket()
            override fun createSocket(host: String?, port: Int): Socket =
                SocketFactory.getDefault().createSocket(host, port)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                SocketFactory.getDefault().createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                SocketFactory.getDefault().createSocket(host, port)
            override fun createSocket(host: InetAddress?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                SocketFactory.getDefault().createSocket(host, port, localHost, localPort)
        }
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookedUpHosts.add(hostname)
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val e = engine(
            provider = WifiNetworkRouteProvider {
                resolutions++
                WifiRoute(socketFactory = socketFactory, dns = dns)
            },
        )
        val result = e.run { }
        assertEquals(100_000L, result.downloadBytes)
        assertTrue(lookedUpHosts.isNotEmpty())
        assertEquals(1, resolutions)
    }

    @Test fun `two runs resolve the route twice`() = runBlocking {
        server.start()
        route(100_000)
        val e = engine(
            provider = WifiNetworkRouteProvider {
                resolutions++
                WifiRoute(socketFactory = SocketFactory.getDefault(), dns = Dns.SYSTEM)
            },
        )
        e.run { }
        e.run { }
        assertEquals(2, resolutions)
    }
}
