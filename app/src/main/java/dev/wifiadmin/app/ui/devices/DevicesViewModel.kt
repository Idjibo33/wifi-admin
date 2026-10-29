package dev.wifiadmin.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.RouterRepository
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
    val errorMessage: String? = null
) {

    /** True when the screen has nothing to show but a spinner. */
    val isFirstLoad: Boolean get() = isLoading && devices.isEmpty()

    /** True when a failure happened and there is no device to fall back on. */
    val isFatalError: Boolean get() = errorMessage != null && devices.isEmpty()

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
 */
class DevicesViewModel(
    private val repository: RouterRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        refresh()
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
        val hadDevices = _state.value.devices.isNotEmpty()

        _state.value = _state.value.copy(
            isLoading = !hadDevices,
            errorMessage = null
        )

        viewModelScope.launch(repository.networkDispatcher) {
            val result = repository.refresh()
            result
                .onSuccess { snapshot ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        devices = snapshot.devices,
                        lastRefreshMillis = snapshot.capturedAtMillis,
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Impossible de joindre le routeur."
                    )
                }
        }
    }
}
