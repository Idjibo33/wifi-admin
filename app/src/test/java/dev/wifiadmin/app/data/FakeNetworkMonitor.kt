package dev.wifiadmin.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [NetworkMonitor] that never touches the Android framework.
 *
 * The tests drive it directly: [emit] publishes a state, the way a platform
 * callback would. No radio, no timer, no network.
 */
class FakeNetworkMonitor(
    initial: LocalNetworkStatus = LocalNetworkStatus.NoWifi,
) : NetworkMonitor {

    private val _status = MutableStateFlow(initial)
    override fun observeStatus(): Flow<LocalNetworkStatus> = _status.asStateFlow()

    override fun currentStatus(): LocalNetworkStatus = _status.value

    /** Publishes a new state, as a platform callback would. */
    fun emit(status: LocalNetworkStatus) {
        _status.value = status
    }
}
