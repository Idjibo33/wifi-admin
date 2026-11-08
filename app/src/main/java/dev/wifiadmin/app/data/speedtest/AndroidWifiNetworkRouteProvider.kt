package dev.wifiadmin.app.data.speedtest

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkMonitor
import okhttp3.Dns
import java.net.UnknownHostException

/**
 * The production [WifiNetworkRouteProvider]: binds one run to the
 * Wi-Fi network validated by the [NetworkMonitor] for this run.
 *
 * The monitor is re-read at resolution time (not snapshotted at
 * construction): the run's route is rejected unless the monitor still
 * reports `LocalWifi`, and the candidate network must match that
 * validation by interface name (`NetworkIdentity.interfaceName`).
 * Without such a network the run fails fast with `NoWiFi` instead of
 * falling back to any other network.
 *
 * This is deliberately local to the speed test client: it neither
 * binds the process (like `bindProcessToNetwork`, rejected because
 * the router traffic must stay on its own network) nor logs anything.
 */
class AndroidWifiNetworkRouteProvider(
    private val connectivity: ConnectivityManager,
    private val monitor: NetworkMonitor,
) : WifiNetworkRouteProvider {

    override fun resolveForRun(): WifiRoute? {
        val status = monitor.currentStatus()
        if (status !is LocalNetworkStatus.LocalWifi) return null
        val wantedInterface = status.identity.interfaceName

        val network = connectivity.allNetworks
            .filter { candidate ->
                val capabilities = connectivity.getNetworkCapabilities(candidate) ?: return@filter false
                // A hotspot is a Wi-Fi network that also carries the
                // cellular transport, and is never the route.
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            .firstOrNull { candidate ->
                val link = connectivity.getLinkProperties(candidate) ?: return@firstOrNull false
                // Match the network the repository validated, never an
                // arbitrary Wi-Fi network that happens to be available.
                wantedInterface == null || link.interfaceName == wantedInterface
            }
            ?: connectivity.activeNetwork?.takeIf { active ->
                val capabilities = connectivity.getNetworkCapabilities(active) ?: return@takeIf false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    (wantedInterface == null ||
                        connectivity.getLinkProperties(active)?.interfaceName == wantedInterface)
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
