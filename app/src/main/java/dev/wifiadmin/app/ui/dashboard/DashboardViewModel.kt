package dev.wifiadmin.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.WifiBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The state of the dashboard.
 *
 * Every count shown on the dashboard comes from the runtime snapshot. There is
 * no constant anywhere: the numbers are whatever the router reported during
 * the last successful poll.
 */
data class DashboardUiState(
    /** True while the very first snapshot is being loaded. */
    val isLoading: Boolean = false,
    /** The last snapshot the router returned, or null if there never was one. */
    val snapshot: DeviceSnapshot? = null,
    /** When the last successful poll happened, in epoch milliseconds. */
    val lastRefreshMillis: Long? = null,
    /** The message of the last failure, or null. */
    val errorMessage: String? = null,
    /** True while a refresh runs on top of an existing snapshot. */
    val isRefreshing: Boolean = false
) {
    /** True when the screen has nothing to show but a spinner. */
    val isFirstLoad: Boolean get() = isLoading && snapshot == null

    /** True when a failure happened and there is no snapshot to fall back on. */
    val isFatalError: Boolean get() = errorMessage != null && snapshot == null

    /**
     * The number of devices associated with the 2.4 GHz radio.
     *
     * Computed from the snapshot, never hardcoded.
     */
    val band24Count: Int
        get() = snapshot?.devices?.count { WifiBand.BAND_2_4_GHZ in it.bands } ?: 0

    /**
     * The number of devices associated with the 5 GHz radio.
     *
     * Computed from the snapshot, never hardcoded.
     */
    val band5Count: Int
        get() = snapshot?.devices?.count { WifiBand.BAND_5_GHZ in it.bands } ?: 0
}

/**
 * Drives the dashboard screen.
 *
 * The ViewModel only reads the router through the repository; it never opens a
 * session and never touches the Android framework.
 */
class DashboardViewModel(
    private val repository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Polls the router.
     *
     * The first poll shows a full-screen spinner; the following ones keep the
     * previous snapshot on screen and only flag that a refresh is running.
     */
    fun refresh() {
        val hadSnapshot = _state.value.snapshot != null

        _state.value = _state.value.copy(
            isLoading = !hadSnapshot,
            isRefreshing = hadSnapshot,
            errorMessage = null
        )

        viewModelScope.launch(repository.networkDispatcher) {
            val result = repository.refresh()
            result
                .onSuccess { snapshot ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        snapshot = snapshot,
                        lastRefreshMillis = snapshot.capturedAtMillis,
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Impossible de joindre le routeur."
                    )
                }
        }
    }
}

/**
 * Formats a timestamp as a local wall-clock time such as `14:32:05`.
 *
 * @param millis the epoch milliseconds to format, or null.
 * @return the formatted time, or null when there is no timestamp.
 */
fun formatLastRefresh(millis: Long?): String? {
    if (millis == null) return null
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(millis))
}
