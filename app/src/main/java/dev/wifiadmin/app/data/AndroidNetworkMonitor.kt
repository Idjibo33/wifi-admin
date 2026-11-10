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
 * The production [NetworkMonitor], backed by `ConnectivityManager`.
 *
 * ## What it watches
 *
 * The **Wi-Fi** networks, not the default network. A default-network callback
 * would fire when the phone switches to mobile data, which says nothing about
 * the local network, and it would stop reporting the Wi-Fi network as soon as
 * it is no longer the default one.
 *
 * ## What it does not use
 *
 * - `NET_CAPABILITY_VALIDATED` is **never** a condition: a local Wi-Fi
 *   network without Internet is perfectly able to reach the router.
 * - The SSID and the BSSID are never read: they would require a location
 *   permission on recent Android versions, and they are not needed here.
 * - `getLinkProperties()` is never called from a callback: the platform
 *   explicitly warns that the synchronous getters are prone to races, and
 *   the callback already carries the properties. Every value used here comes
 *   from the callback arguments.
 *
 * ## The hotspot case
 *
 * A mobile hotspot is a Wi-Fi network that **also** carries the cellular
 * transport. It is never the router, and it must never be reported as the
 * local network.
 */
class AndroidNetworkMonitor(
    private val context: Context,
) : NetworkMonitor {

    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    override fun observeStatus(): Flow<LocalNetworkStatus> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(LocalNetworkStatus.NotOnWifi)
            close()
            return@callbackFlow
        }

        // The Wi-Fi network the application cares about, and the properties
        // the platform gave us for it.
        var current: Network? = null
        var currentProperties: LinkProperties? = null
        var currentCapabilities: NetworkCapabilities? = null

        fun emitStatus() {
            trySend(
                statusOf(
                    capabilities = currentCapabilities,
                    properties = currentProperties,
                )
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network
                currentProperties = manager.getLinkProperties(network)
                currentCapabilities = manager.getNetworkCapabilities(network)
                emitStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (network != current) return
                currentCapabilities = networkCapabilities
                emitStatus()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                if (network != current) return
                currentProperties = linkProperties
                emitStatus()
            }

            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                currentProperties = null
                currentCapabilities = null
                trySend(LocalNetworkStatus.NoWifi)
            }
        }

        val request = NetworkRequest.Builder()
            // The transport alone: no `NET_CAPABILITY_INTERNET`, so a local
            // network without Internet is still matched.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override fun currentStatus(): LocalNetworkStatus {
        val manager = connectivity ?: return LocalNetworkStatus.NotOnWifi
        // Sous VPN, `activeNetwork` est le VPN, pas le Wi-Fi : lire le réseau
        // actif seul retournerait NotOnWifi alors même que le Wi-Fi est
        // présent dans `allNetworks`. On cherche donc le candidat Wi-Fi
        // (non-hotspot) avec une route par défaut, comme dans `observeStatus`.
        val wifi = manager.allNetworks.firstOrNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        if (wifi != null) {
            val capabilities = manager.getNetworkCapabilities(wifi)
            return statusOf(
                capabilities = capabilities,
                properties = manager.getLinkProperties(wifi),
            )
        }
        // Aucun Wi-Fi vivant : fallback sur l'actif pour distinguer NoWifi /
        // NotOnWifi (mobile, Ethernet...).
        val network = manager.activeNetwork ?: return LocalNetworkStatus.NoWifi
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return LocalNetworkStatus.NotOnWifi
        // Un réseau actif sans transport Wi-Fi n'est pas le LAN.
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (isWifi && isCellular) return LocalNetworkStatus.NotOnWifi
        if (!isWifi) return LocalNetworkStatus.NotOnWifi
        return statusOf(
            capabilities = capabilities,
            properties = manager.getLinkProperties(network),
        )
    }

    /**
     * Vrai si un réseau VPN est présent dans `allNetworks`.
     *
     * Ce n'est jamais un verdict d'inaccessibilité : le VPN peut être en
     * split-tunnel autorisant le LAN. Seules les sonde de joignabilité le
     * prouveraient — jamais la seule présence de `TRANSPORT_VPN`.
     */
    override fun isVpnPresent(): Boolean {
        val manager = connectivity ?: return false
        return manager.allNetworks.any { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate) ?: return@any false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun statusOf(
        capabilities: NetworkCapabilities?,
        properties: LinkProperties?,
    ): LocalNetworkStatus {
        if (capabilities == null) return LocalNetworkStatus.NotOnWifi

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        if (isWifi && isCellular) {
            // A tethered hotspot: a Wi-Fi network that is the mobile
            // network. It is never the router.
            return LocalNetworkStatus.NotOnWifi
        }

        if (!isWifi) return LocalNetworkStatus.NotOnWifi

        val identity = properties?.identity() ?: NetworkIdentity.UNKNOWN
        if (identity.gateway == null) {
            // A Wi-Fi network without a usable default route cannot reach
            // anything, the router included.
            return LocalNetworkStatus.NoWifi
        }
        return LocalNetworkStatus.LocalWifi(identity)
    }

    /**
     * The identity of the network these properties describe.
     *
     * The address range is taken from the link addresses, so two networks
     * with the same gateway but a different range are told apart.
     */
    private fun LinkProperties.identity(): NetworkIdentity {
        val link = linkAddresses.firstOrNull()
        val prefix = link?.prefixLength
        val network = link?.address?.hostAddress
        return NetworkIdentity(
            interfaceName = interfaceName,
            addressPrefix = if (network != null && prefix != null) {
                network.substringBeforeLast('.') + ".0/$prefix"
            } else {
                null
            },
            gateway = defaultGatewayOf(
                routes.map { route ->
                    RouteSummary(
                        isDefault = route.isDefaultRoute,
                        gateway = route.gateway?.hostAddress,
                    )
                }
            ),
        )
    }
}
