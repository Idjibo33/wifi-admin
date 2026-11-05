package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val clock: AppClock = SystemAppClock(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val freshness: ReadFreshnessPolicy = ReadFreshnessPolicy.Default,
    private val networkMonitor: NetworkMonitor? = null,
) {

    private val mutex = Mutex()

    /**
     * The read coordinator.
     *
     * It owns the **single flight**: a second caller arriving while a read
     * is running awaits that very read instead of starting a second one.
     */
    private val coordinator = ReadCoordinator(
        adapter = adapter,
        clock = clock,
        scope = scope,
        freshness = freshness,
        ioDispatcher = ioDispatcher,
    )

    /** The state of the reads, shared by every consumer. */
    val readState: StateFlow<ReadState> get() = coordinator.state

    /**
     * Bumped on every disconnect and expiry. A result that started before the
     * change is discarded instead of resurrecting a dead session.
     */
    private var generation = 0

    /**
     * The moment, on the monotonic clock, until which the router refuses
     * logins.
     *
     * The automatic refresh waits until then: asking a router that is
     * already refusing logins is what turns a temporary refusal into a
     * lockout.
     */
    private var cooldownUntilMonotonicMillis: Long? = null

    /**
     * The moment of the last **successful** read, on the monotonic clock.
     *
     * `null` when no read ever succeeded. A failed read does not make the
     * data fresh: the timestamp is only updated on success.
     */
    private var lastSuccessfulReadAtMillis: Long? = null

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
     *
     * The read is **deduplicated**: a caller that arrives while another read
     * is running joins it instead of starting a second one. The dashboard and
     * the device list therefore produce a single network request, and a manual
     * refresh never doubles a read that is already in flight.
     */
    suspend fun refresh(): Result<DeviceSnapshot> = performRead(force = true)

    /**
     * Reads the devices only when the last successful read is older than
     * [maxAgeMillis].
     *
     * This is what the automatic polling uses. Two screens polling at the
     * same time produce **one** read, and a read that just succeeded is not
     * repeated.
     *
     * @param maxAgeMillis how old the last successful read may be.
     */
    suspend fun refreshIfStale(maxAgeMillis: Long): Result<DeviceSnapshot> =
        performRead(force = false, maxAgeMillis = maxAgeMillis)

    /**
     * Whether the last successful read is old enough to be worth repeating.
     */
    fun isStale(maxAgeMillis: Long): Boolean {
        val last = lastSuccessfulReadAtMillis ?: return true
        return clock.monotonicMillis() - last >= maxAgeMillis
    }

    /**
     * Forgets the freshness of the data.
     *
     * Called when the network changes: the data read on the previous network
     * cannot be considered fresh for the new one.
     */
    fun invalidateFreshness() {
        lastSuccessfulReadAtMillis = null
        coordinator.invalidate()
    }

    /**
     * Runs the read, or joins the one already running.
     *
     * The read happens **outside** the session mutex: holding the lock for
     * the whole network call would serialise the callers instead of sharing
     * their result, and the second screen would then perform a second read.
     * The [ReadCoordinator] is what makes the callers share.
     *
     * The generation is captured before the read and checked after it: a
     * result produced for a session that has since died is discarded.
     */
    private suspend fun performRead(
        force: Boolean,
        maxAgeMillis: Long = 0L,
    ): Result<DeviceSnapshot> {
        if (!force && !isStale(maxAgeMillis)) {
            // Fresh enough: no read, and the caller keeps what it has.
            return Result.success(
                _state.value.snapshot ?: DeviceSnapshot(devices = emptyList())
            )
        }

        when (_state.value.connection) {
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.SessionExpired -> return Result.failure(
                RouterUnavailableException.NotConnected()
            )

            is ConnectionState.Error -> return Result.failure(
                RouterUnavailableException.NotConnected()
            )

            // The phone is on another network: the router is not reachable,
            // so no read is attempted. The session is kept, and the last
            // known devices stay on screen.
            is ConnectionState.NetworkChanged -> return Result.failure(
                RouterUnavailableException.NotConnected()
            )

            ConnectionState.Connected,
            is ConnectionState.Cooldown -> Unit
        }

        val startGeneration = generation
        val context = runCatching {
            SnapshotContext.of(_state.value.host, _state.value.username)
        }.getOrNull()

        val outcome = coordinator.read(force = true)

        if (startGeneration != generation) {
            // A session changed while the read was running: the result
            // belongs to a dead session and must not touch the live state.
            return Result.failure(RouterUnavailableException.NotConnected())
        }

        return when (outcome) {
            is ReadOutcome.Success -> {
                val snapshot = outcome.snapshot
                if (context != null && !snapshot.payloadAbsent) {
                    withContext(ioDispatcher) {
                        snapshotStore?.write(snapshot, context)
                    }
                }

                if (snapshot.payloadAbsent) {
                    // The router answered without a device list. That is not
                    // an empty network, and it must never overwrite a good
                    // snapshot: keep the previous devices (live or cached)
                    // and flag the incomplete reply. Nothing is written to
                    // disk.
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
                    return Result.success(snapshot)
                }

                mutex.withLock {
                    lastSuccessfulReadAtMillis = clock.monotonicMillis()
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
            }

            is ReadOutcome.Failure -> applyFailure(outcome.error, startGeneration)
        }
    }

    /**
     * Applies a failed read to the state.
     *
     * The `when` is **exhaustive** over the sealed hierarchy: adding a new
     * failure forces an explicit decision, and a network failure can never
     * be turned into a session expiry by accident.
     */
    private fun applyFailure(
        error: RouterUnavailableException,
        startGeneration: Int,
    ): Result<DeviceSnapshot> {
        if (startGeneration != generation) {
            // The session died while the read was running: the error belongs
            // to a dead session and must not touch the live state.
            return Result.failure(RouterUnavailableException.NotConnected())
        }

        when (error) {
            // A **confirmed** expiry: the only case that signs the user out.
            is RouterUnavailableException.SessionExpired -> expireSession(error)

            // The router refuses logins for a while: the session is kept.
            is RouterUnavailableException.Cooldown -> {
                cooldownUntilMonotonicMillis = error.retryAfterSeconds?.let {
                    clock.monotonicMillis() + it * 1_000L
                }
                _state.value = _state.value.copy(
                    connection = ConnectionState.Cooldown(error.retryAfterSeconds),
                    isRefreshing = false,
                    error = error,
                )
            }

            // Everything else keeps the session: a timeout, an unreachable
            // router or a malformed reply are **not** an expiry.
            is RouterUnavailableException.Unreachable,
            is RouterUnavailableException.Timeout,
            is RouterUnavailableException.AuthenticationFailed,
            is RouterUnavailableException.UnexpectedResponse,
            is RouterUnavailableException.NotConnected,
            -> _state.value = _state.value.copy(
                isRefreshing = false,
                error = error,
            )
        }

        return Result.failure(error)
    }

    /**
     * How long the router still refuses logins, or null.
     *
     * The automatic refresh waits exactly as long as the router asked: a
     * router that is already refusing logins must not be asked again.
     */
    fun cooldownRemainingMillis(): Long? {
        val until = cooldownUntilMonotonicMillis ?: return null
        val remaining = until - clock.monotonicMillis()
        return if (remaining <= 0L) {
            cooldownUntilMonotonicMillis = null
            null
        } else {
            remaining
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
        // The state is reset **before** the cleanup, and without waiting for
        // the session lock: a read that is in flight holds the lock for the
        // whole network call, and the user must not wait for it — the
        // timeout can be several seconds.
        //
        // The generation is bumped first, so a read that comes back after
        // this point is discarded and cannot revive the session.
        val previous = _state.value
        generation++
        _state.value = RouterUiState(host = previous.host, username = previous.username)

        withContext(ioDispatcher) {
            adapter.disconnect()
            snapshotStore?.clear()
        }
    }

    // ---- Internals ----

    /**
     * Watches the network the session is bound to.
     *
     * The reads are **suspended**, not cancelled: the session is not
     * expired, and the user may come back to the right network. The last
     * known devices stay on screen, flagged as not fresh.
     *
     * The returned job can be cancelled to stop the observation.
     */
    fun observeNetworkChanges(): Job {
        val monitor = networkMonitor ?: return Job().also { it.complete() }
        return scope.launch {
            monitor.observeStatus().collect { status ->
                onNetworkStatus(status)
            }
        }
    }

    /**
     * Observes the network changes in a scope of the caller.
     *
     * The application uses [observeNetworkChanges], which observes in the
     * repository's own scope. This variant exists for the tests, which need
     * the observation to live in the test's scope.
     */
    fun observeNetworkChanges(scope: CoroutineScope): Job {
        val monitor = networkMonitor ?: return Job().also { it.complete() }
        return scope.launch {
            monitor.observeStatus().collect { status ->
                onNetworkStatus(status)
            }
        }
    }

    /**
     * Applies a network change to the state.
     *
     * The rule is deliberately narrow: a network change never signs the
     * user out. It only suspends the reads and says what happened.
     */
    private fun onNetworkStatus(status: LocalNetworkStatus) {
        val current = _state.value
        val sessionOpen = current.connection is ConnectionState.Connected ||
            current.connection is ConnectionState.Cooldown ||
            current.connection is ConnectionState.NetworkChanged
        if (!sessionOpen) return

        val assessment = assessNetwork(status, current.host)
        when (assessment.confidence) {
            NetworkConfidence.LOCAL_DEFAULT -> {
                // Back on the right network: the reads resume.
                if (current.connection is ConnectionState.NetworkChanged) {
                    _state.value = current.copy(
                        connection = ConnectionState.Connected,
                        error = null,
                    )
                }
            }

            NetworkConfidence.DIFFERENT,
            NetworkConfidence.ABSENT,
            -> {
                if (current.connection !is ConnectionState.NetworkChanged) {
                    // The data read on the previous network is not fresh any
                    // more, but it is kept: it is still the last known state
                    // of the router.
                    invalidateFreshness()
                    _state.value = current.copy(
                        connection = ConnectionState.NetworkChanged(
                            when (status) {
                                is LocalNetworkStatus.LocalWifi -> status.identity.gateway
                                is LocalNetworkStatus.DifferentNetwork -> status.identity.gateway
                                LocalNetworkStatus.NoWifi,
                                LocalNetworkStatus.NotOnWifi,
                                -> null
                            },
                        ),
                    )
                }
            }

            NetworkConfidence.UNKNOWN -> Unit
        }
    }

    /**
     * Reads the stored snapshot and keeps it only when it belongs to [context].
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

    /**
     * The phone left the network the session was opened on.
     *
     * The session is **not** expired: the router may still know it. The
     * reads are suspended until the phone is back on the right network.
     *
     * This state never triggers a navigation and never signs the user out:
     * a network failure is not an expiry, and Mission 08 says so
     * explicitly.
     */
    data class NetworkChanged(val currentGateway: String?) : ConnectionState()

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
