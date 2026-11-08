package dev.wifiadmin.app.data.speedtest

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build

/** Where a link-speed value comes from. */
enum class LinkSpeedSource { WIFI_RX, WIFI_LEGACY, NETWORK_CAPABILITIES_ESTIMATE }

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
    val linkSpeedSource: LinkSpeedSource? = null,
    val isLinkSpeedEstimate: Boolean =
        linkSpeedSource == LinkSpeedSource.NETWORK_CAPABILITIES_ESTIMATE,
    val txLinkSpeedMbps: Int? = null,
)

// Band edges from AOSP ScanResult.java (BAND_*_FREQ_MHZ are @hide).
private const val BAND_24_START = 2412
private const val BAND_24_END = 2484
private const val BAND_5_START = 5160
private const val BAND_5_END = 5885
private const val BAND_6_START = 5955
private const val BAND_6_END = 7115
private const val BAND_6_CLASS_136 = 5935
private const val BAND_60_START = 58320
private const val BAND_60_END = 70200

fun is6GHzBand(frequencyMhz: Int?): Boolean =
    frequencyMhz != null && (frequencyMhz == BAND_6_CLASS_136 || frequencyMhz in BAND_6_START..BAND_6_END)

fun frequencyBandLabel(frequencyMhz: Int?): String? = when (frequencyMhz) {
    null -> null
    in BAND_24_START..BAND_24_END -> "2,4 GHz"
    in BAND_5_START..BAND_5_END -> "5 GHz"
    in BAND_6_START..BAND_6_END -> "6 GHz"
    BAND_6_CLASS_136 -> "6 GHz"
    in BAND_60_START..BAND_60_END -> "60 GHz (WiGig)"
    else -> null
}

/**
 * Totally public, JVM-testable: [value] is a ScanResult.WIFI_STANDARD_*
 * constant (compile-time inlined, no Android runtime).
 * Wi-Fi 6E is 11AX on the 6 GHz band, hence [frequencyMhz] discriminates.
 */
fun wifiStandardLabel(value: Int, frequencyMhz: Int? = null): String? = when (value) {
    ScanResult.WIFI_STANDARD_UNKNOWN -> null
    ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
    ScanResult.WIFI_STANDARD_11AX ->
        if (is6GHzBand(frequencyMhz)) "Wi-Fi 6E (802.11ax, 6 GHz)" else "Wi-Fi 6 (802.11ax)"
    ScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad, 60 GHz)"
    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
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

        var linkSpeed: Int? = null
        var source: LinkSpeedSource? = null
        var tx: Int? = null
        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rx = info.rxLinkSpeedMbps.takeIf { it != WifiInfo.LINK_SPEED_UNKNOWN && it > 0 }
            if (rx != null) {
                linkSpeed = rx
                source = LinkSpeedSource.WIFI_RX
            }
            tx = info.txLinkSpeedMbps.takeIf { it != WifiInfo.LINK_SPEED_UNKNOWN && it > 0 }
        }
        if (linkSpeed == null && info != null) {
            @Suppress("DEPRECATION")
            val legacy = info.linkSpeed.takeIf { it != WifiInfo.LINK_SPEED_UNKNOWN && it > 0 }
            if (legacy != null) {
                linkSpeed = legacy
                source = LinkSpeedSource.WIFI_LEGACY
            }
        }
        if (linkSpeed == null) {
            // Last resort: first-hop bandwidth ESTIMATE (Kbps), not
            // a negotiated PHY rate. 0 means unspecified ("@hide").
            val estimated = runCatching {
                caps.getLinkDownstreamBandwidthKbps().takeIf { it > 0 }?.let { it / 1000 }
            }.getOrNull()
            if (estimated != null) {
                linkSpeed = estimated
                source = LinkSpeedSource.NETWORK_CAPABILITIES_ESTIMATE
            }
        }

        val frequency = info?.frequency?.takeIf { it > 0 }
        // -127 is AOSP's INVALID_RSSI sentinel.
        val rssi = info?.rssi?.takeIf { it > -127 }
        val standard = if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wifiStandardLabel(info.wifiStandard, frequency) }.getOrNull()
        } else {
            null
        }
        return WifiLinkInfo(
            linkSpeedMbps = linkSpeed,
            rssiDbm = rssi,
            frequencyMhz = frequency,
            bandLabel = frequencyBandLabel(frequency),
            wifiStandard = standard,
            linkSpeedSource = source,
            isLinkSpeedEstimate = source == LinkSpeedSource.NETWORK_CAPABILITIES_ESTIMATE,
            txLinkSpeedMbps = tx,
        )
    }
}
