package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The outcome of a read.
 *
 * The failure keeps its **exact type**: the repository must be able to tell
 * a confirmed expiry from a timeout, from an unreachable router and from a
 * malformed reply. A failure is never flattened into a generic error.
 */
sealed interface ReadOutcome {
    data class Success(val snapshot: DeviceSnapshot) : ReadOutcome
    data class Failure(val error: RouterUnavailableException) : ReadOutcome
}

/**
 * How long a successful read stays **fresh enough to be reused**.
 *
 * A read that succeeded less than this ago is not repeated: the data is
 * still current, and asking the router again would only double the load on
 * a device that is already fragile.
 *
 * The decision is a **pure function** of the age of the read. It holds no
 * state, so it is tested without a coroutine and without waiting.
 */
fun interface ReadFreshnessPolicy {

    /**
     * @param ageMillis how long ago the read succeeded, on the monotonic
     *   clock.
     * @return true when the read may be reused instead of redone.
     */
    fun isFresh(ageMillis: Long): Boolean

    companion object {
        /** The default window: the polling period itself. */
        val Default = ReadFreshnessPolicy { ageMillis -> ageMillis < 30_000L }
    }
}

/**
 * Coordinates the reads so that **one period means one read**.
 *
 * ## Why this class exists
 *
 * The dashboard and the device list need the same data. Without a
 * coordinator, each of them triggers its own read: two screens, two
 * requests, twice the load on the router.
 *
 * ## What it guarantees
 *
 * - **At most one read is in flight.** A caller that arrives while a read
 *   is running **awaits that very read** instead of starting a new one, so
 *   every caller receives the same result.
 * - **A recent read is reused.** A manual read that lands right after a
 *   scheduled one does not hit the router again.
 * - **Every consumer sees the same outcome**, and the state says whether a
 *   read is running and since when.
 *
 * ## What it does not do
 *
 * It does not decide *when* a read happens: the polling period lives in
 * [RefreshCoordinator]. It does not know about the session: the repository
 * owns the session state and the generation counter, and it is the only one
 * that writes to the snapshot store.
 */
class ReadCoordinator(
    private val adapter: RouterAdapter,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val freshness: ReadFreshnessPolicy = ReadFreshnessPolicy.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * The read currently running, if any.
     *
     * It is the single-flight handle: a second caller arriving while it is
     * set **waits for the same result** instead of starting a new read.
     */
    private var inFlight: Deferred<Result<DeviceSnapshot>>? = null

    private val _state = MutableStateFlow(ReadState())
    val state: StateFlow<ReadState> = _state.asStateFlow()

    /**
     * Reads the devices, or reuses a recent read.
     *
     * @param force when true, a recent read is **not** reused and the
     *   router is queried even if the last read was fresh. The manual
     *   button does not force: the user wants fresh data, and a read that
     *   completed a moment ago *is* fresh data.
     */
    suspend fun read(force: Boolean = false): ReadOutcome {
        val running = inFlight
        if (running != null) {
            // A read is already running: wait for it instead of starting a
            // second one. Both screens end up with the same snapshot, and
            // the router is asked only once.
            return running.await().toOutcome()
        }

        if (!force) {
            val reusable = reusableSnapshot()
            if (reusable != null) return ReadOutcome.Success(reusable)
        }

        return startRead()
    }

    /**
     * The last snapshot, when it is still fresh enough to be reused.
     *
     * A **failed** read is never reusable: the data on screen is then old,
     * and the next attempt must reach the router.
     */
    private fun reusableSnapshot(): DeviceSnapshot? {
        val last = _state.value
        val snapshot = last.snapshot ?: return null
        if (last.lastError != null) return null
        val completedAt = last.completedAtMonotonicMillis ?: return null
        val age = clock.monotonicMillis() - completedAt
        return if (freshness.isFresh(age)) snapshot else null
    }

    /**
     * Starts the one read that is allowed to happen, and publishes it.
     */
    private suspend fun startRead(): ReadOutcome {
        val startedAt = clock.monotonicMillis()
        _state.value = _state.value.copy(
            isReading = true,
            readingStartedAtMonotonicMillis = startedAt,
        )

        val deferred = scope.async(ioDispatcher) {
            runCatching { adapter.fetchDevices() }
        }
        inFlight = deferred

        return try {
            val result = deferred.await()
            val completedAt = clock.monotonicMillis()
            _state.value = _state.value.copy(
                isReading = false,
                readingStartedAtMonotonicMillis = null,
                snapshot = result.getOrNull() ?: _state.value.snapshot,
                lastError = result.exceptionOrNull() as? RouterUnavailableException,
                completedAtMonotonicMillis = completedAt,
            )
            result.toOutcome()
        } finally {
            // Only the owner of the flight clears it: a read that started
            // later must not be cancelled by the tail of an earlier one.
            if (inFlight === deferred) inFlight = null
        }
    }

    /** Forgets the reusable snapshot, so the next read reaches the router. */
    fun invalidate() {
        _state.value = _state.value.copy(
            snapshot = null,
            completedAtMonotonicMillis = null,
            lastError = null,
        )
    }

    private fun Result<DeviceSnapshot>.toOutcome(): ReadOutcome =
        fold(
            onSuccess = { ReadOutcome.Success(it) },
            onFailure = { error ->
                ReadOutcome.Failure(
                    when (error) {
                        is RouterUnavailableException -> error
                        // The adapter only reports the application failures,
                        // but a crash must not look like a session expiry.
                        else -> RouterUnavailableException.UnexpectedResponse(error)
                    }
                )
            },
        )
}

/**
 * The observable state of the reads.
 *
 * The timestamps are **monotonic**: they measure a duration, not a moment on
 * the calendar. They are only comparable to each other, and a change of the
 * phone's date cannot disturb them.
 */
data class ReadState(
    val isReading: Boolean = false,
    val readingStartedAtMonotonicMillis: Long? = null,
    val snapshot: DeviceSnapshot? = null,
    val lastError: RouterUnavailableException? = null,
    val completedAtMonotonicMillis: Long? = null,
)
