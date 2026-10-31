package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CancellationException
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
 *
 * ## Session lifecycle
 *
 * A session is opened by [connect] and can only be lost by [disconnect] or by
 * a **confirmed** expiry during [refresh]: the adapter reports
 * [RouterUnavailableException.SessionExpired] only when an already open session
 * could not be renewed. Network, timeout, protocol and cooldown failures keep
 * the session state untouched, so they can never sign the user out by mistake.
 *
 * ## Snapshot persistence
 *
 * When a [SnapshotStorage] is supplied, the last complete read is saved to
 * disk and restored after a successful [connect] for the **same** router and
 * account. The store is optional: without it the repository behaves exactly
 * as before.
 */
class RouterRepository(
    private val adapter: RouterAdapter = ZltRouterAdapter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val snapshotStore: SnapshotStorage? = null,
) {

    private val mutex = Mutex()

    /**
     * Bumped on every disconnect and expiry. A result that started before the
     * change is discarded instead of resurrecting a dead session.
     */
    private var generation = 0

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
     * Opens a session and restores the stored snapshot of the same router and
     * account.
     *
     * The stored snapshot is read **only after** the credentials are accepted:
     * no private data is exposed before authentication. A snapshot written for
     * another host or account is deleted instead of shown.
     *
     * @return true when the router accepted the credentials.
     */
    suspend fun connect(host: String, username: String, password: String): Boolean =
        mutex.withLock {
            val normalizedHost = host.trim()
            val normalizedUsername = username.trim()
            val startGeneration = ++generation

            _state.value = _state.value.copy(
                connection = ConnectionState.Connecting,
                // A new login must not leave the previous session's devices
                // visible, even briefly. The cache is restored only after the
                // router accepts the credentials.
                snapshot = null,
                lastRefreshMillis = null,
                snapshotOrigin = SnapshotOrigin.NONE,
                payloadAbsent = false,
                isRefreshing = false,
                error = null
            )
            try {
                val accepted = withContext(ioDispatcher) {
                    adapter.connect(normalizedHost, normalizedUsername, password)
                }

                if (startGeneration != generation) return@withLock false

                if (!accepted) {
                    val failure = RouterUnavailableException.AuthenticationFailed(null)
                    _state.value = _state.value.copy(
                        connection = ConnectionState.Error(failure),
                        error = failure
                    )
                    return@withLock false
                }

                val context = SnapshotContext.of(normalizedHost, normalizedUsername)
                val restored = restore(context)

                if (startGeneration != generation) {
                    adapter.disconnect()
                    return@withLock false
                }

                _state.value = _state.value.copy(
                    connection = ConnectionState.Connected,
                    host = normalizedHost,
                    username = normalizedUsername,
                    snapshot = restored?.snapshot,
                    lastRefreshMillis = restored?.snapshot?.capturedAtMillis,
                    snapshotOrigin = if (restored != null) {
                        SnapshotOrigin.CACHE
                    } else {
                        SnapshotOrigin.NONE
                    },
                    isRefreshing = false,
                    error = null,
                    payloadAbsent = false,
                )
                true
            } catch (e: RouterUnavailableException) {
                if (startGeneration == generation) {
                    _state.value = _state.value.copy(
                        connection = ConnectionState.Error(e),
                        isRefreshing = false,
                        error = e
                    )
                }
                false
            }
        }

    /**
     * Reads the devices and updates the snapshot.
     *
     * A successful read replaces the snapshot and, unless the router delivered
     * no device list at all, is written to the store. A failure keeps the
     * displayed snapshot; only a confirmed session expiry changes the
     * connection state to [ConnectionState.SessionExpired].
     */
    suspend fun refresh(): Result<DeviceSnapshot> = mutex.withLock {
        when (_state.value.connection) {
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.SessionExpired -> return@withLock Result.failure(
                RouterUnavailableException.NotConnected()
            )

            is ConnectionState.Error -> return@withLock Result.failure(
                RouterUnavailableException.NotConnected()
            )

            ConnectionState.Connected,
            is ConnectionState.Cooldown -> Unit
        }

        val startGeneration = generation
        val context = runCatching {
            SnapshotContext.of(_state.value.host, _state.value.username)
        }.getOrNull()

        try {
            val snapshot = withContext(ioDispatcher) { adapter.fetchDevices() }

            if (startGeneration != generation) {
                return@withLock Result.failure(RouterUnavailableException.NotConnected())
            }

            if (context != null && !snapshot.payloadAbsent) {
                withContext(ioDispatcher) {
                    snapshotStore?.write(snapshot, context)
                }
            }

            if (snapshot.payloadAbsent) {
                // The router answered without a device list. That is not an
                // empty network, and it must never overwrite a good snapshot:
                // keep the previous devices (live or cached) and flag the
                // incomplete reply. Nothing is written to disk.
                val previous = _state.value.snapshot
                _state.value = _state.value.copy(
                    snapshot = previous ?: snapshot,
                    lastRefreshMillis = previous?.capturedAtMillis
                        ?: _state.value.lastRefreshMillis,
                    payloadAbsent = true,
                    isRefreshing = false,
                    error = null,
                    connection = ConnectionState.Connected,
                )
                return@withLock Result.success(snapshot)
            }

            _state.value = _state.value.copy(
                snapshot = snapshot,
                lastRefreshMillis = snapshot.capturedAtMillis,
                isRefreshing = false,
                error = null,
                connection = ConnectionState.Connected,
                payloadAbsent = false,
                snapshotOrigin = SnapshotOrigin.LIVE,
            )
            Result.success(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouterUnavailableException.SessionExpired) {
            if (startGeneration == generation) expireSession(e)
            Result.failure(e)
        } catch (e: RouterUnavailableException.Cooldown) {
            if (startGeneration == generation) {
                // The session cannot be used, but this is a temporary refusal,
                // not an expiry: no navigation, no automatic retry. The
                // adapter keeps its settings in memory, so a later manual
                // refresh can reopen the session without asking the password
                // again.
                _state.value = _state.value.copy(
                    connection = ConnectionState.Cooldown(e.retryAfterSeconds),
                    isRefreshing = false,
                    error = e,
                )
            }
            Result.failure(e)
        } catch (e: RouterUnavailableException) {
            if (startGeneration == generation) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    error = e,
                )
            }
            Result.failure(e)
        }
    }

    /** Marks the beginning of a refresh, so the UI can show a spinner. */
    fun setRefreshing(refreshing: Boolean) {
        _state.value = _state.value.copy(isRefreshing = refreshing)
    }

    /**
     * Drops the session and deletes the stored snapshot.
     *
     * The state is reset **before** the blocking cleanup, so the navigation can
     * leave the authenticated screens immediately. The snapshot on disk is
     * deleted because a voluntary logout must not leave private device data
     * behind.
     */
    suspend fun disconnect() {
        mutex.withLock {
            generation++
            val previous = _state.value
            _state.value = RouterUiState(host = previous.host, username = previous.username)
            withContext(ioDispatcher) {
                adapter.disconnect()
                snapshotStore?.clear()
            }
        }
    }

    // ---- Internals ----

    /**
     * Reads the stored snapshot and keeps it only when it belongs to [context].
     *
     * A snapshot from another router or account is deleted: showing it would
     * describe a network the user is not connected to.
     */
    private suspend fun restore(context: SnapshotContext): SnapshotRecord? {
        val store = snapshotStore ?: return null
        return withContext(ioDispatcher) {
            when (val read = store.read()) {
                is SnapshotRead.Found ->
                    if (read.record.context == context) {
                        read.record
                    } else {
                        store.clear()
                        null
                    }

                SnapshotRead.Missing,
                is SnapshotRead.Rejected -> null
            }
        }
    }

    /**
     * A confirmed expiry: drop the in-memory snapshot so no authenticated data
     * is visible after the return to the login screen. The file is **kept** —
     * the user may sign in again on the same router and account, and the next
     * successful [connect] restores it.
     */
    private fun expireSession(exception: RouterUnavailableException.SessionExpired) {
        generation++
        adapter.disconnect()
        val previous = _state.value
        _state.value = RouterUiState(
            connection = ConnectionState.SessionExpired,
            host = previous.host,
            username = previous.username,
            error = exception,
        )
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

    /** A confirmed expiry: the session could not be renewed. */
    data object SessionExpired : ConnectionState()

    /** The router temporarily refuses to open a session (180 s penalty). */
    data class Cooldown(val retryAfterSeconds: Int?) : ConnectionState()

    /** The last connection attempt failed. */
    data class Error(val exception: RouterUnavailableException) : ConnectionState()
}

/** Where the displayed snapshot comes from. */
enum class SnapshotOrigin {
    /** No snapshot is displayed. */
    NONE,

    /** The snapshot was read from the router during this session. */
    LIVE,

    /** The snapshot was restored from disk after authentication. */
    CACHE,
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
    val snapshotOrigin: SnapshotOrigin = SnapshotOrigin.NONE,
    /**
     * True when the last read returned no device list.
     *
     * The previous snapshot, when there is one, is kept on screen: an absent
     * payload is not an empty network and must not erase good data.
     */
    val payloadAbsent: Boolean = false,
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    /** True when the displayed snapshot comes from disk, not from this session. */
    val isShowingCachedSnapshot: Boolean
        get() = snapshotOrigin == SnapshotOrigin.CACHE
}
