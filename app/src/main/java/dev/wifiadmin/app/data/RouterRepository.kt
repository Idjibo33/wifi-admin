package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the connection state and the last device snapshot.
 *
 * The repository is the only place that talks to a [RouterAdapter]. The
 * ViewModels observe it and call its methods; they never see the protocol.
 *
 * The protocol client does **blocking** I/O. Every call is therefore moved to
 * [ioDispatcher]: doing it on the main thread would make Android raise
 * `NetworkOnMainThreadException`, which would be indistinguishable from a
 * genuine network failure.
 */
class RouterRepository(
    private val adapter: RouterAdapter = ZltRouterAdapter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(RouterUiState())
    val state: StateFlow<RouterUiState> = _state.asStateFlow()

    /** The adapter in use, exposed so the UI can show which router it talks to. */
    val routerDisplayName: String get() = adapter.displayName

    /**
     * The dispatcher on which the blocking network calls run.
     *
     * Exposed so the ViewModels can launch their own coroutines on it: the
     * whole call then happens on one dispatcher, which keeps the tests
     * deterministic.
     */
    val networkDispatcher: CoroutineDispatcher get() = ioDispatcher

    /**
     * Opens a session.
     *
     * @return true when the router accepted the credentials.
     */
    suspend fun connect(host: String, username: String, password: String): Boolean =
        mutex.withLock {
            _state.value = _state.value.copy(
                connection = ConnectionState.Connecting,
                error = null
            )
            try {
                val accepted = withContext(ioDispatcher) {
                    adapter.connect(host.trim(), username.trim(), password)
                }
                _state.value = if (accepted) {
                    _state.value.copy(
                        connection = ConnectionState.Connected,
                        host = host.trim(),
                        username = username.trim(),
                        error = null
                    )
                } else {
                    _state.value.copy(
                        connection = ConnectionState.Error(
                            RouterUnavailableException.AuthenticationFailed(null)
                        )
                    )
                }
                accepted
            } catch (e: RouterUnavailableException) {
                _state.value = _state.value.copy(
                    connection = ConnectionState.Error(e),
                    error = e
                )
                false
            }
        }

    /** Reads the devices and updates the snapshot. */
    suspend fun refresh(): Result<DeviceSnapshot> = mutex.withLock {
        try {
            val snapshot = withContext(ioDispatcher) { adapter.fetchDevices() }
            _state.value = _state.value.copy(
                snapshot = snapshot,
                lastRefreshMillis = snapshot.capturedAtMillis,
                isRefreshing = false,
                error = null
            )
            Result.success(snapshot)
        } catch (e: RouterUnavailableException) {
            _state.value = _state.value.copy(
                isRefreshing = false,
                error = e,
                // An authentication failure means the session is gone: the user
                // has to sign in again.
                connection = if (e is RouterUnavailableException.AuthenticationFailed) {
                    ConnectionState.Error(e)
                } else {
                    _state.value.connection
                }
            )
            Result.failure(e)
        }
    }

    /** Marks the beginning of a refresh, so the UI can show a spinner. */
    fun setRefreshing(refreshing: Boolean) {
        _state.value = _state.value.copy(isRefreshing = refreshing)
    }

    /** Drops the session and returns to the login screen. */
    fun disconnect() {
        adapter.disconnect()
        _state.value = RouterUiState(host = _state.value.host, username = _state.value.username)
    }
}

/** The connection state of the app. */
sealed class ConnectionState {
    /** Not signed in yet. */
    data object Disconnected : ConnectionState()

    /** A connection attempt is in progress. */
    data object Connecting : ConnectionState()

    /** Signed in. */
    data object Connected : ConnectionState()

    /** The last attempt failed. */
    data class Error(val exception: RouterUnavailableException) : ConnectionState()
}

/** Everything the UI needs to render. */
data class RouterUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val snapshot: DeviceSnapshot? = null,
    val isRefreshing: Boolean = false,
    val lastRefreshMillis: Long? = null,
    val host: String = "",
    val username: String = "",
    val error: RouterUnavailableException? = null,
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected
}
