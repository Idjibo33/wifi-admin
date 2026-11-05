package dev.wifiadmin.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.ui.components.StaleDataReason
import dev.wifiadmin.app.ui.components.userMessage
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The family of devices the user wants to see. */
enum class DeviceFilter {
    /** Every device the router knows about. */
    ALL,

    /** The devices associated with a Wi-Fi radio. */
    WIFI,

    /** The devices holding a DHCP lease on a wired interface. */
    WIRED
}

/** The label shown on the filter chip. */
val DeviceFilter.label: String
    get() = when (this) {
        DeviceFilter.ALL -> "Tous"
        DeviceFilter.WIFI -> "Wi-Fi"
        DeviceFilter.WIRED -> "Filaire"
    }

/**
 * The state of the device list.
 *
 * [devices] is the raw list from the last poll; [filteredDevices] is what the
 * screen actually shows.
 */
data class DevicesUiState(
    /** True while the very first snapshot is being loaded. */
    val isLoading: Boolean = false,
    /** Every device the router reported during the last successful poll. */
    val devices: List<RouterDevice> = emptyList(),
    /** The text typed in the search field. */
    val query: String = "",
    /** The family of devices to keep. */
    val filter: DeviceFilter = DeviceFilter.ALL,
    /** When the last successful poll happened, in epoch milliseconds. */
    val lastRefreshMillis: Long? = null,
    /** The message of the last failure, or null. */
    val errorMessage: String? = null,
    /**
     * True when the router delivered **no** device list at all.
     *
     * The screen must not claim that no device is connected in that case:
     * nothing was received, which is not the same thing as an empty network.
     */
    val payloadAbsent: Boolean = false,
    /**
     * True when the displayed devices come from disk, not from a read made
     * during this session.
     */
    val isFromCache: Boolean = false,
    /** True when the last synchronisation failed while devices were shown. */
    val isSyncFailed: Boolean = false,
    /** True once a snapshot — live or cached — has been displayed. */
    val hasSnapshot: Boolean = false,
    /**
     * True when the phone left the network the router was reached on.
     *
     * The reads are suspended and the last known devices stay on screen.
     */
    val isNetworkChanged: Boolean = false,
) {

    /** True when the screen has nothing to show but a spinner. */
    val isFirstLoad: Boolean get() = isLoading && !hasSnapshot

    /** True when a failure happened and there is no device to fall back on. */
    val isFatalError: Boolean get() = errorMessage != null && !hasSnapshot

    /** True when the list on screen is not a fresh, complete read. */
    val hasStaleData: Boolean
        get() = hasSnapshot &&
            (isFromCache || isSyncFailed || payloadAbsent || isNetworkChanged)

    /**
     * Why the data on screen is not fresh.
     *
     * The order matters: the network change is the most specific reason, and
     * it comes before the generic synchronisation failure.
     */
    val staleDataReason: StaleDataReason?
        get() = when {
            isNetworkChanged -> StaleDataReason.NETWORK_CHANGED
            isFromCache -> StaleDataReason.CACHE
            payloadAbsent -> StaleDataReason.INCOMPLETE_REPLY
            isSyncFailed -> StaleDataReason.SYNC_FAILED
            else -> null
        }

    /**
     * The devices matching both the search query and the filter.
     *
     * The query is matched case-insensitively against the name, the IP address
     * and the MAC address. The filter keeps the devices that are associated
     * with a radio ([DeviceFilter.WIFI]) or that hold a lease on a wired
     * interface ([DeviceFilter.WIRED]).
     */
    val filteredDevices: List<RouterDevice>
        get() {
            val needle = query.trim()
            return devices.filter { device ->
                matchesQuery(device, needle) && matchesFilter(device, filter)
            }
        }

    private fun matchesQuery(device: RouterDevice, needle: String): Boolean {
        if (needle.isEmpty()) return true
        return device.name.contains(needle, ignoreCase = true) ||
            device.ip.contains(needle, ignoreCase = true) ||
            device.mac.contains(needle, ignoreCase = true)
    }

    private fun matchesFilter(device: RouterDevice, filter: DeviceFilter): Boolean =
        when (filter) {
            DeviceFilter.ALL -> true
            DeviceFilter.WIFI -> device.presence == DevicePresence.ACTIVE
            DeviceFilter.WIRED -> device.presence == DevicePresence.CABLED
        }
}

/**
 * Drives the device list screen.
 *
 * The ViewModel only reads the router through the repository; it never opens a
 * session and never touches the Android framework.
 *
 * On creation it first adopts the snapshot the repository already holds (the
 * one restored from disk right after a successful login), then refreshes it.
 */
class DevicesViewModel(
    private val repository: RouterRepository,
    private val refreshCoordinator: RefreshCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        seedFromRepository()
        observeSessionLoss()
        observeReads()
    }

    /**
     * Starts the automatic refresh.
     *
     * The method is **idempotent**: the screen may call it again after a
     * rotation or a return to the foreground, and the loop must not be
     * duplicated. The loop itself belongs to the coordinator, which is
     * shared with the dashboard.
     */
    fun startPolling() {
        refreshCoordinator.startPolling()
    }

    /** Stops the automatic refresh. */
    fun stopPolling() {
        refreshCoordinator.stopPolling()
    }

    /**
     * Mirrors the shared read state.
     *
     * The device list does not own the reads: the coordinator does, and the
     * dashboard sees the same ones. The screen therefore **observes** the
     * result instead of acting on its own.
     */
    private fun observeReads() {
        viewModelScope.launch {
            repository.readState.collect { readState ->
                if (readState.lastError != null) {
                    // The read failed: the data on screen is the last known
                    // one, and it is no longer fresh.
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isSyncFailed = _state.value.hasSnapshot,
                        errorMessage = readState.lastError.userMessage(),
                    )
                    return@collect
                }

                val snapshot = readState.snapshot ?: return@collect
                if (snapshot.payloadAbsent) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        payloadAbsent = true,
                    )
                    return@collect
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    devices = snapshot.devices,
                    lastRefreshMillis = snapshot.capturedAtMillis,
                    payloadAbsent = false,
                    hasSnapshot = true,
                    isFromCache = repository.state.value.isShowingCachedSnapshot,
                    isSyncFailed = false,
                    isNetworkChanged = false,
                    errorMessage = null,
                )
            }
        }
    }

    /**
     * Purges the displayed devices as soon as the session is no longer valid.
     *
     * The navigation also leaves the screen, but the two are independent: the
     * view model must never keep authenticated devices in its own state,
     * even for the short time the navigation takes to react.
     */
    private fun observeSessionLoss() {
        viewModelScope.launch {
            repository.state.collect { state ->
                // A network change **keeps** the data: the router may still
                // know the session, and the user may come back to the right
                // network. Only a session that is really gone purges it.
                val keepsData = state.connection is ConnectionState.Connected ||
                    state.connection is ConnectionState.Cooldown ||
                    state.connection is ConnectionState.NetworkChanged

                if (!keepsData) {
                    _state.value = _state.value.copy(
                        devices = emptyList(),
                        lastRefreshMillis = null,
                        isFromCache = false,
                        isSyncFailed = false,
                        payloadAbsent = false,
                        hasSnapshot = false,
                        isLoading = false,
                    )
                } else if (state.connection is ConnectionState.NetworkChanged) {
                    // The network changed: the data on screen is the last
                    // known state, not a fresh reading.
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isNetworkChanged = true,
                    )
                }
            }
        }
    }

    /** Adopts the snapshot the repository restored after authentication. */
    private fun seedFromRepository() {
        val repositoryState = repository.state.value
        if (!repositoryState.isConnected) return
        val snapshot = repositoryState.snapshot ?: return

        _state.value = _state.value.copy(
            devices = snapshot.devices,
            lastRefreshMillis = snapshot.capturedAtMillis,
            payloadAbsent = repositoryState.payloadAbsent,
            isFromCache = repositoryState.isShowingCachedSnapshot,
            hasSnapshot = true,
        )
    }

    /** Sets the search text. */
    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /** Sets the family of devices to show. */
    fun setFilter(filter: DeviceFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    /**
     * Polls the router.
     *
     * The first poll shows a full-screen spinner; the following ones keep the
     * devices already on screen while the refresh runs.
     */
    fun refresh() {
        val hadSnapshot = _state.value.hasSnapshot

        _state.value = _state.value.copy(
            isLoading = !hadSnapshot,
            errorMessage = null
        )

        viewModelScope.launch(repository.networkDispatcher) {
            repository.refresh().onFailure { error ->
                val hasSnapshot = _state.value.hasSnapshot
                _state.value = _state.value.copy(
                    isLoading = false,
                    isSyncFailed = hasSnapshot,
                    errorMessage = (error as? RouterUnavailableException)?.userMessage()
                        ?: error.message
                        ?: "Impossible de joindre le routeur."
                )
            }
        }
    }
}
