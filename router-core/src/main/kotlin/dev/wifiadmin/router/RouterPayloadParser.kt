package dev.wifiadmin.router

import org.json.JSONObject

/**
 * One DHCP lease as reported by cmd 223.
 *
 * The fields are kept exactly as the router sent them; interpretation happens
 * in [DeviceMerger].
 */
data class DhcpLease(
    val mac: String,
    val ip: String,
    val hostname: String?,
    val interfaceName: String?,
    val leaseExpiry: String?,
    val ipv6: String?,
)

/**
 * One Wi-Fi association as reported by cmd 224 (2.4 GHz) or cmd 225 (5 GHz).
 */
data class WifiAssociation(
    val mac: String?,
    val ip: String?,
    val rssiDbm: Int?,
    val txRateMbps: String?,
    val rxRateMbps: String?,
    val ssid: String?,
)

/**
 * Turns the raw router replies into the plain data the merger works on.
 */
internal object RouterPayloadParser {

    const val CMD_DHCP_LIST = 223
    const val CMD_WIFI_2_4 = 224
    const val CMD_WIFI_5 = 225

    private const val KEY_DHCP_LIST = "dhcp_list_info"
    private const val WIFI_KEY_2_4 = "wlan24g_wifi_info"
    private const val WIFI_KEY_5 = "wlan5g_wifi_info"

    /**
     * Whether a reply carries the device list at all.
     *
     * The three list commands each carry their own key. When none of them
     * is present, the router delivered no data: the snapshot must say so
     * instead of showing an empty network.
     */
    fun payloadAbsent(
        dhcpReply: JSONObject,
        wifi24Reply: JSONObject,
        wifi5Reply: JSONObject,
    ): Boolean =
        !dhcpReply.has(KEY_DHCP_LIST) &&
            !wifi24Reply.has(WIFI_KEY_2_4) &&
            !wifi5Reply.has(WIFI_KEY_5)

    fun parseDhcpLeases(reply: JSONObject): List<DhcpLease> {
        val array = reply.optJSONArray("dhcp_list_info") ?: return emptyList()
        val leases = mutableListOf<DhcpLease>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            leases += DhcpLease(
                mac = item.optString("mac").trim(),
                ip = item.optString("ip").trim(),
                hostname = item.optNullableString("hostname"),
                interfaceName = item.optNullableString("interface"),
                leaseExpiry = item.optNullableString("expires"),
                ipv6 = item.optNullableString("ipv6"),
            )
        }
        return leases
    }

    fun parseWifi24Associations(reply: JSONObject): List<WifiAssociation> =
        parseWifiAssociations(reply, WIFI_KEY_2_4)

    fun parseWifi5Associations(reply: JSONObject): List<WifiAssociation> =
        parseWifiAssociations(reply, WIFI_KEY_5)

    fun parseWifiAssociations(reply: JSONObject, key: String): List<WifiAssociation> {
        val array = reply.optJSONArray(key) ?: return emptyList()
        val associations = mutableListOf<WifiAssociation>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            associations += WifiAssociation(
                mac = item.optNullableString("mac"),
                ip = item.optNullableString("ip"),
                rssiDbm = item.optIntOrNull("rssi"),
                txRateMbps = item.optNullableString("txrate"),
                rxRateMbps = item.optNullableString("rxrate"),
                ssid = item.optNullableString("ssid"),
            )
        }
        return associations
    }

    /** Reads a value as a trimmed string, treating JSON null and "null" as absent. */
    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key).trim()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
        return value
    }

    /** Reads a number that the router may send as a JSON number or as a string. */
    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }
}
