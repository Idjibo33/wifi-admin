package dev.wifiadmin.poc

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand

/**
 * Renders an **anonymised diagnostic** of a device snapshot.
 *
 * Nothing that identifies a device is printed: no MAC address, no IP address,
 * no hostname, no SSID, no remark. Only counts, state distributions and the
 * *shape* of the fields are reported.
 *
 * The shape of a value is described by its type and a coarse pattern, never by
 * its content: a lease expiry is reported as "a numeric string", not as its
 * digits.
 */
object DiagnosticRenderer {

    fun render(
        devices: List<RouterDevice>,
        commands: List<Int> = emptyList()
    ): String = buildString {
        appendLine("=== Diagnostic anonymisé du routeur ===")
        appendLine()

        appendLine("Commandes envoyées (lecture seule) : ${commands.joinToString(", ")}")
        appendLine()

        appendLine("Appareils détectés : ${devices.size}")
        appendLine()

        appendLine("Répartition des états :")
        DevicePresence.entries.forEach { presence ->
            val count = devices.count { it.presence == presence }
            appendLine("  ${presence.name.padEnd(8)} $count")
        }
        appendLine()

        appendLine("Appareils par bande Wi-Fi :")
        appendLine("  2.4 GHz   ${devices.count { WifiBand.BAND_2_4_GHZ in it.bands }}")
        appendLine("  5 GHz     ${devices.count { WifiBand.BAND_5_GHZ in it.bands }}")
        appendLine("  sans bande ${devices.count { it.bands.isEmpty() }}")
        appendLine()

        appendLine("Valeurs de `interface` (catégorisées) :")
        devices.groupBy { it.interfaceName.trim().lowercase() }
            .toSortedMap()
            .forEach { (value, group) ->
                val kind = interfaceKind(value)
                appendLine("  ${kind.padEnd(18)} ${group.size}   (valeurs distinctes : ${group.map { it.interfaceName.trim() }.distinct().size})")
            }
        appendLine()

        appendLine("Format du champ `expires` (forme uniquement, jamais la valeur) :")
        devices.groupBy { describeExpiry(it.leaseExpiry) }
            .toSortedMap()
            .forEach { (shape, group) ->
                appendLine("  ${shape.padEnd(34)} ${group.size}")
            }
        appendLine()

        appendLine("Champs `hostname` :")
        appendLine("  renseignés              ${devices.count { it.name != "Unknown Device" }}")
        appendLine("  non renseignés / \"NULL\" ${devices.count { it.name == "Unknown Device" }}")
        appendLine()

        appendLine("Présence du signal (device) :")
        appendLine("  avec RSSI               ${devices.count { it.signal?.rssiDbm != null }}")
        appendLine("  sans RSSI               ${devices.count { it.signal?.rssiDbm == null }}")
        appendLine("  SSID présent            ${devices.count { !it.signal?.ssid.isNullOrEmpty() }}")
        appendLine("  bandes multiples        ${devices.count { it.bands.size > 1 }}")
    }.trimEnd()

    /**
     * Describes the *kind* of an `interface` value without printing it.
     *
     * The values themselves are operator-specific and could be identifying, so
     * only the category is reported.
     */
    private fun interfaceKind(value: String): String = when {
        value.isEmpty() -> "inconnue"
        value.contains("wlan") || value.contains("wifi") || value.contains("wi-fi") ||
            value.contains("wireless") -> "sans fil"
        value.contains("lan") || value.contains("eth") -> "filaire"
        else -> "autre"
    }

    /**
     * Describes the shape of an `expires` value.
     *
     * The value is never echoed: only its type and a coarse pattern are
     * reported, which is enough to decide whether the parser can rely on it.
     */
    fun describeExpiry(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "absent ou vide"

        return when {
            // A plain integer: seconds remaining, or an epoch timestamp.
            value.all { it.isDigit() } -> {
                val magnitude = when {
                    value.toLongOrNull()?.let { it >= 1_000_000_000L } == true -> "entier (timestamp epoch ?)"
                    value.length <= 2 -> "entier court (durée ?)"
                    else -> "entier (durée ?)"
                }
                "$magnitude, ${value.length} chiffres"
            }
            // A duration such as "24h" or "7d".
            value.any { it.isLetter() } && value.dropLastWhile { it.isLetter() }.all { it.isDigit() } ->
                "durée (chiffres + suffixe), ${value.length} caractères"
            // A date-time.
            value.contains('-') && value.contains(':') -> "date-heure"
            value.any { it.isLetter() } -> "texte non numérique"
            else -> "autre forme non numérique"
        }
    }
}
