package dev.wifiadmin.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Finds the router address from the Wi-Fi connection the phone is on.
 *
 * The source is the **default gateway** of the active Wi-Fi network: it is the
 * address the phone uses to reach the outside world, which on a home router
 * is the router itself.
 *
 * A fallback based on the BSSID was considered and **rejected**: the BSSID is
 * the hardware address of the access point, whose prefix has no relation to
 * the IP range of the router (on the tested device the BSSID starts with
 * `d8:42:f7` while the router is `192.168.1.1`). Suggesting a wrong address
 * is worse than suggesting none.
 *
 * The result is a **suggestion**, never an assertion: the user can always
 * change the address, and the field is never filled silently.
 */
class RouterAddressResolver(private val context: Context) {

    /**
     * The address suggested for the current Wi-Fi network.
     *
     * Emits `null` when no address can be determined.
     */
    fun observeSuggestedAddress(): Flow<String?> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)

        if (connectivity == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentSuggestion())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentSuggestion())
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        trySend(currentSuggestion())

        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** The address currently suggested, or `null`. */
    fun currentSuggestion(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        return gatewayAddress(connectivity)
    }

    /**
     * The default gateway of the active Wi-Fi network.
     *
     * This is the address the phone uses to reach the outside world, which on
     * a home router is the router itself. `LinkProperties.routes` is the
     * reliable source: the gateway of the default route.
     */
    private fun gatewayAddress(connectivity: ConnectivityManager): String? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val properties: LinkProperties = connectivity.getLinkProperties(network) ?: return null

        return properties.routes
            .asSequence()
            .filter { it.isDefaultRoute }
            .mapNotNull { route -> route.gateway?.hostAddress }
            .firstOrNull { it != null && it.isNotBlank() && it != "0.0.0.0" }
    }
}
