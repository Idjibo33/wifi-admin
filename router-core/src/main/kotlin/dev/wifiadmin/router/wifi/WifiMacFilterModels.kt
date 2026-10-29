package dev.wifiadmin.router.wifi

/**
 * The Wi-Fi radio a MAC filter applies to.
 *
 * The `subcmd` values are the ones the router's own web UI uses:
 * `'1'` for the 2.4 GHz band and `'0'` for the 5 GHz band.
 *
 * The mapping was verified three independent ways against the router's
 * JavaScript:
 *
 * 1. `js/config/wifiList24g.js` writes with `subcmd: '1'` and reads its own
 *    band with `subcmd: '1'`; `js/config/wifiList5g.js` writes with
 *    `subcmd: '0'` and reads its own band with `subcmd: '0'`.
 * 2. Each file reads the *other* band with the opposite `subcmd`.
 * 3. `js/config/wirelessWpsSetting.js` reads the 2.4 GHz filter with
 *    `subcmd: 1` and `js/config/wireless5gWpsSetting.js` reads the 5 GHz
 *    filter with `subcmd: 0`.
 */
enum class WifiBandFilter(val subcmd: String) {
    /** 2.4 GHz radio. */
    BAND_2_4_GHZ("1"),

    /** 5 GHz radio. */
    BAND_5_GHZ("0"),
    ;

    companion object {
        fun fromSubcmd(subcmd: String): WifiBandFilter? =
            entries.firstOrNull { it.subcmd == subcmd }
    }
}

/**
 * The mode of the Wi-Fi MAC filter.
 *
 * These are the literal values the router's web UI exchanges. The French
 * labels in the router's own dictionary make the semantics unambiguous:
 *
 * - `close`  — « fermeture » : the filter is disabled, the list is ignored.
 * - `allow`  — « liste blanche » / « Autoriser la connexion » : only the MACs
 *   in the list may associate.
 * - `deny`   — « liste noire » / « bloquer la connexion » : the MACs in the
 *   list may not associate.
 */
enum class WifiMacMode(val wire: String) {
    CLOSE("close"),
    ALLOW("allow"),
    DENY("deny"),
    ;

    companion object {
        /**
         * Parses a mode as sent by the router.
         *
         * An unknown value is a [WifiMacFilterCompatibilityException]: the
         * firmware may know modes this client does not, and silently treating
         * them as "no filter" would be a dangerous default.
         */
        fun fromWire(value: String?): WifiMacMode? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}
