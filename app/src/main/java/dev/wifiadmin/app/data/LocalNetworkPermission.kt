package dev.wifiadmin.app.data

import kotlinx.coroutines.flow.StateFlow

/**
 * L'autorisation du réseau local (`ACCESS_LOCAL_NETWORK`).
 *
 * Documentation officielle : page Local network permission (Android Developers).
 *
 * Règle de la plateforme : la permission n'est exigée que lorsque l'appareil
 * exécute Android 17 (API 37) ou plus récent **et** que l'application cible
 * l'API 37 ou plus. Sur les versions antérieures — et lorsque l'application
 * cible l'API 36 ou moins — l'accès au réseau local est implicite via la
 * permission `INTERNET`. Demander la permission dans ces cas serait inutile.
 *
 * Cet objet est volontairement **pur et sans Android** : [resolveLocalNetworkPermissionStatus]
 * ne dépend que de chiffres, de sorte qu'il est testable sur JVM avec des
 * fakes, sans émulateur.
 */
enum class LocalNetworkPermissionStatus {
    /** La permission n'existe pas ou n'est pas exigée sur cet appareil. Aucune demande, aucun bandeau. */
    NotRequired,

    /** La permission est exigée et a été accordée. */
    Granted,

    /** La permission est exigée et n'a pas encore été demandée. */
    Required,

    /** La permission est exigée et a été refusée au moins une fois, mais peut encore être demandée. */
    Refused,

    /**
     * La permission est exigée mais l'utilisateur a refusé de façon
     * permanente (`shouldShowRequestPermissionRationale() == false` après une
     * demande). Seuls les réglages Android peuvent la rétablir.
     */
    PermanentlyRefused,
}

/** L'état de l'autorisation, observé par l'UI. */
data class LocalNetworkPermissionState(
    val status: LocalNetworkPermissionStatus,
    /** Vrai si une demande système peut encore aboutir. Faux pour [LocalNetworkPermissionStatus.PermanentlyRefused]. */
    val canAskAgain: Boolean,
    /** Vrai tandis qu'un dialogue système est affiché. Anti-doublon. */
    val isRequestInFlight: Boolean = false,
) {
    /** Vrai quand aucune opération LAN ne doit démarrer. */
    val blocksLocalNetwork: Boolean
        get() = status == LocalNetworkPermissionStatus.Required ||
            status == LocalNetworkPermissionStatus.Refused ||
            status == LocalNetworkPermissionStatus.PermanentlyRefused
}

/** Le nom exact de la permission Android. */
const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Le seuil d'exigibilité de la permission.
 *
 * Vrai uniquement sur Android 17+ (API 37+) avec une cible API 37+.
 * Toute autre combinaison utilise le comportement implicite (`INTERNET`).
 */
fun requiresLocalNetworkPermission(sdkInt: Int, targetSdk: Int): Boolean =
    sdkInt >= 37 && targetSdk >= 37

/**
 * La décision de l'état de la permission, fonction pure.
 *
 * @param sdkInt la version de l'appareil (`Build.VERSION.SDK_INT`).
 * @param targetSdk la cible de l'application.
 * @param granted vrai si `checkSelfPermission()` a accordé la permission.
 * @param rationale vrai si `shouldShowRequestPermissionRationale()` est vrai.
 * @param previouslyRefused vrai si l'utilisateur a déjà refusé au moins une fois.
 */
fun resolveLocalNetworkPermissionStatus(
    sdkInt: Int,
    targetSdk: Int,
    granted: Boolean,
    rationale: Boolean,
    previouslyRefused: Boolean,
): LocalNetworkPermissionState {
    if (!requiresLocalNetworkPermission(sdkInt, targetSdk)) {
        return LocalNetworkPermissionState(
            status = LocalNetworkPermissionStatus.NotRequired,
            canAskAgain = false,
        )
    }
    if (granted) {
        return LocalNetworkPermissionState(
            status = LocalNetworkPermissionStatus.Granted,
            canAskAgain = false,
        )
    }
    if (previouslyRefused && !rationale) {
        return LocalNetworkPermissionState(
            status = LocalNetworkPermissionStatus.PermanentlyRefused,
            canAskAgain = false,
        )
    }
    if (previouslyRefused) {
        return LocalNetworkPermissionState(
            status = LocalNetworkPermissionStatus.Refused,
            canAskAgain = true,
        )
    }
    return LocalNetworkPermissionState(
        status = LocalNetworkPermissionStatus.Required,
        canAskAgain = true,
    )
}

/**
 * Le point de passage unique de l'autorisation.
 *
 * Une **seule** instance vit dans [dev.wifiadmin.app.AppContainer], comme
 * `networkMonitor` et `refreshCoordinator`. `MainActivity` reste le seul pont
 * Android (vérification synchrone, launcher, `onResume`) ; les écrans et les
 * repositories **observent** l'état et n'appellent jamais le launcher
 * directement. Aucun second système de permission n'existe.
 */
interface LocalNetworkPermissionGateway {
    val state: StateFlow<LocalNetworkPermissionState>

    /**
     * Re-vérification synchrone de l'autorisation.
     *
     * Appelée depuis `onResume()` pour détecter une révocation depuis les
     * réglages Android. Ne déclenche jamais de demande.
     */
    fun refreshStatus(): LocalNetworkPermissionState

    /**
     * Demande système, idempotente.
     *
     * Sans effet si [LocalNetworkPermissionState.status] est `NotRequired`,
     * `Granted`, `PermanentlyRefused`, ou si une demande est déjà en vol.
     *
     * @return vrai si un lancement a eu lieu.
     */
    fun requestIfNeeded(launch: (String) -> Unit): Boolean

    /**
     * Traduit le résultat du dialogue système en transition d'état.
     *
     * @param granted vrai si l'utilisateur a accordé la permission.
     * @param rationale `shouldShowRequestPermissionRationale()` lu **avant**
     *   le résultat (le système le lit en arrière-plan de façon fiable).
     */
    fun onRequestResult(granted: Boolean, rationale: Boolean)
}
