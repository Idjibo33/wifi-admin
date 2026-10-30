package dev.wifiadmin.poc

import dev.wifiadmin.router.wifi.WifiBandFilter
import dev.wifiadmin.router.wifi.WifiMacFilterState

/**
 * Why a Wi-Fi filter band could not be read.
 *
 * A closed type on purpose: the router's `message` is free text and must
 * never reach the report, where it could carry a secret or forge a line.
 */
enum class WifiFilterError {
    /** The router could not be reached. */
    NETWORK,

    /** The router refused the command. */
    REFUSED,

    /** The reply could not be understood. */
    PROTOCOL,

    /** The Wi-Fi is not ready. */
    NOT_READY,

    /** The WPS state prevents the read. */
    WPS,

    /** Anything else. */
    OTHER,
}

/**
 * Renders an **anonymised** report of the router's Wi-Fi MAC filter.
 *
 * No MAC address is ever printed. Only the mode, the number of entries and
 * the observed structure are reported.
 *
 * The key names are sanitised: they are chosen by the router, and a name
 * may be named after a secret or contain a line break.
 */
object WifiFilterRenderer {

    /**
     * Renders the state of one band.
     *
     * @param state the state read from the router.
     * @param structure the JSON keys that were actually observed.
     * @param error why the band could not be read, if it could not.
     */
    fun render(
        band: WifiBandFilter,
        state: WifiMacFilterState?,
        structure: List<String>,
        error: WifiFilterError? = null
    ): String = buildString {
        appendLine("Bande ${band.label()} :")
        if (error != null) {
            appendLine("  ERREUR : ${describeError(error)}")
            return@buildString
        }
        if (state == null) {
            appendLine("  indisponible")
            return@buildString
        }

        appendLine("  mode               ${describeMode(state)}")
        appendLine("  entrées            ${state.entries.size}")
        appendLine(
            "  structure observée " +
                SafeKeyNames.sanitizeAll(structure).joinToString(", ").ifEmpty { "(aucune)" }
        )

        // The shape of the entries is reported without their content.
        val withRemark = state.entries.count { it.remark.isNotEmpty() }
        appendLine("  remarque non vide  $withRemark / ${state.entries.size}")
        appendLine("  MAC toutes normalisées : ${state.entries.all { it.mac.matches(MAC_PATTERN) }}")
    }

    /**
     * The filter mode, or the fact that it is unknown.
     *
     * The router may deliver no payload at all; the mode is then unknown,
     * not "disabled".
     */
    private fun describeMode(state: WifiMacFilterState): String {
        val mode = state.mode
        return if (mode == null) "inconnu (le routeur n'a rien renvoyé)" else mode.wire
    }

    /**
     * A fixed sentence per error.
     *
     * The exception's own message is never reproduced: it may embed a
     * string sent by the router.
     */
    private fun describeError(error: WifiFilterError): String = when (error) {
        WifiFilterError.NETWORK -> "le routeur est injoignable"
        WifiFilterError.REFUSED -> "le routeur a refusé la commande"
        WifiFilterError.PROTOCOL -> "réponse inutilisable"
        WifiFilterError.NOT_READY -> "le Wi-Fi n'est pas initialisé"
        WifiFilterError.WPS -> "le WPS empêche cette lecture"
        WifiFilterError.OTHER -> "lecture impossible"
    }

    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    private fun WifiBandFilter.label(): String = when (this) {
        WifiBandFilter.BAND_2_4_GHZ -> "2.4 GHz"
        WifiBandFilter.BAND_5_GHZ -> "5 GHz"
    }
}
