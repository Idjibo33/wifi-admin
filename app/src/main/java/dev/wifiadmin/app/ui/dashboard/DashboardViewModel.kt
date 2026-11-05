package dev.wifiadmin.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.ui.components.userMessage
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
    val isRefreshing: Boolean = false,
    /**
     * True when the displayed snapshot comes from disk, not from a read made
     * during this session.
     *
     * A cached snapshot describes the network **at the time of the read**. It
     * must never be presented as the current state.
     */
    val isFromCache: Boolean = false,
    /** True when the last synchronisation failed while a snapshot was shown. */
    val isSyncFailed: Boolean = false,
    /** True when the router answered without any device list. */
    val payloadAbsent: Boolean = false,
    /** True once a snapshot — live or cached — has been displayed. */
    val hasSnapshot: Boolean = false,
) {
    /** True when the screen has nothing to show but a spinner. */
    val isFirstLoad: Boolean get() = isLoading && !hasSnapshot

    /** True when a failure happened and there is no snapshot to fall back on. */
    val isFatalError: Boolean get() = errorMessage != null && !hasSnapshot

    /** True when the snapshot on screen is not a fresh, complete read. */
    val hasStaleData: Boolean
        get() = hasSnapshot && (isFromCache || isSyncFailed || payloadAbsent)

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
 *
 * On creation it first adopts the snapshot the repository already holds (the
 * one restored from disk right after a successful login), then refreshes it.
 * Without that first step the restored snapshot would never be shown.
 */
class DashboardViewModel(
    private val repository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        seedFromRepository()
        observeSessionLoss()
        refresh()
    }

    /**
     * Purges the displayed data as soon as the session is no longer valid.
     *
     * The navigation also leaves the screen, but the two are independent: the
     * view model must never keep authenticated devices in its own state,
     * even for the short time the navigation takes to react.
     */
    private fun observeSessionLoss() {
        viewModelScope.launch {
            repository.state.collect { state ->
                if (state.connection !is ConnectionState.Connected &&
                    state.connection !is ConnectionState.Cooldown
                ) {
                    _state.value = _state.value.copy(
                        snapshot = null,
                        lastRefreshMillis = null,
                        isFromCache = false,
                        isSyncFailed = false,
                        payloadAbsent = false,
                        hasSnapshot = false,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    /**
     * Adopts the snapshot the repository restored after authentication.
     *
     * Only a connected repository is read: a cached snapshot must never be
     * displayed before the credentials were accepted.
     */
    private fun seedFromRepository() {
        val repositoryState = repository.state.value
        if (!repositoryState.isConnected) return
        val snapshot = repositoryState.snapshot ?: return

        _state.value = _state.value.copy(
            snapshot = snapshot,
            lastRefreshMillis = snapshot.capturedAtMillis,
            isFromCache = repositoryState.isShowingCachedSnapshot,
            payloadAbsent = repositoryState.payloadAbsent,
            hasSnapshot = true,
        )
    }

    /**
     * Polls the router.
     *
     * The first poll shows a full-screen spinner; the following ones keep the
     * previous snapshot on screen and only flag that a refresh is running.
     */
    fun refresh() {
        val hadSnapshot = _state.value.hasSnapshot

        _state.value = _state.value.copy(
            isLoading = !hadSnapshot,
            isRefreshing = hadSnapshot,
            errorMessage = null
        )

        viewModelScope.launch(repository.networkDispatcher) {
            val result = repository.refresh()
            result
                .onSuccess { snapshot ->
                    // The repository keeps the previous snapshot when the
                    // router answered without a device list. Always display
                    // what it settled on.
                    val effective = repository.state.value.snapshot ?: snapshot
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        snapshot = effective,
                        lastRefreshMillis = effective.capturedAtMillis,
                        payloadAbsent = snapshot.payloadAbsent,
                        hasSnapshot = true,
                        isFromCache = repository.state.value.isShowingCachedSnapshot,
                        isSyncFailed = false,
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    val hasSnapshot = _state.value.hasSnapshot
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isSyncFailed = hasSnapshot,
                        errorMessage = (error as? RouterUnavailableException)?.userMessage()
                            ?: error.message
                            ?: "Impossible de joindre le routeur."
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
