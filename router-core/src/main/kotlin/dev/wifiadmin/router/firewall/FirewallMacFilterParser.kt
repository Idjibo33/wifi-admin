package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.RouterReplyObservation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the replies of the firewall MAC filter read commands.
 *
 * The parser is deliberately **lenient**: it reports what it could not read
 * instead of throwing, so a single unexpected field cannot hide the rest of
 * the router's answer.
 *
 * The real shape of these replies **has** now been observed (30/10/2026):
 * `{"success": true, "cmd": 23, "message": ""}` with **no** `datas` — and
 * the router's own web interface receives the same. The parser therefore
 * reports `RESPONSE_INVALID`, never an empty rule list.
 *
 * It is `internal`: no caller outside this module can build a rule from an
 * arbitrary JSON object.
 */
internal object FirewallMacFilterParser {

    private const val KEY_DATAS = "datas"

    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val TRUE_VALUES = setOf("true", "1", "yes", "on")
    private val FALSE_VALUES = setOf("false", "0", "no", "off")

    /** Parses a `GET 23` reply. */
    fun parseRules(reply: JSONObject): FirewallReadOutcome<FirewallMacRules> {
        val observation = RouterReplyObservation.of(reply)
        val observedKeys = observation.topLevelKeys
        val anomalies = mutableListOf<FirewallAnomaly>()

        if (!reply.has(KEY_DATAS)) {
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.DATAS_MISSING,
                observation = observation,
            )
        }

        val datas = reply.opt(KEY_DATAS)
        if (datas == null || datas == JSONObject.NULL) {
            // An explicit null is **not** an empty list: it means the router
            // delivered no payload at all. Reporting it as "0 rules" would
            // tell the user the firewall has no filter.
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.DATAS_NULL,
                observation = observation,
            )
        }
        if (datas !is JSONArray) {
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.DATAS_NOT_ARRAY,
                observation = observation,
            )
        }

        val rules = (0 until datas.length()).mapNotNull { index ->
            val element = datas.opt(index)
            if (element !is JSONObject) {
                anomalies += FirewallAnomaly(
                    kind = FirewallAnomalyKind.RULE_NOT_OBJECT,
                    ruleIndex = index,
                )
                return@mapNotNull null
            }
            parseRule(index, element, anomalies)
        }

        if (rules.isEmpty() && anomalies.any { it.kind == FirewallAnomalyKind.RULE_NOT_OBJECT }) {
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.ENTRIES_NOT_OBJECTS,
                observation = observation,
            )
        }

        return FirewallReadOutcome.Data(
            value = FirewallMacRules(rules, observedKeys, anomalies),
            observation = observation,
        )
    }

    /** Parses a `GET 30` reply. */
    fun parseDefaults(reply: JSONObject): FirewallReadOutcome<FirewallDefaults> {
        val observation = RouterReplyObservation.of(reply)
        val observedKeys = observation.topLevelKeys
        val anomalies = mutableListOf<FirewallAnomaly>()

        val datas = reply.opt(KEY_DATAS)
        if (datas == null || datas == JSONObject.NULL) {
            val reason = if (!reply.has(KEY_DATAS)) {
                FirewallInvalidReason.DATAS_MISSING
            } else {
                FirewallInvalidReason.DATAS_NULL
            }
            return FirewallReadOutcome.Invalid(reason, observation)
        }
        if (datas !is JSONArray) {
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.DATAS_NOT_ARRAY,
                observation = observation,
            )
        }

        val entries = (0 until datas.length()).mapNotNull { index ->
            val element = datas.opt(index)
            if (element !is JSONObject) {
                anomalies += FirewallAnomaly(
                    kind = FirewallAnomalyKind.RULE_NOT_OBJECT,
                    ruleIndex = index,
                )
                return@mapNotNull null
            }
            parseDefaultRule(index, element, anomalies)
        }

        // Prefer the declared protocol; fall back to the documented order
        // (IPv4 first, IPv6 second) only when `ippro` is unusable.
        val ipv4 = entries.firstOrNull { it.ipProtocol == FirewallIpProtocol.IPV4 }
            ?: entries.getOrNull(0)?.takeIf { it.ipProtocol == FirewallIpProtocol.UNRECOGNIZED }
        val ipv6 = entries.firstOrNull { it.ipProtocol == FirewallIpProtocol.IPV6 }
            ?: entries.getOrNull(1)?.takeIf { it.ipProtocol == FirewallIpProtocol.UNRECOGNIZED }

        if (ipv4 == null && ipv6 == null) {
            return FirewallReadOutcome.Invalid(
                reason = FirewallInvalidReason.ENTRIES_NOT_OBJECTS,
                observation = observation,
            )
        }

        return FirewallReadOutcome.Data(
            value = FirewallDefaults(ipv4, ipv6, observedKeys, anomalies),
            observation = observation,
        )
    }

    private fun parseRule(
        index: Int,
        element: JSONObject,
        anomalies: MutableList<FirewallAnomaly>,
    ): FirewallMacRule? {
        val observedFields = element.keys().asSequence().toSet()

        val rawMac = element.optString("mac", "").trim()
        if (rawMac.isEmpty()) {
            anomalies += FirewallAnomaly(FirewallAnomalyKind.MISSING_MAC, index)
            return null
        }
        val mac = normalizeMac(rawMac)
        val macIsValid = mac.matches(MAC_PATTERN)
        if (!macIsValid) {
            // The raw value is kept: the read must not "repair" what the
            // router actually stores.
            anomalies += FirewallAnomaly(FirewallAnomalyKind.INVALID_MAC, index)
        }

        val enabled = parseBoolean(element.opt("enableRule")).also {
            if (it == null) {
                anomalies += FirewallAnomaly(FirewallAnomalyKind.UNREADABLE_ENABLE_RULE, index)
            }
        }

        val enableLink = parseBoolean(element.opt("enableLink")).also {
            if (it == null) {
                anomalies += FirewallAnomaly(FirewallAnomalyKind.UNREADABLE_ENABLE_LINK, index)
            }
        }

        val ipProtocolRaw = element.optString("ippro", "").trim()
        val ipProtocol = FirewallIpProtocol.fromWire(ipProtocolRaw)
        if (ipProtocol == FirewallIpProtocol.UNRECOGNIZED) {
            anomalies += FirewallAnomaly(FirewallAnomalyKind.UNKNOWN_IP_PROTOCOL, index)
        }

        return FirewallMacRule(
            mac = mac,
            macIsValid = macIsValid,
            enabled = enabled,
            verdict = enableLink?.let(FirewallMacVerdict::fromEnableLink),
            ipProtocol = ipProtocol,
            ipProtocolRaw = ipProtocolRaw,
            remark = element.optString("remark", ""),
            observedFields = observedFields,
        )
    }

    private fun parseDefaultRule(
        index: Int,
        element: JSONObject,
        anomalies: MutableList<FirewallAnomaly>,
    ): FirewallDefaultRule {
        val enabled = parseBoolean(element.opt("enableRule"))
        if (enabled == null) {
            anomalies += FirewallAnomaly(FirewallAnomalyKind.UNREADABLE_ENABLE_RULE, index)
        }

        val acceptAll = parseBoolean(element.opt("acceptAll"))
        if (acceptAll == null) {
            anomalies += FirewallAnomaly(FirewallAnomalyKind.UNREADABLE_ACCEPT_ALL, index)
        }

        val ipProtocolRaw = element.optString("ippro", "").trim()
        val ipProtocol = FirewallIpProtocol.fromWire(ipProtocolRaw)
        if (ipProtocol == FirewallIpProtocol.UNRECOGNIZED) {
            anomalies += FirewallAnomaly(FirewallAnomalyKind.UNKNOWN_IP_PROTOCOL, index)
        }

        return FirewallDefaultRule(
            enabled = enabled,
            acceptAll = acceptAll,
            ipProtocol = ipProtocol,
            ipProtocolRaw = ipProtocolRaw,
            observedFields = element.keys().asSequence().toSet(),
        )
    }

    /**
     * Reads a boolean the way a firmware may send it: a JSON boolean, a
     * number, or one of the strings `"true"` / `"false"` / `"1"` / `"0"`.
     *
     * Anything else is `null`, never a default.
     */
    private fun parseBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toInt()) {
            1 -> true
            0 -> false
            else -> null
        }
        is String -> when (value.trim().lowercase()) {
            in TRUE_VALUES -> true
            in FALSE_VALUES -> false
            else -> null
        }
        else -> null
    }

    /**
     * Normalises `AA-BB-CC-DD-EE-FF`, `aabbccddeeff` and
     * `aa:bb:cc:dd:ee:ff` to `AA:BB:CC:DD:EE:FF`.
     *
     * A value that does not contain exactly twelve hexadecimal digits is
     * returned unchanged, so the caller can report it as invalid instead of
     * silently producing a plausible-looking address.
     */
    fun normalizeMac(raw: String): String {
        val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.length != 12) return raw.trim()
        return hex.uppercase().chunked(2).joinToString(":")
    }
}
