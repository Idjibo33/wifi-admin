package dev.wifiadmin.app.data

/**
 * How confident the application is about the current network.
 *
 * The levels exist because the IP address alone cannot identify a router:
 * `192.168.1.1` is the default gateway of practically every home router.
 */
enum class NetworkConfidence {
    /** Nothing is known yet. */
    UNKNOWN,

    /** The local Wi-Fi network is there and its gateway is the session one. */
    LOCAL_DEFAULT,

    /** The local Wi-Fi network is there but its gateway is another one. */
    DIFFERENT,

    /** No local Wi-Fi network is available. */
    ABSENT,
}

/**
 * What the current network means for the session.
 *
 * @param status the observed state.
 * @param confidence how sure the application is.
 * @param sessionHost the host of the authenticated session, or null.
 */
data class NetworkAssessment(
    val status: LocalNetworkStatus,
    val confidence: NetworkConfidence,
    val sessionHost: String? = null,
) {
    /** True when the session can no longer be trusted on this network. */
    val isNetworkLost: Boolean
        get() = confidence == NetworkConfidence.ABSENT ||
            confidence == NetworkConfidence.DIFFERENT
}

/**
 * Decides what the current network means for the session.
 *
 * **Pure function**: no Android, no I/O, no clock. Every scenario of the
 * mission is a unit test of this function.
 *
 * ## The rules
 *
 * - A Wi-Fi network whose gateway is the one of the session is **probably**
 *   the right network. Nothing more can be proven from an address.
 * - A Wi-Fi network whose gateway is **another** one is a **different**
 *   network: the session cannot be trusted there.
 * - No Wi-Fi network at all means the local network is **gone**. This is
 *   **not** a session expiry: the session may still be valid, the route to
 *   the router simply does not exist any more.
 * - A mobile hotspot is a Wi-Fi network that is also cellular: it is never
 *   the router, and it is reported as "no local network".
 *
 * @param status the state observed by the [NetworkMonitor].
 * @param sessionHost the host of the authenticated session, or null when no
 *   session is open.
 */
fun assessNetwork(
    status: LocalNetworkStatus,
    sessionHost: String?,
): NetworkAssessment {
    return when (status) {
        is LocalNetworkStatus.LocalWifi ->
            if (sessionHost != null &&
                !status.identity.gateway.equals(sessionHost, ignoreCase = true)
            ) {
                // Another gateway: another network, and therefore another
                // router as far as the application can tell.
                NetworkAssessment(status, NetworkConfidence.DIFFERENT, sessionHost)
            } else {
                NetworkAssessment(status, NetworkConfidence.LOCAL_DEFAULT, sessionHost)
            }

        is LocalNetworkStatus.DifferentNetwork ->
            NetworkAssessment(status, NetworkConfidence.DIFFERENT, sessionHost)

        LocalNetworkStatus.NoWifi ->
            NetworkAssessment(status, NetworkConfidence.ABSENT, sessionHost)

        LocalNetworkStatus.NotOnWifi ->
            NetworkAssessment(status, NetworkConfidence.ABSENT, sessionHost)
    }
}
