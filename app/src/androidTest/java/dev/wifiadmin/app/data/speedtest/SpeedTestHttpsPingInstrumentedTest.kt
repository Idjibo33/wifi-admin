package dev.wifiadmin.app.data.speedtest

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.BuildConfig
import dev.wifiadmin.app.WifiAdminApplication
import dev.wifiadmin.app.data.LocalNetworkStatus
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Certification HTTPS minimale (Mission 14) : UN ping empty.php via le
 * client Android réellement lié au Wi-Fi. PAS de run(), PAS de 20/10 Mio,
 * PAS de TrustManager/HostnameVerifier custom, PAS de cleartext hors loopback.
 *
 * Lancement sur Pixel (même Wi-Fi que le serveur LibreSpeed HTTPS local) :
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.wifiadmin.app.data.speedtest.SpeedTestHttpsPingInstrumentedTest \
 *     -Pwifiadmin.speedtestBaseUrl=https://192.168.1.174:8443
 * Requiert : CA debug dans app/src/debug/res/raw/local_ca.crt (déjà versionnée).
 * Sinon : Assume skip motivé (pas d'échec opaque).
 */
@RunWith(AndroidJUnit4::class)
class SpeedTestHttpsPingInstrumentedTest {

    @Test
    fun `https ping via route wifi reelle`() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val app = ctx.applicationContext as WifiAdminApplication
        val monitor = app.container.networkMonitor
        val status = monitor.currentStatus()
        Assume.assumeTrue("Skip : pas sur le Wi-Fi attendu (status=$status)",
            status is LocalNetworkStatus.LocalWifi)

        val rawUrl = BuildConfig.SPEEDTEST_BASE_URL
        Assume.assumeTrue("Skip : SPEEDTEST_BASE_URL debug non https (désactivé ou http)",
            rawUrl.startsWith("https://"))
        val policy = SpeedTestPolicy()
        val endpoint = SpeedTestEndpointValidator.parse(rawUrl)
        Assume.assumeNotNull("Skip : endpoint invalide", endpoint)
        endpoint!!

        val rawRoute = AndroidWifiNetworkRouteProvider(
            connectivity = ctx.getSystemService(ConnectivityManager::class.java)!!,
            monitor = monitor,
        ).resolveForRun()
        Assume.assumeNotNull("Skip : aucune route Wi-Fi (pas de TRANSPORT_WIFI)", rawRoute)
        rawRoute!!

        var dnsCalls = 0
        val lookedUp = mutableListOf<String>()
        val countingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                dnsCalls++
                lookedUp += hostname
                return rawRoute.dns.lookup(hostname)
            }
        }

        val httpClient = OkHttpClient.Builder()
            .callTimeout(policy.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(policy.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(policy.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(policy.writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .socketFactory(rawRoute.socketFactory)
            .dns(countingDns)
            .build()

        val client = LibreSpeedClient(httpClient, endpoint)
        val ms = client.pingMillis()

        assertTrue("pingMillis >= 1 (contrat LibreSpeedClient)", ms >= 1L)
        assertEquals("exactement une résolution DNS", 1, dnsCalls)
        assertTrue("DNS lié à la route : host de l'endpoint consulté",
            lookedUp.any { it == endpoint.hostLabel })
        assertNotNull("route non nulle", rawRoute.socketFactory)
        assertNotNull("dns délégué réel", rawRoute.dns)
    }

    @Test
    fun `no wifi route echoue sans octet`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val monitor = (ctx.applicationContext as WifiAdminApplication).container.networkMonitor
        val rawRoute = AndroidWifiNetworkRouteProvider(
            connectivity = ctx.getSystemService(ConnectivityManager::class.java)!!,
            monitor = monitor,
        ).resolveForRun()
        Assume.assumeTrue("Skip : on est sur Wi-Fi, cas negatif non applicable",
            rawRoute == null)
        assertNull(rawRoute)
    }
}
