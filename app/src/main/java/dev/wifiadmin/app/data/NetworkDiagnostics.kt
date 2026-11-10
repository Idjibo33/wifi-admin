package dev.wifiadmin.app.data

/**
 * Le problème réseau classifié, **sans jamais affirmer une cause non prouvée**.
 *
 * Règles (Mission 15 §7) :
 * - `PERMISSION_REQUIRED` n'est posée que si l'état réel de la permission
 *   bloque le LAN (`blocksLocalNetwork == true`). Un timeout ne devient
 *   jamais « permission refusée ».
 * - `TIMEOUT_UNKNOWN` reste indéterminée : elle ne prétend ni que le VPN
 *   ni que la permission est en cause.
 * - Le VPN n'est qu'une **indication prudente** ([NetworkDiagnosis.shouldShowVpnHint]),
 *   affichée uniquement quand un échec routeur/timeout coexiste avec un VPN
 *   présent. Un VPN actif n'implique jamais automatiquement une
 *   inaccessibilité LAN (split-tunnel, socket Wi-Fi épinglé).
 */
enum class NetworkProblem {
    /** Aucun problème : le réseau est utilisable ou l'opération a réussi. */
    NONE,

    /** L'autorisation du réseau local est requise/refusée (vérifiée). */
    PERMISSION_REQUIRED,

    /** Aucun Wi-Fi n'est disponible. */
    NO_WIFI,

    /** Le Wi-Fi a changé d'identité. */
    NETWORK_CHANGED,

    /** Le routeur est inaccessible (serveur indisponible, adresse fausse...). */
    ROUTER_UNREACHABLE,

    /** Timeout d'origine indéterminée. */
    TIMEOUT_UNKNOWN,
}

/**
 * Le libellé prudent du VPN.
 *
 * Volontairement modal (« peut », jamais « bloque ») : la cause n'est jamais
 * certaine sans sonde. Aucune désactivation automatique du VPN, aucun
 * contournement silencieux, aucun `bindProcessToNetwork` global.
 */
const val VPN_MAY_AFFECT_MESSAGE =
    "Un VPN est actif et peut affecter la connexion à votre routeur. " +
        "Vérifiez ses paramètres d'accès au réseau local, puis réessayez."

/**
 * Le diagnostic d'une situation réseau.
 *
 * @param problem le problème classifié.
 * @param shouldShowVpnHint vrai quand un échec coexiste avec un VPN présent.
 *   Ce n'est **pas** une affirmation de cause.
 */
data class NetworkDiagnosis(
    val problem: NetworkProblem,
    val shouldShowVpnHint: Boolean,
)

/**
 * Classe le problème à partir de faits observables uniquement.
 *
 * Fonction **pure** (aucun Android, aucun I/O) : testable sur JVM.
 *
 * @param permissionBlocks vrai si la permission requise bloque le LAN
 *   (état réel du gateway, jamais une déduction d'un timeout).
 * @param wifiStatus le statut Wi-Fi observé par le [NetworkMonitor].
 * @param vpnPresent vrai si un réseau `TRANSPORT_VPN` est présent dans
 *   `ConnectivityManager.allNetworks` (fait, pas un verdict).
 * @param failure l'échec routeur éventuel, ou null si l'opération a réussi.
 */
fun diagnoseNetwork(
    permissionBlocks: Boolean,
    wifiStatus: LocalNetworkStatus,
    vpnPresent: Boolean,
    failure: RouterUnavailableException?,
): NetworkDiagnosis {
    if (permissionBlocks) {
        return NetworkDiagnosis(
            problem = NetworkProblem.PERMISSION_REQUIRED,
            shouldShowVpnHint = false,
        )
    }
    if (failure is RouterUnavailableException.LocalNetworkPermissionDenied) {
        return NetworkDiagnosis(
            problem = NetworkProblem.PERMISSION_REQUIRED,
            shouldShowVpnHint = false,
        )
    }
    when (wifiStatus) {
        LocalNetworkStatus.NoWifi, LocalNetworkStatus.NotOnWifi ->
            return NetworkDiagnosis(
                problem = NetworkProblem.NO_WIFI,
                shouldShowVpnHint = false,
            )
        is LocalNetworkStatus.DifferentNetwork ->
            return NetworkDiagnosis(
                problem = NetworkProblem.NETWORK_CHANGED,
                shouldShowVpnHint = false,
            )
        is LocalNetworkStatus.LocalWifi -> Unit
    }
    if (failure == null) {
        return NetworkDiagnosis(
            problem = NetworkProblem.NONE,
            shouldShowVpnHint = false,
        )
    }
    val hint = vpnPresent && (
        failure is RouterUnavailableException.Unreachable ||
            failure is RouterUnavailableException.Timeout
        )
    return when (failure) {
        is RouterUnavailableException.Timeout ->
            NetworkDiagnosis(
                problem = NetworkProblem.TIMEOUT_UNKNOWN,
                shouldShowVpnHint = hint,
            )
        is RouterUnavailableException.Unreachable ->
            NetworkDiagnosis(
                problem = NetworkProblem.ROUTER_UNREACHABLE,
                shouldShowVpnHint = hint,
            )
        else -> NetworkDiagnosis(
            problem = when (failure) {
                is RouterUnavailableException.AuthenticationFailed,
                is RouterUnavailableException.SessionExpired,
                is RouterUnavailableException.NotConnected,
                is RouterUnavailableException.Cooldown,
                is RouterUnavailableException.UnexpectedResponse,
                is RouterUnavailableException.LocalNetworkPermissionDenied,
                -> if (hint) NetworkProblem.ROUTER_UNREACHABLE else NetworkProblem.ROUTER_UNREACHABLE
                else -> NetworkProblem.ROUTER_UNREACHABLE
            },
            shouldShowVpnHint = hint && false, // Indication prudente réservée à Unreachable/Timeout.
        )
    }
}
