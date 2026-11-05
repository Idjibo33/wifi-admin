package dev.wifiadmin.app.data

/**
 * The identity of the local network the phone is on.
 *
 * It answers one question: **is this the same network as before?**
 *
 * The gateway alone is not enough: `192.168.1.1` is the default gateway of
 * practically every home router, and `admin` is the default account. Two
 * different routers would then share the same identity, and the device list
 * of one would be shown for the other.
 *
 * The identity is therefore built from three things the platform exposes
 * **without a location permission**:
 *
 * - the **interface** the network is bound to (`wlan0`, …);
 * - the **address range** of the network (`192.168.1.0/24`, …);
 * - the **gateway**.
 *
 * The BSSID would be more precise — it identifies the access point itself —
 * but reading it requires `ACCESS_FINE_LOCATION` on recent Android versions.
 * This application deliberately does not ask for that permission, so the
 * identity is deliberately weaker. The consequence is accepted and honest:
 * two networks that share an interface name, an address range and a gateway
 * are considered the same, and the application says "routeur détecté" rather
 * than claiming to know which physical device it is talking to.
 */
data class NetworkIdentity(
    val interfaceName: String?,
    val addressPrefix: String?,
    val gateway: String?,
) {

    /**
     * A stable string that identifies the network.
     *
     * An empty string means "unknown": the identity is then only as precise
     * as the gateway.
     */
    val key: String
        get() = listOf(
            interfaceName.orEmpty(),
            addressPrefix.orEmpty(),
            gateway.orEmpty(),
        ).joinToString("|")

    companion object {
        /** The identity used when nothing could be detected. */
        val UNKNOWN = NetworkIdentity(null, null, null)
    }
}
