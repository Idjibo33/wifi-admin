package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.RouterReplyObservation

/**
 * The IP protocol a firewall MAC rule targets.
 *
 * The router's web UI writes `"IPV4"` and `"IPV6"`. The value `"IPV4V6"` is
 * mentioned by the analysis but has never been observed, so it is accepted
 * without being treated as proven. Any other value is kept as
 * [UNRECOGNIZED] rather than guessed: a read must never turn an unknown
 * protocol into `IPV4`.
 */
enum class FirewallIpProtocol(val wire: String) {
    IPV4("IPV4"),
    IPV6("IPV6"),
    IPV4V6("IPV4V6"),
    UNRECOGNIZED("");

    companion object {
        fun fromWire(raw: String): FirewallIpProtocol {
            val value = raw.trim().uppercase()
            return entries.firstOrNull { it != UNRECOGNIZED && it.wire == value }
                ?: UNRECOGNIZED
        }
    }
}

/**
 * What a rule does to the MAC addresses it matches.
 *
 * `enableLink` is `false` for a black list and `true` for a white list.
 *
 * > This mapping is a **deduction** from the router's web UI, which sets
 * > `enableLink = !acceptAll` (`macFilter.js`, transcribed in
 * > `docs/MAC_FILTER_ANALYSIS.md` §5.3). It has never been observed on the
 * > real firmware, which is why [FirewallMacRule.verdict] is nullable.
 */
enum class FirewallMacVerdict(val enableLink: Boolean) {
    BLACKLIST(false),
    WHITELIST(true);

    companion object {
        fun fromEnableLink(enableLink: Boolean): FirewallMacVerdict =
            if (enableLink) WHITELIST else BLACKLIST
    }
}

/**
 * One rule of the firewall MAC filter (`cmd 23`).
 *
 * Every field is nullable or carries a raw value on purpose: a read must
 * report what the router actually returned, including a field this client
 * does not understand. It must never invent a value.
 *
 * @property mac normalised as `AA:BB:CC:DD:EE:FF` when the address could be
 *   recognised; the raw value otherwise.
 * @property macIsValid whether [mac] is a well-formed unicast MAC address.
 * @property enabled value of `enableRule`, or null when absent/unreadable.
 * @property verdict value of `enableLink`, or null when absent/unreadable.
 * @property ipProtocol parsed `ippro`.
 * @property ipProtocolRaw `ippro` exactly as the router sent it.
 * @property remark free-form text. It is personal data and must never be
 *   printed by a diagnostic.
 * @property observedFields the keys actually present in this rule.
 */
data class FirewallMacRule(
    val mac: String,
    val macIsValid: Boolean,
    val enabled: Boolean?,
    val verdict: FirewallMacVerdict?,
    val ipProtocol: FirewallIpProtocol,
    val ipProtocolRaw: String,
    val remark: String,
    val observedFields: Set<String>,
)

/**
 * What a firewall read actually established.
 *
 * The distinction matters: the router answered `success: true` but returned
 * no `datas` at all. Presenting that as "0 rules" would tell the user the
 * firewall has no filter — the exact opposite of what an unreadable reply
 * means, and dangerous in white-list mode where an empty list blocks
 * everything.
 */
enum class FirewallReadState {
    /** The reply carried a `datas` array; its content was parsed. */
    DATA_RECEIVED,

    /** The reply carried no usable `datas`: missing, null or not an array. */
    RESPONSE_INVALID,

    /** The firmware refuses this command for this account (`LIMITED_ACCESS`). */
    FEATURE_UNAVAILABLE,

    /** The reply allows no conclusion: network, session or protocol failure. */
    UNKNOWN,
}

/**
 * The result of a firewall read: the parsed data, or the reason there is
 * none.
 *
 * A rule count is only meaningful in [State.DATA_RECEIVED].
 */
sealed interface FirewallReadOutcome<out T> {

    /** What the read established. */
    val state: FirewallReadState

    /** The observation of the reply, when there was one. */
    val observation: RouterReplyObservation?

    /** The command was accepted and its payload was read. */
    data class Data<out T>(
        val value: T,
        override val observation: RouterReplyObservation,
    ) : FirewallReadOutcome<T> {
        override val state: FirewallReadState get() = FirewallReadState.DATA_RECEIVED
    }

    /**
     * The command was accepted but the payload was missing or unusable.
     *
     * This is **not** an empty list.
     */
    data class Invalid(
        val reason: FirewallInvalidReason,
        override val observation: RouterReplyObservation,
    ) : FirewallReadOutcome<Nothing> {
        override val state: FirewallReadState get() = FirewallReadState.RESPONSE_INVALID
    }

    /** The firmware refuses this command for this account. */
    data class Unavailable(
        val feature: String,
        override val observation: RouterReplyObservation?,
    ) : FirewallReadOutcome<Nothing> {
        override val state: FirewallReadState get() = FirewallReadState.FEATURE_UNAVAILABLE
    }

    /** Nothing can be concluded: the router was unreachable or the session failed. */
    data class Indeterminate(
        val reason: FirewallIndeterminateReason,
        override val observation: RouterReplyObservation? = null,
    ) : FirewallReadOutcome<Nothing> {
        override val state: FirewallReadState get() = FirewallReadState.UNKNOWN
    }
}

/** Why the payload of a firewall read could not be used. */
enum class FirewallInvalidReason {
    /** The `datas` field is absent from the reply. */
    DATAS_MISSING,

    /** The `datas` field is explicitly null. */
    DATAS_NULL,

    /** The `datas` field is not a JSON array. */
    DATAS_NOT_ARRAY,

    /** The `datas` array holds no usable rule. */
    ENTRIES_NOT_OBJECTS,
}

/** Why a firewall read allowed no conclusion. */
enum class FirewallIndeterminateReason {
    /** The router could not be reached. */
    NETWORK,

    /** The session expired and could not be renewed. */
    SESSION_EXPIRED,

    /** The reply was not usable JSON, or reported a failure. */
    PROTOCOL,
}

/**
 * The full answer to a `GET 23`.
 *
 * @property rules in the router's order. The order **is** the priority: the
 *   table displays `index + 1`, and no priority field exists.
 * @property observedKeys the top-level keys of the reply, so a diagnostic can
 *   prove the real shape without printing its content.
 * @property anomalies everything this parser could not read as documented.
 */
data class FirewallMacRules(
    val rules: List<FirewallMacRule>,
    val observedKeys: List<String> = emptyList(),
    val anomalies: List<FirewallAnomaly> = emptyList(),
) {
    /** True when the router returned at least the documented maximum. */
    val isAtDocumentedLimit: Boolean get() = rules.size >= MAX_RULES

    /** True when the reply contains more rules than the web UI allows. */
    val exceedsDocumentedLimit: Boolean get() = rules.size > MAX_RULES

    companion object {
        /**
         * The maximum number of rules the router's own web UI accepts
         * (`macFilter.js`). A read above it is reported, never truncated.
         */
        const val MAX_RULES = 32
    }
}

/**
 * The default policy of the firewall MAC filter (`GET 30`).
 *
 * The router keeps exactly two entries, one per protocol. `acceptAll` is
 * stored as a string (`"true"` / `"false"`) by the web UI, but a boolean is
 * accepted too.
 *
 * `acceptAll = true` means "accept everything", so a rule then has to be a
 * rejection; `acceptAll = false` means "reject everything except the listed
 * addresses", i.e. a white list.
 */
data class FirewallDefaultRule(
    val enabled: Boolean?,
    val acceptAll: Boolean?,
    val ipProtocol: FirewallIpProtocol,
    val ipProtocolRaw: String,
    val observedFields: Set<String>,
)

/**
 * The answer to a `GET 30`: one default rule per protocol.
 *
 * A missing entry is `null`, not `false`: absence is not a policy.
 */
data class FirewallDefaults(
    val ipv4: FirewallDefaultRule? = null,
    val ipv6: FirewallDefaultRule? = null,
    val observedKeys: List<String> = emptyList(),
    val anomalies: List<FirewallAnomaly> = emptyList(),
)

/**
 * Everything a read-only probe needs to describe the firewall MAC filter.
 *
 * This is not a restorable state: the write path is not implemented and
 * remains forbidden. The read was validated on the real firmware on
 * 30/10/2026 — it returned no payload, so the capability is
 * `UNAVAILABLE` on this build.
 *
 * Each half carries its own outcome: a failure on one command must not hide
 * the result of the other.
 */
data class FirewallMacState(
    val rules: FirewallReadOutcome<FirewallMacRules>,
    val defaults: FirewallReadOutcome<FirewallDefaults>,
)

/** A field this parser could not read as documented. */
data class FirewallAnomaly(
    val kind: FirewallAnomalyKind,
    /** Index of the offending rule, or null for a reply-level problem. */
    val ruleIndex: Int? = null,
    /**
     * A short explanation. It must never contain a MAC address, an IP address
     * or a remark: anomalies end up in diagnostics.
     */
    val detail: String = "",
)

/** The kinds of problem [FirewallMacRules] and [FirewallDefaults] report. */
enum class FirewallAnomalyKind {
    /** The reply has no `datas` key at all. */
    DATAS_MISSING,

    /**
     * `datas` is present but explicitly `null`.
     *
     * This is **not** an empty list: an empty list is a confirmed state,
     * a null payload means the router delivered nothing.
     */
    DATAS_NULL,

    /** `datas` is neither an array nor null. */
    DATAS_NOT_ARRAY,

    /** One element of `datas` is not a JSON object. */
    RULE_NOT_OBJECT,

    /** A rule has no `mac` field, or an empty one. */
    MISSING_MAC,

    /** `mac` is not a well-formed unicast MAC address. */
    INVALID_MAC,

    /** `ippro` is absent or carries an unknown value. */
    UNKNOWN_IP_PROTOCOL,

    /** `enableRule` is absent or neither a boolean nor a 0/1-like value. */
    UNREADABLE_ENABLE_RULE,

    /** `enableLink` is absent or neither a boolean nor a 0/1-like value. */
    UNREADABLE_ENABLE_LINK,

    /** `acceptAll` is absent or neither a boolean nor a `"true"`/`"false"` value. */
    UNREADABLE_ACCEPT_ALL,
}
