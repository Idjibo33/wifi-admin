package dev.wifiadmin.poc

import dev.wifiadmin.router.wifi.WifiBandFilter
import dev.wifiadmin.router.wifi.WifiMacFilterState

/**
 * Renders an **anonymised** report of the router's Wi-Fi MAC filter.
 *
 * No MAC address is ever printed. Only the mode, the number of entries and
 * the observed structure are reported.
 */
object WifiFilterRenderer {

    /**
     * Renders the state of one band.
     *
     * @param state the state read from the router.
     * @param structure the JSON keys that were actually observed.
     */
    fun render(
        band: WifiBandFilter,
        state: WifiMacFilterState?,
        structure: List<String>,
        error: String? = null
    ): String = buildString {
        appendLine("Bande ${band.label()} :")
        if (error != null) {
            appendLine("  ERREUR : $error")
            return@buildString
        }
        if (state == null) {
            appendLine("  indisponible")
            return@buildString
        }

        appendLine("  mode               ${state.mode.wire}")
        appendLine("  entrées            ${state.entries.size}")
        appendLine("  structure observée ${structure.joinToString(", ").ifEmpty { "(aucune)" }}")

        // The shape of the entries is reported without their content.
        val withRemark = state.entries.count { it.remark.isNotEmpty() }
        appendLine("  remarque non vide  $withRemark / ${state.entries.size}")
        appendLine("  MAC toutes normalisées : ${state.entries.all { it.mac.matches(MAC_PATTERN) }}")
    }

    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    private fun WifiBandFilter.label(): String = when (this) {
        WifiBandFilter.BAND_2_4_GHZ -> "2.4 GHz"
        WifiBandFilter.BAND_5_GHZ -> "5 GHz"
    }
}
