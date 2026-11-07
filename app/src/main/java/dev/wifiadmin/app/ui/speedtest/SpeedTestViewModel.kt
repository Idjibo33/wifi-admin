package dev.wifiadmin.app.ui.speedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.speedtest.CancelReason
import dev.wifiadmin.app.data.speedtest.SpeedTestFailure
import dev.wifiadmin.app.data.speedtest.SpeedTestPhase
import dev.wifiadmin.app.data.speedtest.SpeedTestPreferencesStore
import dev.wifiadmin.app.data.speedtest.SpeedTestRepository
import dev.wifiadmin.app.data.speedtest.SpeedTestResult
import dev.wifiadmin.app.data.speedtest.SpeedTestState
import dev.wifiadmin.app.data.speedtest.WifiLinkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of the speed test screen.
 *
 * There is no polling here: a test only starts on an explicit tap, only
 * one runs at a time, and leaving the screen cancels it.
 */
data class SpeedTestUiState(
    val phase: SpeedTestPhase? = null,
    val isRunning: Boolean = false,
    val isCancelled: Boolean = false,
    val currentMbps: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val latencyMillis: Long? = null,
    val result: SpeedTestResult? = null,
    val failure: SpeedTestFailure? = null,
    val wifiLink: WifiLinkInfo? = null,
    val hasConsented: Boolean = false,
    val showConsent: Boolean = false,
    val isNetworkOk: Boolean = true,
    val previousResult: SpeedTestResult? = null,
) {
    val isDisabled: Boolean get() = failure is SpeedTestFailure.Disabled
}

/**
 * Drives the speed test screen.
 *
 * The ViewModel only delegates to [SpeedTestRepository]; it never opens a
 * socket itself. A network loss is observed and cancels the running test
 * without ever falling back to mobile data.
 */
class SpeedTestViewModel(
    private val repository: SpeedTestRepository,
    private val preferences: SpeedTestPreferencesStore,
    private val monitor: NetworkMonitor,
    private val linkProvider: dev.wifiadmin.app.data.speedtest.WifiLinkInfoProvider? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SpeedTestUiState())
    val state: StateFlow<SpeedTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.hasConsented.collect { consented ->
                _state.value = _state.value.copy(hasConsented = consented)
            }
        }
        viewModelScope.launch {
            repository.state.collect { repoState ->
                val current = _state.value
                _state.value = when (repoState) {
                    SpeedTestState.Idle -> current.copy(isRunning = false)
                    is SpeedTestState.Running -> current.copy(
                        phase = repoState.phase,
                        isRunning = true,
                        isCancelled = false,
                        failure = null,
                        currentMbps = repoState.downloadMbps ?: repoState.uploadMbps,
                    )
                    is SpeedTestState.Done -> current.copy(
                        isRunning = false,
                        phase = null,
                        result = repoState.result,
                        previousResult = repoState.result,
                        downloadMbps = repoState.result.downloadMbps,
                        uploadMbps = repoState.result.uploadMbps,
                        latencyMillis = repoState.result.latencyMillis,
                        currentMbps = repoState.result.downloadMbps,
                        wifiLink = repoState.result.wifiLink ?: current.wifiLink,
                        failure = null,
                    )
                    SpeedTestState.Cancelled -> current.copy(
                        isRunning = false,
                        phase = null,
                        isCancelled = true,
                    )
                    is SpeedTestState.Failed -> current.copy(
                        isRunning = false,
                        phase = null,
                        failure = repoState.reason,
                    )
                }
            }
        }
        viewModelScope.launch {
            monitor.observeStatus().collect { status ->
                val ok = status is LocalNetworkStatus.LocalWifi
                _state.value = _state.value.copy(isNetworkOk = ok)
                if (!ok && repository.isRunning) {
                    repository.cancel(CancelReason.NETWORK_LOST)
                }
                if (ok) {
                    _state.value = _state.value.copy(wifiLink = linkProvider?.current())
                }
            }
        }
    }

    /** The user tapped "Lancer" — the only entry point of a test. */
    fun start() {
        if (!_state.value.hasConsented) {
            _state.value = _state.value.copy(showConsent = true)
            return
        }
        repository.start()
    }

    /** The user accepted the first-test consent. */
    fun acceptConsentAndStart() {
        viewModelScope.launch {
            preferences.setConsent(true)
            _state.value = _state.value.copy(showConsent = false)
            repository.start()
        }
    }

    fun dismissConsent() {
        _state.value = _state.value.copy(showConsent = false)
    }

    fun cancel() {
        repository.cancel(CancelReason.USER)
    }

    override fun onCleared() {
        repository.cancel()
        super.onCleared()
    }
}
