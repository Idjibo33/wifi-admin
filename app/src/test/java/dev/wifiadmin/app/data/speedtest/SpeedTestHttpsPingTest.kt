package dev.wifiadmin.app.data.speedtest

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Contrat LibreSpeed minimal (Mission 14) — ping seul, zéro benchmark.
 * MockWebServer en boucle locale + validation du schéma d'endpoint.
 * Ne prouve pas la liaison physique Wi-Fi (recette appareil, instrumenté).
 * Ne prouve pas non plus le TLS en soi (réservé au test instrumenté
 * SpeedTestHttpsPingInstrumentedTest avec CA debug).
 */
class SpeedTestHttpsPingTest {
    private val server = MockWebServer()
    @After fun tearDown() { server.shutdown() }

    @Test fun `http ping via route simulee`() = runBlocking {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                if (req.method == "GET" && (req.path ?: "").contains("empty.php")) {
                    MockResponse().setResponseCode(200).setBody("")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        var dnsCalls = 0
        val lookedUp = mutableListOf<String>()
        val httpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsCalls++
                    lookedUp += hostname
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            })
            .build()
        val endpoint = SpeedTestEndpointValidator.parse(server.url("/").toString())!!

        val ms = LibreSpeedClient(httpClient, endpoint).pingMillis()
        assertTrue(ms >= 1L)
        assertEquals("exactement 1 requête", 1, server.requestCount)
        assertEquals(1, dnsCalls)
        assertTrue(lookedUp.isNotEmpty())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue((req.path ?: "").startsWith("/empty.php"))
    }

    @Test fun `validation sommet - https LAN accepte http LAN refuse`() {
        val rejected = SpeedTestEndpointValidator.parse("http://192.168.1.174:8280/")
        assertTrue(rejected == null)
        val accepted = SpeedTestEndpointValidator.parse("https://192.168.1.174:8443/")
        assertTrue(accepted != null && accepted.baseUrl == "https://192.168.1.174:8443")
        val loopbackHttp = SpeedTestEndpointValidator.parse("http://127.0.0.1:8280/")
        assertTrue(loopbackHttp != null)
    }
}
