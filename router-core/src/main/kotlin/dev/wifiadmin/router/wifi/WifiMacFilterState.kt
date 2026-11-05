package dev.wifiadmin.router.wifi

/**
 * One entry of the Wi-Fi MAC filter list.
 */
data class WifiMacEntry(
    /** Normalised address: `AA:BB:CC:DD:EE:FF`, upper case, colon separated. */
    val mac: String,
    /** Free-form remark, at most 50 characters as the router expects. */
    val remark: String = "",
)

/**
 * The complete state of the Wi-Fi MAC filter for one band.
 *
 * This is the only thing that has to be saved and restored: it fully
 * describes the filter for [band].
 *
 * @property mode the filter mode. It is **null** when the router delivered
 *   no payload: an absent collection is not an empty collection, and
 *   reporting "disabled" would claim something the router never said.
 * @property payloadPresent whether the router actually delivered the
 *   filter's payload.
 */
data class WifiMacFilterState(
    val band: WifiBandFilter,
    val mode: WifiMacMode?,
    val entries: List<WifiMacEntry> = emptyList(),
    val payloadPresent: Boolean = true,
) {
    /**
     * True when this state would lock every wireless device out of the band.
     *
     * `ALLOW` with an empty list means "only the listed MACs may associate",
     * and an empty list excludes everybody. The client refuses such a write.
     */
    val locksOutEveryDevice: Boolean
        get() = mode == WifiMacMode.ALLOW && entries.isEmpty()

    /**
     * Returns a copy with [entries] added or replaced.
     *
     * Replacing an existing entry (same MAC) keeps its position, so the order
     * the router displays — and therefore the priority — is preserved.
     */
    fun withEntry(entry: WifiMacEntry): WifiMacFilterState {
        val index = entries.indexOfFirst { it.mac.equals(entry.mac, ignoreCase = true) }
        return if (index >= 0) {
            copy(entries = entries.toMutableList().also { it[index] = entry })
        } else {
            copy(entries = entries + entry)
        }
    }

    /** Returns a copy without the entry whose MAC is [mac]. */
    fun withoutMac(mac: String): WifiMacFilterState =
        copy(entries = entries.filterNot { it.mac.equals(mac, ignoreCase = true) })

    companion object {
        /** Normalises a MAC address to `AA:BB:CC:DD:EE:FF`. */
        fun normalizeMac(raw: String): String {
            val hex = raw.trim().filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            return hex.uppercase().chunked(2).joinToString(":")
        }

        /**
         * Validates a MAC address the way the router's web UI does.
         *
         * The router accepts `AA:BB:CC:DD:EE:FF` and `AA-BB-CC-DD-EE-FF`, and
         * rejects the broadcast address, the all-zero address and any
         * multicast address (an address whose first octet has its least
         * significant bit set).
         */
        fun validateMac(raw: String): String {
            val normalized = normalizeMac(raw)
            if (!normalized.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
                throw WifiMacValidationException.InvalidMac(raw)
            }
            if (normalized == "FF:FF:FF:FF:FF:FF" ||
                normalized == "00:00:00:00:00:00" ||
                normalized == "01:00:00:00:00:00"
            ) {
                throw WifiMacValidationException.ForbiddenMac(normalized)
            }
            val firstOctet = normalized.substringBefore(':').toInt(16)
            if (firstOctet and 0x01 == 1) {
                throw WifiMacValidationException.ForbiddenMac(normalized)
            }
            return normalized
        }
    }
}
