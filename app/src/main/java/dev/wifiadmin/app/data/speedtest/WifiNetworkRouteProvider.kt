package dev.wifiadmin.app.data.speedtest

import javax.net.SocketFactory
import okhttp3.Dns

/**
 * The network route one speed test run is bound to.
 *
 * Pure JVM: no `android.net.*` import, so the engine stays unit-testable
 * with MockWebServer on the JVM. In production the Android adapter binds
 * the sockets and the DNS to the currently selected Wi-Fi `Network`
 * (`Network.socketFactory`, `Network.getAllByName`), so no byte can
 * leave over mobile data even if the default route changes mid-run.
 */
data class WifiRoute(
    val socketFactory: SocketFactory,
    val dns: Dns,
)

/**
 * Resolves the route for one run. Resolved per run, never at
 * construction: an Android `Network` is a live object that can
 * disappear between two runs.
 *
 * Returns null when no suitable Wi-Fi network is available; the engine
 * then fails fast with [SpeedTestFailure.NoWiFi] before any byte is
 * sent. JVM tests inject a fake, or null (no routing imposed).
 */
fun interface WifiNetworkRouteProvider {
    fun resolveForRun(): WifiRoute?
}
