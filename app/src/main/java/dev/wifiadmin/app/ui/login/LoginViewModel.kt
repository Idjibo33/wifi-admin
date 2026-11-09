package dev.wifiadmin.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterAddressResolver
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What happened on the last connection attempt. */
sealed class LoginResult {
    data object None : LoginResult()
    data object Success : LoginResult()
    data object InvalidCredentials : LoginResult()
    data object NetworkError : LoginResult()
    data object Timeout : LoginResult()
    data object UnexpectedResponse : LoginResult()

    /**
     * L'autorisation du réseau local est requise ou refusée (vérifiée).
     *
     * Aucun socket n'a été ouvert. Après accord, l'écran peut réessayer
     * sans redémarrage via [LoginViewModel.clearLocalNetworkPermissionDenied].
     */
    data object LocalNetworkPermissionDenied : LoginResult()

    /**
     * The previous session could not be renewed.
     *
     * The message on screen is fixed: "Votre session a expiré. Reconnectez-vous
     * pour continuer." This is not a wrong password.
     */
    data object SessionExpired : LoginResult()

    /** The router refuses logins for a while after too many failures. */
    data class Cooldown(val retryAfterSeconds: Int?) : LoginResult()
}

/**
 * The address used when the gateway of the current Wi-Fi network cannot be
 * read. It is the factory default of this router, and only a last resort.
 */
const val FALLBACK_HOST = "192.168.1.1"

/** The state of the login form. */
data class LoginUiState(
    /**
     * The address used for the connection.
     *
     * It is **not** editable: it comes from the current Wi-Fi network. The
     * default is only a fallback for when the gateway cannot be read, so that
     * a connection attempt is still possible.
     */
    val host: String = FALLBACK_HOST,
    val username: String = "admin",
    val isConnecting: Boolean = false,
    /** True when [host] was read from the current Wi-Fi network. */
    val hostWasDetected: Boolean = false,
    val result: LoginResult = LoginResult.None
)

/**
 * Drives the connection screen.
 *
 * The password lives in the composable's local state and is handed straight
 * to [connect]; it is never part of [LoginUiState] and therefore never
 * persisted, logged or restored.
 */
class LoginViewModel(
    private val repository: RouterRepository,
    private val addressResolver: RouterAddressResolver? = null
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<LoginResult>(LoginResult.None)
    val result: StateFlow<LoginResult> = _result.asStateFlow()

    init {
        observeRouterAddress()
        reportExpiryIfAny()
        observeSessionExpiry()
    }

    /**
     * Reports a confirmed expiry to the login screen.
     *
     * The repository is the single source of truth, shared with the
     * navigation. It is **observed** rather than read once: the login view
     * model may be created before the expiry is detected, and the message
     * must still appear.
     */
    private fun observeSessionExpiry() {
        viewModelScope.launch {
            repository.state.collect { state ->
                if (state.connection is ConnectionState.SessionExpired) {
                    _result.value = LoginResult.SessionExpired
                }
            }
        }
    }

    /**
     * Sets the expiry result at once, before any coroutine runs.
     *
     * The screen must show the message on its very first frame: waiting for
     * the collector would show an empty screen for a moment.
     */
    private fun reportExpiryIfAny() {
        if (repository.state.value.connection is ConnectionState.SessionExpired) {
            _result.value = LoginResult.SessionExpired
        }
    }

    /**
     * Reads the address from the gateway of the current Wi-Fi network.
     *
     * The address is not editable: the user connects to the router of the
     * network the phone is on. When the gateway cannot be read, [LoginUiState.host]
     * keeps its fallback value and the screen says the address was not detected.
     */
    private fun observeRouterAddress() {
        val resolver = addressResolver ?: return
        viewModelScope.launch {
            resolver.observeSuggestedAddress().collect { suggestion ->
                if (suggestion.isNullOrBlank()) {
                    _state.value = _state.value.copy(hostWasDetected = false)
                    return@collect
                }
                _state.value = _state.value.copy(
                    host = suggestion,
                    hostWasDetected = true
                )
            }
        }
    }

    fun onUsernameChanged(username: String) {
        _state.value = _state.value.copy(username = username)
    }

    /** Opens the session and reports the outcome. */
    fun connect(host: String, username: String, password: String) {
        if (_state.value.isConnecting) return

        _state.value = _state.value.copy(
            host = host,
            username = username,
            isConnecting = true
        )
        _result.value = LoginResult.None

        viewModelScope.launch(repository.networkDispatcher) {
            val accepted = repository.connect(host, username, password)
            val error = repository.state.value.error
            _state.value = _state.value.copy(isConnecting = false)
            _result.value = when (error) {
                null -> if (accepted) LoginResult.Success else LoginResult.InvalidCredentials
                is RouterUnavailableException.Unreachable -> LoginResult.NetworkError
                is RouterUnavailableException.Timeout -> LoginResult.Timeout
                is RouterUnavailableException.LocalNetworkPermissionDenied ->
                    LoginResult.LocalNetworkPermissionDenied
                is RouterUnavailableException.UnexpectedResponse ->
                    LoginResult.UnexpectedResponse
                is RouterUnavailableException.SessionExpired ->
                    LoginResult.SessionExpired
                is RouterUnavailableException.Cooldown ->
                    LoginResult.Cooldown(error.retryAfterSeconds)
                is RouterUnavailableException.AuthenticationFailed -> LoginResult.InvalidCredentials
                is RouterUnavailableException.NotConnected -> LoginResult.InvalidCredentials
            }
            // L'ancien `else -> InvalidCredentials` aurait masqué
            // LocalNetworkPermissionDenied en « mauvais mot de passe ».
        }
    }

    /**
     * Efface l'erreur de permission après un accord.
     *
     * Appelé quand le dialogue système ou les réglages viennent d'accorder
     * l'autorisation : l'utilisateur peut aussitôt réessayer sans redémarrer.
     * Ne déclenche jamais une connexion automatique (le mot de passe n'est
     * jamais conservé en ViewModel).
     */
    fun clearLocalNetworkPermissionDenied() {
        if (_result.value is LoginResult.LocalNetworkPermissionDenied) {
            _result.value = LoginResult.None
        }
    }
}
