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

fun formatLatency(value: Long?): String = if (value == null) SpeedTestStrings.UNKNOWN else "$value ms"

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
