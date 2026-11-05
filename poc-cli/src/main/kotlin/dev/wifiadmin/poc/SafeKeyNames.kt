package dev.wifiadmin.poc

/**
 * Sanitises the key names a router sends.
 *
 * A key name is **content**: the router chooses it. It may be named after a
 * secret, and it may contain a line break that would forge a line of the
 * report.
 *
 * The rules are:
 *
 * - a name that is exactly known is kept;
 * - a name that starts with a known prefix is **cut to that prefix** — the
 *   rest is dropped, so `macadmin_password` becomes `mac…`;
 * - any other name becomes `…`;
 * - control characters, including line breaks, are always removed.
 */
object SafeKeyNames {

    /** What an unrecognised name is replaced with. */
    const val REDACTED = "…"

    /**
     * The names that are kept as they are.
     *
     * `sessionId` and `token` are **not** in this list: a router that names
     * a field after its own session must not make that name printable.
     */
    private val EXACT_NAMES = setOf(
        "cmd", "datas", "success", "message", "error", "code", "status",
        "enableRule", "enableLink", "acceptAll", "ippro", "remark", "mac",
        "macfilter", "maclist", "subcmd",
        "datas.macfilter", "datas.maclist", "datas.maclist[].mac",
        "datas.maclist[].remarks",
    )

    /** The prefixes a name may start with; the name is cut to the prefix. */
    private val PREFIXES = listOf(
        "enable", "accept", "ippro", "remark", "ipv4", "ipv6", "ip", "mac",
        "port", "proto", "dns", "url", "host", "time", "date", "expire",
        "count", "num", "list", "filter", "mode", "type", "state", "switch",
        "datas.",
    )

    /** Sanitises one key name. */
    fun sanitize(name: String): String {
        val cleaned = stripControlCharacters(name)
        if (cleaned.isEmpty()) return ""
        if (cleaned in EXACT_NAMES) return cleaned

        // A dotted name is sanitized part by part.
        if (cleaned.contains('.')) {
            return cleaned.split('.').joinToString(".") { sanitize(it) }
        }

        val prefix = PREFIXES.firstOrNull { cleaned.startsWith(it) }
        return if (prefix == null) REDACTED else prefix + REDACTED
    }

    /** Sanitises a list of key names. */
    fun sanitizeAll(names: List<String>): List<String> = names.map(::sanitize)

    /**
     * Removes every control character, including line breaks.
     *
     * A line break in a key name would forge a line of the report.
     */
    private fun stripControlCharacters(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '[' || it == ']' }
}
