package dev.wifiadmin.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.DeviceIdentity
import dev.wifiadmin.app.data.DeviceListPreferencesStore
import dev.wifiadmin.app.data.DeviceMetadata
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.ui.components.StaleDataReason
import dev.wifiadmin.app.ui.components.userMessage
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of the device list.
 *
 * [devices] is the raw list from the last poll; [rows] is what the screen
 * shows: the router's devices, joined with the local metadata.
 */
data class DevicesUiState(
    /** True while the very first snapshot is being loaded. */
    val isLoading: Boolean = false,
    /** Every device the router reported during the last successful poll. */
    val devices: List<RouterDevice> = emptyList(),
    /** The text typed in the search field. */
    val query: String = "",
    /** The band filter to keep. */
    val filter: DeviceBandFilter = DeviceBandFilter.ALL,
    /** The order the list is shown in. */
    val sort: DeviceSort = DeviceSort.NAME,
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
    /**
     * The local metadata of the devices, indexed by their identity.
     *
     * They are **local**: the router knows nothing about them.
     */
    val metadata: Map<String, DeviceMetadata> = emptyMap(),
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
     * The devices joined with their local metadata.
     */
    val rows: List<DeviceRow>
        get() = devices.map { device ->
            val key = DeviceIdentity.keyOf(device)
            DeviceRow(device = device, metadata = key?.let { metadata[it] })
        }

    /**
     * The devices matching both the search query and the band filter.
     *
     * The filtering is **purely local**: the devices are already in memory,
     * and no read is ever triggered by it.
     */
    val filteredRows: List<DeviceRow>
        get() {
            val needle = query.trim()
            return rows
                .filter { row ->
                    DeviceFiltering.matchesQuery(row, needle) &&
                        DeviceFiltering.matchesBand(row.device, filter)
                }
                .sortedWith(DeviceOrdering.comparator(sort))
        }

    /**
     * The devices matching the search and the filter, for the callers that
     * only need the router's devices.
     */
    val filteredDevices: List<RouterDevice>
        get() = filteredRows.map { it.device }

    /**
     * Why the list is empty.
     *
     * The reason matters: "no device at all" and "no device matching the
     * current search" call for two different actions, and a single message
     * would send the user the wrong way.
     *
     * It is null when the list is **not** empty: the reason only describes
     * an empty list, and a list that shows devices has no reason to be
     * empty.
     */
    val emptyReason: DeviceEmptyReason?
        get() {
            if (filteredRows.isNotEmpty()) return null
            return when {
                payloadAbsent -> DeviceEmptyReason.NO_REPLY
                devices.isEmpty() && isFromCache -> DeviceEmptyReason.NO_DEVICE_CACHED
                devices.isEmpty() -> DeviceEmptyReason.NO_DEVICE
                query.isNotBlank() && filter != DeviceBandFilter.ALL ->
                    DeviceEmptyReason.NO_MATCH_BOTH
                query.isNotBlank() -> DeviceEmptyReason.NO_MATCH_QUERY
                filter != DeviceBandFilter.ALL -> DeviceEmptyReason.NO_MATCH_FILTER
                else -> null
            }
        }
}

/**
 * Why the device list shows nothing.
 */
enum class DeviceEmptyReason {
    /** The router answered without any device list. */
    NO_REPLY,

    /** The router knows no device, and the data comes from the cache. */
    NO_DEVICE_CACHED,

    /** The router knows no device. */
    NO_DEVICE,

    /** No device matches the search. */
    NO_MATCH_QUERY,

    /** No device is on the selected band. */
    NO_MATCH_FILTER,

    /** No device matches the search **and** the band. */
    NO_MATCH_BOTH,
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
    /**
     * The display preferences of the list.
     *
     * They are optional: without them the list keeps its default order and
     * filter, and never writes anything.
     */
    private val preferences: DeviceListPreferencesStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        seedFromRepository()
        observeSessionLoss()
        observeReads()
        loadMetadata()
        observePreferences()
    }

    /**
     * Adopts the display preferences that were saved.
     *
     * They are read **once**, when the screen is created. An unknown value
     * falls back on the default: a preference written by a newer version
     * must never break the list.
     */
    private fun observePreferences() {
        val store = preferences ?: return
        viewModelScope.launch {
            store.sortName.collect { name ->
                val sort = DeviceSort.entries.firstOrNull { it.name == name }
                if (sort != null && sort != _state.value.sort) {
                    _state.value = _state.value.copy(sort = sort)
                }
            }
        }
        viewModelScope.launch {
            store.filterName.collect { name ->
                val filter = DeviceBandFilter.entries.firstOrNull { it.name == name }
                if (filter != null && filter != _state.value.filter) {
                    _state.value = _state.value.copy(filter = filter)
                }
            }
        }
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
                        // The metadata are local, but they describe the
                        // devices of an authenticated session: they leave
                        // the memory with the devices themselves.
                        metadata = emptyMap(),
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

    /** Sets the band filter to keep. */
    fun setFilter(filter: DeviceBandFilter) {
        _state.value = _state.value.copy(filter = filter)
        val store = preferences ?: return
        viewModelScope.launch { store.saveFilter(filter.name) }
    }

    /** Sets the order the list is shown in. */
    fun setSort(sort: DeviceSort) {
        _state.value = _state.value.copy(sort = sort)
        val store = preferences ?: return
        viewModelScope.launch { store.saveSort(sort.name) }
    }

    /**
     * Gives a device a custom name.
     *
     * The name is **local**: the router is never told about it, and the
     * router's own name stays visible next to it. The rename does not
     * trigger any read: it only writes on the disk of the phone.
     *
     * @param key the identity of the device — the canonical MAC address.
     * @param name the name the user typed.
     * @return true when the name was stored. It is false when the device has
     *   no usable identity: the application would have no way to find it
     *   again, and pretending otherwise would attach the name to the wrong
     *   device.
     */
    fun renameDevice(key: String, name: String) {
        viewModelScope.launch {
            val stored = repository.renameDevice(key, name)
            if (stored) {
                // The state is updated from the store, so what is shown is
                // exactly what was written.
                _state.value = _state.value.copy(metadata = repository.deviceMetadata())
            } else {
                _state.value = _state.value.copy(
                    errorMessage = "Cet appareil n'a pas d'identifiant stable : " +
                        "impossible de lui attribuer un nom.",
                )
            }
        }
    }

    /**
     * Removes the custom name of a device.
     *
     * The router's own name is shown again.
     */
    fun clearDeviceName(key: String) {
        viewModelScope.launch {
            repository.clearDeviceName(key)
            _state.value = _state.value.copy(metadata = repository.deviceMetadata())
        }
    }

    /**
     * Adopts the local metadata of the devices.
     *
     * It is called when the screen opens and after every rename.
     */
    private fun loadMetadata() {
        _state.value = _state.value.copy(metadata = repository.deviceMetadata())
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
