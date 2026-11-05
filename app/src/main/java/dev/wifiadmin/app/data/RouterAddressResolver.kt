package dev.wifiadmin.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
 *
 * ## Why it consumes the monitor
 *
 * The resolver used to register its own `NetworkCallback`. It now consumes
 * the [NetworkMonitor] instead, because the platform counts every outstanding
 * callback against a system-wide limit of 100 per application, and two
 * observers of the same radio would duplicate the work and could disagree.
 */
class RouterAddressResolver(private val monitor: NetworkMonitor) {

    /**
     * The address suggested for the current Wi-Fi network.
     *
     * Emits `null` when no address can be determined.
     */
    fun observeSuggestedAddress(): Flow<String?> = monitor.observeStatus()
        .map { status ->
            when (status) {
                is LocalNetworkStatus.LocalWifi -> status.identity.gateway
                is LocalNetworkStatus.DifferentNetwork -> status.identity.gateway
                LocalNetworkStatus.NoWifi,
                LocalNetworkStatus.NotOnWifi -> null
            }
        }
        .distinctUntilChanged()

    /** The address currently suggested, or `null`. */
    fun currentSuggestion(): String? = when (val status = monitor.currentStatus()) {
        is LocalNetworkStatus.LocalWifi -> status.identity.gateway
        is LocalNetworkStatus.DifferentNetwork -> status.identity.gateway
        LocalNetworkStatus.NoWifi,
        LocalNetworkStatus.NotOnWifi -> null
    }
}
