package dev.wifiadmin.router

/**
 * Merges the DHCP lease list (cmd 223) with the two Wi-Fi association lists
 * (cmd 224 and cmd 225) into one list of devices.
 *
 * The merger is a pure function of its inputs, so it can be unit tested
 * without any JSON or network access.
 *
 * Unlike the reference implementation it does not lose a 5 GHz association
 * because a 2.4 GHz entry with the same MAC was seen afterwards: both bands
 * end up in [RouterDevice.bands] and the 5 GHz signal wins.
 */
object DeviceMerger {

    /** Interface names that mean "wired". */
    private val WIRED_INTERFACE_MARKERS = listOf("lan", "eth", "ethernet")

    /** Interface names that mean "wireless". Checked before the wired ones. */
    private val WIRELESS_INTERFACE_MARKERS = listOf("wlan", "wifi", "wi-fi", "wireless")

    /**
     * Merges the three router lists into a snapshot.
     *
     * @param leases the DHCP leases (cmd 223).
     * @param wifi24 the 2.4 GHz associations (cmd 224).
     * @param wifi5 the 5 GHz associations (cmd 225).
     * @param nowEpochSeconds current time, used to judge whether a lease has expired.
     */
    fun merge(
        leases: List<DhcpLease>,
        wifi24: List<WifiAssociation>,
        wifi5: List<WifiAssociation>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): DeviceSnapshot {
        val associations = buildAssociations(wifi24, wifi5)

        val devices = mutableListOf<RouterDevice>()
        val coveredAssociations = mutableSetOf<BandAssociation>()

        for (lease in deduplicateLeases(leases)) {
            val mac = normalizeMac(lease.mac)
            val ip = lease.ip.trim()
            val matches = associations.match(mac, ip)
            coveredAssociations += matches

            devices += RouterDevice(
                mac = mac,
                ip = ip,
                name = normalizeHostname(lease.hostname),
                bands = matches.map { it.band }.toSet(),
                signal = matches.primarySignal(),
                interfaceName = lease.interfaceName?.trim().orEmpty(),
                leaseExpiry = lease.leaseExpiry?.trim()?.takeIf { it.isNotEmpty() },
                ipv6 = lease.ipv6?.trim()?.takeIf { it.isNotEmpty() },
                presence = classify(matches, lease, nowEpochSeconds),
            )
        }

        // Associations without a DHCP lease still are devices: the router sees them
        // on the radio, so they must not be dropped. An association already joined
        // to a lease (by MAC or by IP fallback) is not emitted a second time, and
        // neither is an association that only repeats the IP of a lease already
        // represented by another association.
        val representedIps = devices.map { it.ip }.filter { it.isNotEmpty() }.toMutableSet()
        for ((key, group) in associations.byDevice) {
            val remaining = group.filterNot { it in coveredAssociations }
            if (remaining.isEmpty()) continue
            val primary = remaining.first()
            val primaryAssociation = primary.association
            val primaryIp = primaryAssociation.ip?.trim().orEmpty()
            // The lease already represents this IP through another association:
            // the MAC match wins, so this entry is not a separate device.
            if (primaryIp.isNotEmpty() && primaryIp in representedIps) continue
            if (primaryIp.isNotEmpty()) representedIps += primaryIp
            devices += RouterDevice(
                mac = normalizeMac(primaryAssociation.mac.orEmpty()),
                ip = primaryIp,
                name = UNKNOWN_DEVICE_NAME,
                bands = remaining.map { it.band }.toSet(),
                signal = remaining.primarySignal(),
                interfaceName = "",
                leaseExpiry = null,
                ipv6 = null,
                presence = DevicePresence.ACTIVE,
            )
        }

        return DeviceSnapshot(devices = devices.sortedWith(DEVICE_ORDER))
    }

    // ---- Classification ----

    private fun classify(
        matches: List<BandAssociation>,
        lease: DhcpLease,
        nowEpochSeconds: Long,
    ): DevicePresence {
        // The router reports a current radio association: the device is active.
        if (matches.isNotEmpty()) return DevicePresence.ACTIVE

        return when (interfaceKind(lease.interfaceName)) {
            InterfaceKind.WIRED -> DevicePresence.CABLED
            InterfaceKind.WIRELESS ->
                if (isLeaseExpired(lease.leaseExpiry, nowEpochSeconds)) {
                    DevicePresence.STALE
                } else {
                    DevicePresence.UNKNOWN
                }
            InterfaceKind.UNKNOWN -> DevicePresence.UNKNOWN
        }
    }

    private enum class InterfaceKind { WIRED, WIRELESS, UNKNOWN }

    private fun interfaceKind(interfaceName: String?): InterfaceKind {
        val value = interfaceName?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return InterfaceKind.UNKNOWN
        // "wlan" also contains "lan", so the wireless markers are checked first.
        if (WIRELESS_INTERFACE_MARKERS.any { value.contains(it) }) return InterfaceKind.WIRELESS
        if (WIRED_INTERFACE_MARKERS.any { value.contains(it) }) return InterfaceKind.WIRED
        return InterfaceKind.UNKNOWN
    }

    /**
     * Decides whether a lease has objectively expired.
     *
     * The exact `expires` format of the router is not known, so only values
     * that are unambiguous are accepted: an explicit "expired" marker, or a
     * numeric timestamp that lies in the past. Anything else stays unknown
     * instead of being guessed.
     */
    private fun isLeaseExpired(raw: String?, nowEpochSeconds: Long): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        if (value.contains("expired", ignoreCase = true)) return true
        val numeric = value.toLongOrNull() ?: return false
        // A small number is a duration in seconds, not a timestamp: it does not
        // tell us anything about expiry.
        if (numeric < MINIMUM_TIMESTAMP_SECONDS) return false
        return numeric <= nowEpochSeconds
    }

    private const val MINIMUM_TIMESTAMP_SECONDS = 1_000_000_000L

    // ---- Normalisation ----

    private const val UNKNOWN_DEVICE_NAME = "Unknown Device"

    private fun normalizeMac(mac: String): String = mac.trim().lowercase()

    private fun normalizeHostname(hostname: String?): String {
        val value = hostname?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return UNKNOWN_DEVICE_NAME
        return value
    }

    private fun deduplicateLeases(leases: List<DhcpLease>): List<DhcpLease> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<DhcpLease>()
        for (lease in leases) {
            val mac = normalizeMac(lease.mac)
            // A lease without a MAC cannot be deduplicated, so it is kept as it is
            // instead of being dropped.
            if (mac.isNotEmpty() && !seen.add(mac)) continue
            result += lease
        }
        return result
    }

    // ---- Association index ----

    private class BandAssociation(
        val band: WifiBand,
        val association: WifiAssociation,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BandAssociation) return false
            return band == other.band && association == other.association
        }

        override fun hashCode(): Int = 31 * band.hashCode() + association.hashCode()
    }

    private class AssociationIndex {
        val byMac = mutableMapOf<String, MutableList<BandAssociation>>()
        val byIp = mutableMapOf<String, MutableList<BandAssociation>>()
        val byDevice = mutableMapOf<String, MutableList<BandAssociation>>()

        fun add(band: WifiBand, association: WifiAssociation) {
            val mac = normalizeMac(association.mac.orEmpty())
            val ip = association.ip?.trim().orEmpty()
            val entry = BandAssociation(band, association)
            if (mac.isNotEmpty()) byMac.getOrPut(mac) { mutableListOf() } += entry
            if (ip.isNotEmpty()) byIp.getOrPut(ip) { mutableListOf() } += entry
            val key = if (mac.isNotEmpty()) "mac:$mac" else if (ip.isNotEmpty()) "ip:$ip" else null
            if (key != null) byDevice.getOrPut(key) { mutableListOf() } += entry
        }

        /** MAC first, IP only when the MAC matches nothing at all. */
        fun match(mac: String, ip: String): List<BandAssociation> {
            if (mac.isNotEmpty()) {
                val byMacMatches = byMac[mac]
                if (byMacMatches != null && byMacMatches.isNotEmpty()) return byMacMatches
            }
            if (ip.isNotEmpty()) return byIp[ip].orEmpty()
            return emptyList()
        }
    }

    private fun buildAssociations(
        wifi24: List<WifiAssociation>,
        wifi5: List<WifiAssociation>,
    ): AssociationIndex {
        val index = AssociationIndex()
        for (association in wifi24) index.add(WifiBand.BAND_2_4_GHZ, association)
        for (association in wifi5) index.add(WifiBand.BAND_5_GHZ, association)
        return index
    }

    /** The 5 GHz signal wins when both bands report one. */
    private fun List<BandAssociation>.primarySignal(): DeviceSignal? {
        if (isEmpty()) return null
        val preferred = firstOrNull { it.band == WifiBand.BAND_5_GHZ } ?: first()
        val association = preferred.association
        val signal = DeviceSignal(
            rssiDbm = association.rssiDbm,
            txRateMbps = association.txRateMbps?.takeIf { it.isNotEmpty() },
            rxRateMbps = association.rxRateMbps?.takeIf { it.isNotEmpty() },
            ssid = association.ssid?.takeIf { it.isNotEmpty() },
        )
        val isEmpty = signal.rssiDbm == null && signal.txRateMbps == null &&
            signal.rxRateMbps == null && signal.ssid == null
        return if (isEmpty) null else signal
    }

    // ---- Ordering ----

    /**
     * Deterministic order: by name, then IP, then MAC.
     *
     * The placeholder name "Unknown Device" is not a real name, so it sorts
     * after the devices that have one. The order does not depend on the
     * presence state, so two identical polls always produce the same list.
     */
    private val DEVICE_ORDER = compareBy<RouterDevice> {
        if (it.name == UNKNOWN_DEVICE_NAME) 1 else 0
    }.thenBy { it.name.lowercase() }
        .thenBy { it.ip }
        .thenBy { it.mac }
}
