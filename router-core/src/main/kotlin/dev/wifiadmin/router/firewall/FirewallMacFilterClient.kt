package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.ZltRouterClient

/**
 * **Read-only** client for the router's firewall MAC filter.
 *
 * This is the firewall filter (`cmd 23`), not the Wi-Fi association filter
 * (`cmd 278`). The two are disjoint mechanisms:
 *
 * - `cmd 23` filters **traffic** in the router's firewall;
 * - `cmd 278` controls whether a device may **associate** with a radio.
 *
 * ## What this client can send
 *
 * Only two commands, both with `method: "GET"`:
 *
 * | Command | Purpose |
 * |---|---|
 * | `23` | the MAC rule list |
 * | `30` | the default policy, one entry per protocol |
 *
 * It never sends `cmd 20` (apply), never writes `cmd 23` or `cmd 30`, and
 * never asks for the write token `cmd 233`. There is no generic
 * `query(cmd, method)` entry point: the commands are constants and the
 * session's read gate rejects anything outside its allowlist.
 *
 * The session is the one already opened by the [ZltRouterClient] passed to
 * the constructor: no second login, no second copy of the authentication
 * rules.
 *
 * ## Why this is not a feature
 *
 * **The capability is `UNAVAILABLE` on this firmware.**
 *
 * The router's **own web interface** was observed on 30/10/2026, on the
 * path *Pare-Feu → Règles de filtrage → Filtrage MAC*. It received exactly
 * the same empty reply as this client:
 *
 * ```json
 * {"success": true, "cmd": 23, "message": ""}
 * ```
 *
 * HTTP 200, no `datas`. The command **runs** but delivers **nothing** — the
 * handler is not wired on this build, or it is disabled for this account.
 * Either way, two independent clients get the same answer, so the request
 * is not at fault.
 *
 * This module is therefore **kept as an analysis record**, for a future
 * firmware, another account or another operator. It is **not** called by the
 * Android application, and it exposes no write path.
 *
 * See `docs/FIREWALL_ANALYSIS.md` §13ter for the full closure record.
 */
class FirewallMacFilterClient(
    private val client: ZltRouterClient,
) {

    /**
     * Reads the firewall MAC rule list.
     *
     * @param getfun adds the `getfun: "true"` flag that the router's own web
     *   UI is documented to send with this command.
     *
     *   The flag is sent as a **string**, like every other flag this
     *   firmware knows (`acceptAll: "true"`, `isAutoUpgrade: "0"`).
     *
     *   **This track is closed.** The official interface sends the flag and
     *   receives the same empty reply as we do, so the flag is not the
     *   missing condition. The variant is kept because it is correct and
     *   tested, and because another firmware might accept it.
     *
     * The rules keep the router's order, which is the priority.
     *
     * The result distinguishes four situations, because "no rule" and "no
     * answer" are not the same thing:
     *
     * - [FirewallReadOutcome.Data] — the rules were read. The list may be
     *   empty: that is a confirmed state.
     * - [FirewallReadOutcome.Invalid] — the router accepted the command but
     *   delivered no usable payload.
     * - [FirewallReadOutcome.Unavailable] — the firmware refuses the command.
     * - [FirewallReadOutcome.Indeterminate] — no conclusion is possible.
     *
     * @throws dev.wifiadmin.router.RouterAuthenticationException when the
     *   credentials are refused or the session cannot be renewed.
     * @throws dev.wifiadmin.router.RouterCooldownException when the router is
     *   refusing logins.
     * @throws dev.wifiadmin.router.RouterNetworkException when the router
     *   cannot be reached.
     * @throws dev.wifiadmin.router.RouterProtocolException when the reply is
     *   not usable.
     */
    fun readRules(getfun: Boolean = false): FirewallReadOutcome<FirewallMacRules> {
        // The flag is sent as a **string**, like every other flag this
        // firmware knows (`acceptAll: "true"`, `isAutoUpgrade: "0"`). A JSON
        // boolean has never been seen by it, and a strict parser would
        // simply ignore it.
        val extra = if (getfun) mapOf("getfun" to "true") else emptyMap()
        return FirewallMacFilterParser.parseRules(client.readOnly(CMD_MAC_FILTER, extra))
    }

    /**
     * Reads the default policy, one entry per protocol.
     *
     * This is the state that decides whether the rules act as a black list
     * or a white list.
     *
     * @param command which command to use: `30` (`MAC_DEFAULT_FILTER`, the
     *   default) or `28` (`DEFAULT_FILTER`, the older single-entry
     *   variant). `28` is a **variant**: its structure has never been
     *   observed either.
     */
    fun readDefaults(command: Int = CMD_MAC_DEFAULT_FILTER): FirewallReadOutcome<FirewallDefaults> {
        require(command == CMD_MAC_DEFAULT_FILTER || command == CMD_DEFAULT_FILTER) {
            "Only commands 28 and 30 read the default policy"
        }
        return FirewallMacFilterParser.parseDefaults(client.readOnly(command))
    }

    /**
     * Reads both the rules and the default policy.
     *
     * The two reads are separate commands: the snapshot can be inconsistent
     * if the configuration changes in the web UI between them. That is
     * acceptable for a diagnostic and **not** acceptable for a future write,
     * which must re-read and compare before touching anything.
     *
     * One failing read does **not** cancel the other: a diagnostic that
     * loses the valid half because the second command failed is useless.
     */
    fun readState(): FirewallMacState = FirewallMacState(
        rules = readRules(),
        defaults = readDefaults(),
    )

    companion object {
        /** `RequestCmd.MAC_FILTER` in the router's own command table. */
        const val CMD_MAC_FILTER = 23

        /** `RequestCmd.MAC_DEFAULT_FILTER` in the router's command table. */
        const val CMD_MAC_DEFAULT_FILTER = 30

        /**
         * `RequestCmd.DEFAULT_FILTER`: the older, single-entry variant of
         * the default policy.
         */
        const val CMD_DEFAULT_FILTER = 28
    }
}
