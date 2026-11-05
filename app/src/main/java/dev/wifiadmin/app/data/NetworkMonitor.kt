package dev.wifiadmin.app.data

import kotlinx.coroutines.flow.Flow

/**
 * What the application knows about the network the phone is on.
 *
 * The distinction matters: a local Wi-Fi network works **without** Internet,
 * and a phone can browse the web over mobile data while the router is
 * unreachable. Neither situation means the router is gone.
 */
sealed interface LocalNetworkStatus {

    /**
     * A Wi-Fi network is connected.
     *
     * [identity] describes it: it is what tells one network from another.
     */
    data class LocalWifi(val identity: NetworkIdentity) : LocalNetworkStatus

    /**
     * The phone is on a Wi-Fi network, but it is **not** the one the router
     * was reached on.
     *
     * The gateway may be the same address on a different network: nothing
     * proves the router is the same device. The application must not present
     * the previous router's devices as the current ones.
     */
    data class DifferentNetwork(val identity: NetworkIdentity) : LocalNetworkStatus

    /**
     * No Wi-Fi network is available.
     *
     * The router cannot be reached, but this is **not** a session expiry.
     */
    data object NoWifi : LocalNetworkStatus

    /**
     * The phone is not on Wi-Fi at all (mobile data, ethernet, …).
     *
     * The local network is unreachable.
     */
    data object NotOnWifi : LocalNetworkStatus
}

/**
 * Observes the network the phone is on.
 *
 * The interface exists so the application can be tested on the JVM: the
 * production implementation talks to `ConnectivityManager`, the tests use a
 * fake that emits whatever the scenario needs.
 */
interface NetworkMonitor {

    /**
     * The current status, and every change of it.
     *
     * The flow must be **cold**: the monitoring starts when it is collected
     * and stops when the collector is gone. A callback that outlives its
     * collector would keep the network callbacks registered for nothing.
     */
    fun observeStatus(): Flow<LocalNetworkStatus>

    /** The status right now, without registering anything. */
    fun currentStatus(): LocalNetworkStatus
}
