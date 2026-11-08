package dev.wifiadmin.app.ui.speedtest

import dev.wifiadmin.app.data.speedtest.SpeedTestFailure
import dev.wifiadmin.app.data.speedtest.SpeedTestPhase
import dev.wifiadmin.app.data.speedtest.SpeedTestResult
import dev.wifiadmin.app.ui.components.formatSnapshotDate

/** The exact sentences of the speed test screen: screens and tests share them. */
object SpeedTestStrings {
    const val TITLE = "Test de vitesse"
    const val READY = "Prêt à mesurer"
    const val CHECKING = "Vérification du réseau…"
    const val LATENCY_PHASE = "Mesure de la latence…"
    const val DOWNLOAD_PHASE = "Téléchargement en cours…"
    const val UPLOAD_PHASE = "Envoi en cours…"
    const val DONE = "Test terminé."
    const val CANCELLED = "Test annulé. Le résultat précédent reste affiché."
    const val DISABLED = "Test de vitesse Internet indisponible dans cette version."
    const val DISABLED_BODY = "Aucun fournisseur de mesure n'est configuré : aucun endpoint public " +
        "n'est à la fois autorisé, fiable et contrôlé sous les plafonds (20 Mo en téléchargement, " +
        "10 Mo en envoi, 30 s). Rien ne quitte le téléphone. La liaison Wi-Fi ci-dessous reste " +
        "disponible, et le test est prêt à être branché sur un fournisseur validé."
    const val WIFI_GONE = "Wi-Fi indisponible. Connectez le téléphone au Wi-Fi du routeur pour lancer un test."
    const val INTERNET_GONE = "Wi-Fi connecté, mais Internet est indisponible."
    const val SERVER_GONE = "Le serveur de test est injoignable. Réessayez dans un instant."
    const val TOO_BUSY = "Un test est déjà en cours."
    const val TIMEOUT = "Le test a dépassé le délai autorisé."
    const val INCOMPLETE = "La mesure a été interrompue. Les valeurs affichées sont partielles."
    const val LINK_TITLE = "Liaison Wi-Fi (téléphone ↔ routeur)"
    const val LINK_NOTE = "La liaison mesure le Wi-Fi entre le téléphone et le routeur. " +
        "Le test de vitesse mesure Internet via un serveur. Un débit de liaison élevé " +
        "ne garantit pas un débit Internet élevé."
    const val UNKNOWN = "—"
    const val CONFIGURED = "Fournisseur de mesure configuré."
    const val INTERNET_TITLE = "Débit Internet (téléphone ↔ serveur de mesure)"
    const val CONSUMPTION_LABEL = "Consommation (réception / envoi)"
}

fun phaseLabel(phase: SpeedTestPhase?): String = when (phase) {
    SpeedTestPhase.CHECKING_NETWORK -> SpeedTestStrings.CHECKING
    SpeedTestPhase.LATENCY -> SpeedTestStrings.LATENCY_PHASE
    SpeedTestPhase.DOWNLOAD -> SpeedTestStrings.DOWNLOAD_PHASE
    SpeedTestPhase.UPLOAD -> SpeedTestStrings.UPLOAD_PHASE
    SpeedTestPhase.FINALIZING -> "Calcul du résultat…"
    null -> SpeedTestStrings.READY
}

fun failureMessage(failure: SpeedTestFailure): String = when (failure) {
    SpeedTestFailure.NoWiFi -> SpeedTestStrings.WIFI_GONE
    SpeedTestFailure.InternetUnavailable -> SpeedTestStrings.INTERNET_GONE
    SpeedTestFailure.ServerUnavailable -> SpeedTestStrings.SERVER_GONE
    SpeedTestFailure.Disabled -> SpeedTestStrings.DISABLED
    SpeedTestFailure.TooBusy -> SpeedTestStrings.TOO_BUSY
    SpeedTestFailure.Timeout -> SpeedTestStrings.TIMEOUT
    SpeedTestFailure.Incomplete -> SpeedTestStrings.INCOMPLETE
    SpeedTestFailure.Unexpected -> "Une erreur est survenue. Réessayez."
}

fun formatMbps(value: Double?): String =
    if (value == null) SpeedTestStrings.UNKNOWN else "%.1f".format(value).replace('.', ',')

/**
 * French display of a rate: a capped measurement is a floor, never a
 * precise figure (`≥ X Mbps (plafonné)`), an uncapped one stays plain.
 */
fun formatMbpsCapped(value: Double?, capped: Boolean): String {
    if (value == null) return SpeedTestStrings.UNKNOWN
    val base = formatMbps(value)
    return if (capped) "≥ $base Mbps (plafonné)" else base
}

/** Display of a consumed volume: 1 Mo = 1 048 576 octets (policy units). */
fun formatMo(bytes: Long?): String =
    if (bytes == null) SpeedTestStrings.UNKNOWN
    else "%.1f".format(bytes / 1_048_576.0).replace('.', ',') + " Mo"

fun formatConsumption(downloadBytes: Long?, uploadBytes: Long?): String =
    formatMo(downloadBytes) + " / " + formatMo(uploadBytes)

fun consumptionLine(result: SpeedTestResult?): String? =
    result?.let { "Consommation : " + formatConsumption(it.downloadBytes, it.uploadBytes) + " (réception / envoi)" }

fun formatLatency(value: Long?): String = if (value == null) SpeedTestStrings.UNKNOWN else "$value ms"

/** Jitter display: null is "indisponible", never a fake 0 ms. */
fun formatJitter(value: Double?): String =
    if (value == null || value.isNaN()) "indisponible" else "%.1f".format(value).replace('.', ',') + " ms (indicatif)"

/**
 * Keeps only the host of a candidate target: no scheme, no credentials,
 * no port, no path, no query and no IP literal is ever displayed.
 * Returns null when the candidate does not describe a host name.
 */
fun sanitizeHostLabel(raw: String?): String? {
    var host = raw?.trim() ?: return null
    if (host.isEmpty()) return null
    host = host.substringAfter("://", missingDelimiterValue = host)
    host = host.substringAfterLast('@', missingDelimiterValue = host)
    host = host.substringBefore('/').substringBefore('?').substringBefore('#')
    host = host.substringBefore(' ').substringBefore('·').substringBefore('(').substringBefore(')').trim()
    host = host.substringBefore(':')
    if (host.isEmpty()) return null
    // Never display an IP literal (v4 or v6).
    if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return null
    if (host.contains(':') || host.contains('@') || host.contains('_')) return null
    if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
    if (host.none { it.isLetterOrDigit() }) return null
    return host
}

/** Host named in a LibreSpeed style label (`LibreSpeed — vers <hôte>`). */
fun extractTargetHost(providerLabel: String?): String? {
    val label = providerLabel ?: return null
    val marker = Regex("vers", RegexOption.IGNORE_CASE).findAll(label).lastOrNull() ?: return null
    val rest = label.drop(marker.range.last + 1).trimStart(' ', '-', ':', '—', '–')
    val token = rest.split(' ', '·', ',', ';', ')', '(').firstOrNull()?.trim().orEmpty()
    return sanitizeHostLabel(token)
}

/** Host to display: explicit targethost first, then the label's own host. */
fun effectiveTargetHost(providerLabel: String?, targetHost: String?): String? =
    sanitizeHostLabel(targetHost) ?: extractTargetHost(providerLabel)

/**
 * The configured-provider banner: long label plus `vers <hôte>` (HTTPS),
 * never a full URL and never an IP. Falls back to a generic sentence
 * when neither label nor host is known yet.
 */
fun providerBannerText(providerLabel: String?, targetHost: String?): String {
    val label = providerLabel?.trim().takeIf { !it.isNullOrEmpty() }
    val host = effectiveTargetHost(label, sanitizeHostLabel(targetHost))
    val head = if (label != null) "Fournisseur : $label" else SpeedTestStrings.CONFIGURED
    val destination = host?.let {
        if (label != null && label.contains(it)) "" else " · vers $it (HTTPS)"
    }.orEmpty()
    return head + destination + " · Consommation max. 30 Mo/test"
}

/** The consent-dialog server line: host-aware, or the disabled sentence. */
fun consentServerLine(providerLabel: String?, targetHost: String?, disabled: Boolean): String {
    if (disabled) return "Serveur : aucun fournisseur configuré dans cette version. Aucun trafic ne sera envoyé."
    val host = effectiveTargetHost(providerLabel, targetHost)
    val destination = host?.let { " — vers $it (HTTPS)" }.orEmpty()
    val label = providerLabel?.takeIf { it.isNotBlank() } ?: "Fournisseur configuré"
    return "Serveur : $label$destination. Pendant la mesure, le serveur voit l'adresse IP publique du Wi-Fi."
}

fun httpsExternalLinks(): List<Pair<String, String>> = listOf(
    "measurementlab" to "https://speed.measurementlab.net/",
    "speedtest.net" to "https://www.speedtest.net/",
    "cloudflare" to "https://speed.cloudflare.com/",
    "fast.com" to "https://fast.com/",
)

fun SpeedTestResult.historyLine(): String {
    val date = formatSnapshotDate(capturedAtWallMillis) ?: "date inconnue"
    return "Dernier test : $date"
}
