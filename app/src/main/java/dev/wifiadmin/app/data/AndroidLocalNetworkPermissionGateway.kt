package dev.wifiadmin.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * L'implémentation Android du [LocalNetworkPermissionGateway].
 *
 * Lit `ContextCompat.checkSelfPermission()` et
 * `shouldShowRequestPermissionRationale()` de façon synchrone, délègue la
 * décision à [resolveLocalNetworkPermissionStatus], et expose l'état dans un
 * [StateFlow] observé par l'UI. Aucune demande n'est jamais déclenchée par
 * `refreshStatus()` : seule une action utilisateur appelle
 * [requestIfNeeded].
 */
class AndroidLocalNetworkPermissionGateway(
    private val context: Context,
) : LocalNetworkPermissionGateway {

    private val _state = MutableStateFlow(
        resolveNow(rationale = false, previouslyRefused = false),
    )
    override val state: StateFlow<LocalNetworkPermissionState> = _state.asStateFlow()

    /** Vrai après au moins un refus (pour distinguer Required/Refused). */
    private var previouslyRefused = false

    override fun refreshStatus(): LocalNetworkPermissionState {
        val granted = isGranted()
        val rationale = shouldShowRationale()
        // Un rationale vrai signifie qu'un refus a déjà eu lieu.
        if (!granted && rationale) previouslyRefused = true
        val next = resolveLocalNetworkPermissionStatus(
            sdkInt = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion,
            granted = granted,
            rationale = rationale,
            previouslyRefused = previouslyRefused,
        )
        _state.value = next.copy(isRequestInFlight = _state.value.isRequestInFlight)
        return _state.value
    }

    override fun requestIfNeeded(launch: (String) -> Unit): Boolean {
        val current = _state.value
        if (current.isRequestInFlight) return false
        if (current.status != LocalNetworkPermissionStatus.Required &&
            current.status != LocalNetworkPermissionStatus.Refused
        ) {
            return false
        }
        // Re-vérification synchrone juste avant le lancement (l'Activity a pu
        // être recréée entre l'affichage du bouton et le clic).
        val fresh = refreshStatus()
        if (fresh.status != LocalNetworkPermissionStatus.Required &&
            fresh.status != LocalNetworkPermissionStatus.Refused
        ) {
            return false
        }
        _state.value = fresh.copy(isRequestInFlight = true)
        launch(ACCESS_LOCAL_NETWORK_PERMISSION)
        return true
    }

    override fun onRequestResult(granted: Boolean, rationale: Boolean) {
        if (granted) {
            previouslyRefused = false
        } else if (rationale) {
            previouslyRefused = true
        } else {
            // Refus sans rationale après une demande = refus permanent.
            if (_state.value.status == LocalNetworkPermissionStatus.Required ||
                _state.value.status == LocalNetworkPermissionStatus.Refused ||
                _state.value.isRequestInFlight
            ) {
                previouslyRefused = true
            }
        }
        val next = resolveLocalNetworkPermissionStatus(
            sdkInt = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion,
            granted = granted,
            rationale = rationale,
            previouslyRefused = previouslyRefused,
        )
        _state.value = next.copy(isRequestInFlight = false)
    }

    private fun resolveNow(rationale: Boolean, previouslyRefused: Boolean): LocalNetworkPermissionState =
        resolveLocalNetworkPermissionStatus(
            sdkInt = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion,
            granted = isGranted(),
            rationale = rationale,
            previouslyRefused = previouslyRefused,
        )

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            ACCESS_LOCAL_NETWORK_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Fournisseur de `shouldShowRequestPermissionRationale()` branché par l'Activity. */
    @Volatile private var rationaleProvider: (() -> Boolean)? = null

    /**
     * Branche le fournisseur de rationale (appelé par `MainActivity`).
     *
     * Sans lui, le gateway retombe sur `false` : la distinction
     * Required/Refused reste fonctionnelle via la mémoire interne des refus,
     * et le refus permanent est déduit d'un refus sans rationale après une
     * demande.
     */
    fun bindRationaleProvider(provider: () -> Boolean) {
        rationaleProvider = provider
    }

    private fun shouldShowRationale(): Boolean =
        runCatching { rationaleProvider?.invoke() ?: false }.getOrDefault(false)
}
