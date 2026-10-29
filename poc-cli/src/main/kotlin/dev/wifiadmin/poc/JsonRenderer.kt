package dev.wifiadmin.poc

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * Builds the machine-readable snapshot emitted by `--format json`.
 *
 * The schema is intentionally free of credentials, challenge tokens and session ids.
 * Only router-reported device data is exported.
 *
 * The output is written with an ordered writer rather than `org.json.JSONObject`:
 * `JSONObject` is backed by a `HashMap`, so its key order is not stable and the
 * emitted JSON would not match the documented schema. Consumers of the JSON
 * must not depend on key order (JSON objects are unordered by definition), but a
 * stable output keeps diffs readable and the documentation accurate.
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "deviceCount": 2,
 *   "devices": [
 *     {
 *       "name": "pixel-6",
 *       "ip": "192.168.1.192",
 *       "mac": "aa:bb:cc:dd:ee:ff",
 *       "bands": ["5GHZ"],
 *       "signal": { "rssiDbm": -60, "txRateMbps": "866", "rxRateMbps": "866", "ssid": "wifi" },
 *       "interface": "wlan",
 *       "leaseExpiry": "3600",
 *       "ipv6": "",
 *       "presence": "ACTIVE"
 *     }
 *   ]
 * }
 * ```
 */
object JsonRenderer {
    const val SCHEMA_VERSION = 1

    fun render(devices: List<RouterDevice>): String {
        val deviceJson = devices.joinToString(",\n") { device -> deviceJson(device) }
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": $SCHEMA_VERSION,")
            appendLine("  \"deviceCount\": ${devices.size},")
            appendLine("  \"devices\": [")
            appendLine(deviceJson)
            appendLine("  ]")
            append("}")
        }
    }

    private fun deviceJson(device: RouterDevice): String = buildString {
        appendLine("    {")
        appendLine("      \"name\": ${quote(device.name)},")
        appendLine("      \"ip\": ${quote(device.ip)},")
        appendLine("      \"mac\": ${quote(device.mac)},")
        appendLine("      \"bands\": ${bandsJson(device.bands)},")
        appendLine("      \"signal\": ${device.signal?.let { signalJson(it) } ?: "null"},")
        appendLine("      \"interface\": ${quote(device.interfaceName)},")
        appendLine("      \"leaseExpiry\": ${device.leaseExpiry?.let { quote(it) } ?: "null"},")
        appendLine("      \"ipv6\": ${device.ipv6?.let { quote(it) } ?: "null"},")
        appendLine("      \"presence\": ${quote(device.presence.name)}")
        append("    }")
    }

    private fun signalJson(signal: DeviceSignal): String = buildString {
        append("{")
        append("\"rssiDbm\": ${signal.rssiDbm ?: "null"}, ")
        append("\"txRateMbps\": ${signal.txRateMbps?.let { quote(it) } ?: "null"}, ")
        append("\"rxRateMbps\": ${signal.rxRateMbps?.let { quote(it) } ?: "null"}, ")
        append("\"ssid\": ${signal.ssid?.let { quote(it) } ?: "null"}")
        append("}")
    }

    private fun bandsJson(bands: Set<WifiBand>): String {
        val names = bands.sortedBy { it.name }.joinToString(", ") { quote(it.wireName()) }
        return "[$names]"
    }

    /** Quotes and escapes a JSON string. */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else ->
                    if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun WifiBand.wireName(): String = when (this) {
        WifiBand.BAND_2_4_GHZ -> "2_4GHZ"
        WifiBand.BAND_5_GHZ -> "5GHZ"
    }
}
