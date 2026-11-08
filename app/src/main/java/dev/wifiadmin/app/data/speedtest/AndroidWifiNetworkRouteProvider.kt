package dev.wifiadmin.app.data.speedtest

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import java.net.UnknownHostException

/**
 * The production [WifiNetworkRouteProvider]: binds one run to the
 * currently selected Wi-Fi network.
 *
 * The Wi-Fi network is accepted only with the Wi-Fi transport and
 * without the cellular transport (a hotspot is a Wi-Fi network that
 * also carries the cellular transport, and is never the route).
 * Without such a network the run fails fast with `NoWiFi` instead of
 * falling back to any other network.
 *
 * This is deliberately local to the speed test client: it neither
 * binds the process (like `bindProcessToNetwork`, rejected because
 * the router traffic must stay on its own network) nor logs anything.
 */
class AndroidWifiNetworkRouteProvider(
    private val connectivity: ConnectivityManager,
) : WifiNetworkRouteProvider {

    override fun resolveForRun(): WifiRoute? {
        val network = connectivity.allNetworks.firstOrNull { candidate ->
            val capabilities = connectivity.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } ?: return null

        return WifiRoute(
            socketFactory = network.socketFactory,
            // DNS goes through the same network, never the default route.
            dns = object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val resolved = network.getAllByName(hostname)
                    if (resolved.isEmpty()) throw UnknownHostException(hostname)
                    return resolved.toList()
                }
            },
        )
    }
}
