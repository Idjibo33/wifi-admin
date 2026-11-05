package dev.wifiadmin.poc

import dev.wifiadmin.router.RouterFeatureUnavailableException
import dev.wifiadmin.router.RouterJsonType
import dev.wifiadmin.router.RouterMessageCategory
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.firewall.FirewallAnomaly
import dev.wifiadmin.router.firewall.FirewallDefaults
import dev.wifiadmin.router.firewall.FirewallInvalidReason
import dev.wifiadmin.router.firewall.FirewallIpProtocol
import dev.wifiadmin.router.firewall.FirewallMacRules
import dev.wifiadmin.router.firewall.FirewallMacVerdict
import dev.wifiadmin.router.firewall.FirewallReadOutcome

/**
 * Renders an **anonymised** report of the firewall MAC filter.
 *
 * The report exists to answer one question: what does this firmware actually
 * return for `cmd 23` and `cmd 30`? It therefore prints the structure and the
 * counts, never the content.
 *
 * No MAC address, no remark and no IP address is ever printed. A remark is
 * free text and often contains a person's name, so it is treated as personal
 * data even in a diagnostic.
 *
 * A rule count is only printed when the router actually delivered a
 * collection. "No answer" is never rendered as "0 rules".
 */
object FirewallFilterRenderer {

    fun renderRules(
        outcome: FirewallReadOutcome<FirewallMacRules>?,
        error: Throwable? = null,
    ): String = buildString {
        if (outcome == null) {
            appendLine("  état                     ${describeError(error)}")
            return@buildString
        }
        when (outcome) {
            is FirewallReadOutcome.Data -> {
                appendLine("  état                     DONNÉES REÇUES")
                appendObservation(outcome.observation)
                appendRules(outcome.value)
            }
            is FirewallReadOutcome.Invalid -> {
                appendLine("  état                     RÉPONSE INVALIDE (${describeInvalid(outcome.reason)})")
                appendObservation(outcome.observation)
            }
            is FirewallReadOutcome.Unavailable -> {
                appendLine("  état                     FONCTION INDISPONIBLE")
                appendObservation(outcome.observation)
            }
            is FirewallReadOutcome.Indeterminate -> {
                appendLine("  état                     INDÉTERMINÉ (${outcome.reason})")
                appendObservation(outcome.observation)
            }
        }
    }

    fun renderDefaults(
        outcome: FirewallReadOutcome<FirewallDefaults>?,
        error: Throwable? = null,
    ): String = buildString {
        if (outcome == null) {
            appendLine("  état                     ${describeError(error)}")
            return@buildString
        }
        when (outcome) {
            is FirewallReadOutcome.Data -> {
                appendLine("  état                     DONNÉES REÇUES")
                appendObservation(outcome.observation)
                appendDefaults(outcome.value)
            }
            is FirewallReadOutcome.Invalid -> {
                appendLine("  état                     RÉPONSE INVALIDE (${describeInvalid(outcome.reason)})")
                appendObservation(outcome.observation)
            }
            is FirewallReadOutcome.Unavailable -> {
                appendLine("  état                     FONCTION INDISPONIBLE")
                appendObservation(outcome.observation)
            }
            is FirewallReadOutcome.Indeterminate -> {
                appendLine("  état                     INDÉTERMINÉ (${outcome.reason})")
                appendObservation(outcome.observation)
            }
        }
    }

    private fun StringBuilder.appendObservation(observation: dev.wifiadmin.router.RouterReplyObservation?) {
        if (observation == null) return
        appendLine("  success                  ${observation.success ?: "absent"}")
        appendLine("  message                  ${describeCategory(observation.messageCategory)}")
        appendLine("  collection datas         ${describeDatas(observation.datasPresent, observation.datasType)}")
        appendLine("  champ d'erreur           ${observation.hasErrorField}")
        appendLine("  clés de la réponse       ${safeKeys(observation.topLevelKeys)}")
    }

    private fun StringBuilder.appendRules(rules: FirewallMacRules) {
        appendLine("  règles lues              ${rules.rules.size}")
        appendLine("  limite documentée        ${FirewallMacRules.MAX_RULES}")
        appendLine("  limite atteinte          ${rules.isAtDocumentedLimit}")
        appendLine("  limite dépassée          ${rules.exceedsDocumentedLimit}")

        if (rules.rules.isEmpty()) {
            // An empty array was delivered: this is a confirmed state.
            appendLine("  liste                    VIDE (confirmée)")
        } else {
            appendLine("  règles actives           ${rules.rules.count { it.enabled == true }} / ${rules.rules.size}")
            appendLine("  liste noire              ${rules.rules.count { it.verdict == FirewallMacVerdict.BLACKLIST }}")
            appendLine("  liste blanche            ${rules.rules.count { it.verdict == FirewallMacVerdict.WHITELIST }}")
            appendLine("  verdict illisible        ${rules.rules.count { it.verdict == null }}")
            appendLine("  MAC valides              ${rules.rules.count { it.macIsValid }} / ${rules.rules.size}")
            appendLine("  remarques non vides      ${rules.rules.count { it.remark.isNotEmpty() }} / ${rules.rules.size}")
            appendLine("  protocoles               ${protocolSummary(rules)}")
        }
        appendLine("  anomalies                ${anomalies(rules.anomalies)}")
    }

    private fun StringBuilder.appendDefaults(defaults: FirewallDefaults) {
        appendLine("  politique par défaut :")
        appendLine("    IPv4                   ${describe(defaults.ipv4?.enabled, defaults.ipv4?.acceptAll)}")
        appendLine("    IPv6                   ${describe(defaults.ipv6?.enabled, defaults.ipv6?.acceptAll)}")
        appendLine("    anomalies              ${anomalies(defaults.anomalies)}")
    }

    private fun describe(enabled: Boolean?, acceptAll: Boolean?): String {
        val state = when (acceptAll) {
            true -> "accepte tout (liste noire)"
            false -> "refuse tout sauf exceptions (liste blanche)"
            null -> "illisible"
        }
        return "$state, active=${enabled ?: "illisible"}"
    }

    private fun protocolSummary(rules: FirewallMacRules): String {
        val counts = FirewallIpProtocol.entries.mapNotNull { protocol ->
            val count = rules.rules.count { it.ipProtocol == protocol }
            if (count == 0) null else "${protocol.name}=$count"
        }
        return counts.joinToString(", ").ifEmpty { "(aucun)" }
    }

    private fun anomalies(anomalies: List<FirewallAnomaly>): String =
        anomalies
            .groupingBy { it.kind.name }
            .eachCount()
            .entries
            .joinToString(", ") { (kind, count) -> "$kind=$count" }
            .ifEmpty { "(aucune)" }

    /**
     * The category of the router's `message`, never its value.
     */
    private fun describeCategory(category: RouterMessageCategory): String = when (category) {
        RouterMessageCategory.ABSENT -> "absent"
        RouterMessageCategory.NULL -> "null"
        RouterMessageCategory.EMPTY -> "vide"
        RouterMessageCategory.SESSION_EXPIRED -> "session expirée"
        RouterMessageCategory.SESSION_TIMEOUT -> "session expirée"
        RouterMessageCategory.LIMITED_ACCESS -> "refus du firmware"
        RouterMessageCategory.SUCCESS -> "confirmation"
        RouterMessageCategory.MISSING_PARAMETER -> "paramètre manquant"
        RouterMessageCategory.DISPLAYABLE -> "jeton connu"
        RouterMessageCategory.UNKNOWN -> "valeur non reconnue (non affichée)"
    }

    private fun describeDatas(present: Boolean, type: RouterJsonType?): String = when {
        !present -> "absente"
        type == RouterJsonType.NULL -> "présente, null"
        type == RouterJsonType.ARRAY -> "présente, tableau"
        else -> "présente, ${type?.name?.lowercase() ?: "inconnue"}"
    }

    private fun describeInvalid(reason: FirewallInvalidReason): String = when (reason) {
        FirewallInvalidReason.DATAS_MISSING -> "datas absent"
        FirewallInvalidReason.DATAS_NULL -> "datas null"
        FirewallInvalidReason.DATAS_NOT_ARRAY -> "datas n'est pas un tableau"
        FirewallInvalidReason.ENTRIES_NOT_OBJECTS -> "datas ne contient aucune règle exploitable"
    }

    /**
     * A description of a failure, built from closed values only.
     *
     * The exception's own message is never reproduced: it may embed a
     * router-supplied string.
     */
    private fun describeError(error: Throwable?): String = when (error) {
        null -> "INDÉTERMINÉ"
        is RouterFeatureUnavailableException -> "FONCTION INDISPONIBLE (refus du firmware)"
        is RouterNetworkException -> "INDÉTERMINÉ (routeur injoignable)"
        is RouterProtocolException -> "INDÉTERMINÉ (réponse inutilisable)"
        else -> "INDÉTERMINÉ"
    }

    /**
     * Keeps only the key names that describe the shape of a reply.
     *
     * A key name is content: a router can name a field after a secret, and
     * it can put a line break in it to forge a line of this report. The
     * sanitising is shared with the Wi-Fi report.
     */
    private fun safeKeys(keys: List<String>): String =
        SafeKeyNames.sanitizeAll(keys).joinToString(", ").ifEmpty { "(aucune)" }
}
