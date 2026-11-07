package dev.wifiadmin.app.data.speedtest

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo

/**
 * What the phone knows about its Wi-Fi link, when Android allows it.
 *
 * The **link rate** is the negotiated rate between the phone and the
 * access point. It is never the Internet rate, and the UI always labels
 * the two apart. Unknown values are null, never 0.
 */
data class WifiLinkInfo(
    val linkSpeedMbps: Int?,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val bandLabel: String?,
    val wifiStandard: String?,
)

fun frequencyBandLabel(frequencyMhz: Int?): String? = when (frequencyMhz) {
    null -> null
    in 2400..2500 -> "2,4 GHz"
    in 4900..5925 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    else -> null
}

fun wifiStandardLabel(value: Int): String? = when (value) {
    4 -> "Wi-Fi 5"
    5 -> "Wi-Fi 6"
    6 -> "Wi-Fi 6E"
    7 -> "Wi-Fi 7"
    else -> null
}

/** Reads the link from the active Wi-Fi network, without any permission. */
interface WifiLinkInfoProvider {
    fun current(): WifiLinkInfo?
}

/**
 * Reads [NetworkCapabilities.linkSpeedMbps] and, when available without a
 * location permission, [WifiInfo] frequency and RSSI. Any
 * [SecurityException] degrades to null fields instead of crashing.
 */
class AndroidWifiLinkInfoProvider(
    private val connectivity: ConnectivityManager,
) : WifiLinkInfoProvider {

    override fun current(): WifiLinkInfo? {
        val network: Network? = connectivity.activeNetwork ?: return null
        val caps: NetworkCapabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null

        val info = runCatching { caps.getTransportInfo() as? WifiInfo }.getOrNull()
        // Link speed: NetworkCapabilities downstream bandwidth (Kbps) or WifiInfo.
        val linkSpeed = runCatching {
            val kbps = caps.getLinkDownstreamBandwidthKbps()
            if (kbps > 0) kbps / 1000 else null
        }.getOrNull() ?: info?.let {
            @Suppress("DEPRECATION")
            it.linkSpeed.takeIf { speed -> speed > 0 }
        }
        val frequency = info?.frequency?.takeIf { it != null && it > 0 }
        val rssi = info?.rssi?.takeIf { it != null && it > -9999 }
        val standard = info?.let {
            runCatching { wifiStandardLabel(it.wifiStandard) }.getOrNull()
        }
        return WifiLinkInfo(
            linkSpeedMbps = linkSpeed,
            rssiDbm = rssi,
            frequencyMhz = frequency,
            bandLabel = frequencyBandLabel(frequency),
            wifiStandard = standard,
        )
    }
}
