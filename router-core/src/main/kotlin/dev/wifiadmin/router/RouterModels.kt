package dev.wifiadmin.router

/** The Wi-Fi radio a device is associated with. */
enum class WifiBand {
    BAND_2_4_GHZ,
    BAND_5_GHZ,
}

/**
 * How confident the router data lets us be about a device.
 *
 * `ACTIVE` and `CABLED` are backed by an explicit report from the router.
 * `STALE` requires an objectively expired lease. Everything the data does not
 * justify stays `UNKNOWN`.
 */
enum class DevicePresence {
    /** Currently associated with a Wi-Fi radio (cmd 224 or cmd 225). */
    ACTIVE,

    /** Holds a DHCP lease on a wired interface and is not associated with Wi-Fi. */
    CABLED,

    /** Holds an expired Wi-Fi lease and is not associated with any radio. */
    STALE,

    /** The router data does not say more than this. */
    UNKNOWN,
}

/** Signal information of a Wi-Fi association. */
data class DeviceSignal(
    val rssiDbm: Int?,
    val txRateMbps: String?,
    val rxRateMbps: String?,
    val ssid: String?,
)

/** One device known to the router. */
data class RouterDevice(
    val mac: String,
    val ip: String,
    val name: String,
    val bands: Set<WifiBand>,
    val signal: DeviceSignal?,
    val interfaceName: String,
    val leaseExpiry: String?,
    val ipv6: String?,
    val presence: DevicePresence,
)

/**
 * A point-in-time view of the devices the router knows about.
 *
 * [devices] is always ordered deterministically so the UI does not reshuffle
 * between two identical polls.
 */
data class DeviceSnapshot(
    val devices: List<RouterDevice>,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    /**
     * True when the router delivered **no** device list at all.
     *
     * An absent payload is not an empty network: it means the router
     * answered without the data. The two must never be confused — in
     * particular, a caller must not report "no device connected" when
     * nothing was received.
     */
    val payloadAbsent: Boolean = false,
) {
    val activeCount: Int get() = devices.count { it.presence == DevicePresence.ACTIVE }
    val cabledCount: Int get() = devices.count { it.presence == DevicePresence.CABLED }
    val staleCount: Int get() = devices.count { it.presence == DevicePresence.STALE }
    val unknownCount: Int get() = devices.count { it.presence == DevicePresence.UNKNOWN }
}
