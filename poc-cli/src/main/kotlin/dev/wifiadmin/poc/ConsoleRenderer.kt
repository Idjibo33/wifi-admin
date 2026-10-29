import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * Renders the device snapshot in a stable, human-readable form.
 *
 * The console output intentionally omits credentials, challenge tokens and session ids:
 * only data returned by the router for the devices is printed.
 */
object ConsoleRenderer {
    fun render(devices: List<RouterDevice>): String {
        if (devices.isEmpty()) {
            return "No devices reported by the router."
        }

        val sorted = devices.sortedWith(
            compareBy<RouterDevice> { it.name.lowercase() }
                .thenBy { it.ip }
                .thenBy { it.mac }
        )

        val width = sorted.maxOf { it.name.length }.coerceAtLeast(4)

        return buildString {
            appendLine("Devices reported by the router (${sorted.size}):")
            appendLine()
            sorted.forEach { device ->
                val bands = device.bands
                    .map { bandLabel(it) }
                    .joinToString(", ")
                    .ifEmpty { "unknown band" }
                val signal = signalLabel(device)
                val ip = device.ip.ifEmpty { "-" }
                val mac = device.mac.ifEmpty { "-" }
                val name = device.name.ifEmpty { "Unknown Device" }

                appendLine("${name.padEnd(width)}  $ip  $mac  $bands  $signal  ${device.presence.label()}")
            }
        }.trimEnd()
    }

    fun bandLabel(band: WifiBand): String = when (band) {
        WifiBand.BAND_2_4_GHZ -> "2.4 GHz"
        WifiBand.BAND_5_GHZ -> "5 GHz"
    }

    fun signalLabel(device: RouterDevice): String {
        val signal = device.signal ?: return "signal: -"
        val rssi = signal.rssiDbm?.let { "$it dBm" } ?: "-"
        val tx = signal.txRateMbps?.takeIf { it.isNotEmpty() } ?: "-"
        val rx = signal.rxRateMbps?.takeIf { it.isNotEmpty() } ?: "-"
        return "signal: $rssi (tx $tx / rx $rx)"
    }

    private fun DevicePresence.label(): String = name.lowercase()
}
