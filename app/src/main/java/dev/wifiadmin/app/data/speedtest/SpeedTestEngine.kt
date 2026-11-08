package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkMonitor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The measurement engine.
 *
 * The real Internet measurement is **disabled in this version** (see
 * `DisabledSpeedTestEngine`): no validated provider fits the caps and the
 * privacy rules, so the app never contacts a third-party server. The
 * engine is still a real, testable component — the UI, the repository,
 * the cancellation and the error states work exactly the same with the
 * fake engine used in tests.
 */
interface SpeedTestEngine {
    /** Long-lived engine description shown to the user before any test. */
    val providerLabel: String

    /** Runs latence → download → upload, emitting progress. Cooperative cancellation. */
    suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult

    /** Cancels a running test: every socket and coroutine is closed. */
    fun cancel()
}

/**
 * The production engine.
 *
 * It fails fast, **without any network call**, with [SpeedTestFailure.Disabled].
 * A fictional result would be a lie; a missing endpoint is an honest state.
 */
class DisabledSpeedTestEngine : SpeedTestEngine {
    override val providerLabel = "Fournisseur de mesure non configuré"

    override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
        throw SpeedTestFailureException(SpeedTestFailure.Disabled)
    }

    override fun cancel() = Unit
}

/** Wrapper so a failure travels through coroutines unchanged. */
class SpeedTestFailureException(val failure: SpeedTestFailure) : Exception(failure.toString())

/**
 * A fake engine for tests, previews and screenshots.
 *
 * It counts the volumes it pretends to transfer, honours the policy caps
 * (retry included), exposes progress through [onProgress], and cooperates
 * with cancellation: no socket, no network, deterministic without the
 * network stack. Real throughput is simulated at a fixed rate.
 */
class FakeSpeedTestEngine(
    private val policy: SpeedTestPolicy = SpeedTestPolicy(),
    private val clock: AppClock? = null,
    private val providerLabelFake: String = "Moteur simulé (fictif)",
    private val failMidway: Boolean = false,
    private val networkKey: String = "simulated|simulated|simulated",
    private val wifiLink: WifiLinkInfo? = null,
    private val chunkDelayMillis: Long = 100L,
) : SpeedTestEngine {

    override val providerLabel: String = providerLabelFake

    private val runCount = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    @Volatile private var cancelled = false

    fun clearCounters() { runCount.set(0); totalBytes.set(0) }
    fun runCountSnapshot(): Long = runCount.get()
    fun totalBytesSnapshot(): Long = totalBytes.get()

    override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
        runCount.incrementAndGet()
        cancelled = false
        val start = clock?.monotonicMillis() ?: 0L
        // Latency: 5 probes, median 24 ms — the median ignores a spike.
        onProgress(SpeedTestState.Running(SpeedTestPhase.LATENCY))
        repeat(policy.latencyProbes) { if (!ensureActive()) throw CancellationException("cancelled") }
        onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))

        val dlTarget = policy.maxDownloadBytes / 4
        var dl = 0L
        val chunk = 256L * 1024
        while (dl < dlTarget) {
            currentCoroutineContext().ensureActive()
            if (!ensureActive()) throw CancellationException("cancelled")
            if (failMidway && dl > dlTarget / 2) throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
            dl += chunk; totalBytes.addAndGet(chunk)
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = dl))
            if (chunkDelayMillis > 0) delay(chunkDelayMillis)
        }
        onProgress(SpeedTestState.Running(SpeedTestPhase.UPLOAD, downloadMbps = 42.0))
        var ul = 0L
        val ulTarget = policy.maxUploadBytes / 4
        while (ul < ulTarget) {
            currentCoroutineContext().ensureActive()
            if (!ensureActive()) throw CancellationException("cancelled")
            ul += chunk; totalBytes.addAndGet(chunk)
            onProgress(SpeedTestState.Running(SpeedTestPhase.UPLOAD, uploadBytes = ul))
            if (chunkDelayMillis > 0) delay(chunkDelayMillis)
        }

        val elapsed = if (clock != null) (clock.monotonicMillis() - start).coerceAtLeast(1_000L) else 8_000L
        return SpeedTestResult(
            downloadMbps = SpeedTestCalculator.mbps(dl, elapsed),
            uploadMbps = SpeedTestCalculator.mbps(ul, elapsed),
            latencyMillis = SpeedTestCalculator.medianLatencyMillis(listOf(20, 25, 100, 22, 30)),
            downloadBytes = dl,
            uploadBytes = ul,
            durationMillis = elapsed,
            capped = false,
            providerLabel = providerLabelFake,
            networkKey = networkKey,
            wifiLink = wifiLink,
            capturedAtWallMillis = clock?.wallClockMillis() ?: 1_700_000_000_000L,
        )
    }

    override fun cancel() { cancelled = true }
    private fun ensureActive(): Boolean = !cancelled
}

/**
 * Owns at most one running test (single flight), tracks the network it
 * started on, and cancels as soon as the phone leaves that network.
 *
 * There is no polling, no background service and no automatic retry: a
 * test only starts on an explicit user action and only one runs at a
 * time. When the router session expires the repository is not touched
 * here — the navigation already leaves the screen; the ViewModel cancels
 * its collection on `onCleared`.
 */
class SpeedTestRepository(
    private val engine: SpeedTestEngine,
    private val monitor: NetworkMonitor,
    private val linkProvider: WifiLinkInfoProvider? = null,
    private val clock: AppClock? = null,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val policy: SpeedTestPolicy = SpeedTestPolicy(),
) {
    /** Serialises the run slot, the generation and every engine.cancel(). */
    private val lock = Any()

    /** Incremented by each start() and each cancel(); filters stale writes. */
    private val generation = AtomicInteger(0)

    /**
     * The run slot: the single-flight criterion.
     *
     * Set under [lock] before launching, cleared under [lock] from
     * [Job.invokeOnCompletion] — that is, only once the coroutine has fully
     * finished and its `finally` has released the engine. `Job.isActive`
     * cannot replace it: it is already false while a cancelled coroutine is
     * still unwinding and still owns the engine.
     */
    @Volatile private var running = false
    @Volatile private var runJob: Job? = null
    @Volatile private var startNetworkKey: String? = null

    private val _state = MutableStateFlow<SpeedTestState>(SpeedTestState.Idle)
    val state = _state.asStateFlow()

    /** True while a run owns the engine, including its final cleanup. */
    val isRunning: Boolean get() = running

    /**
     * Starts one test. Returns immediately; progress is observed through
     * [state].
     *
     * A second call while a run is active **or still releasing its
     * resources** is refused: the slot stays owned until the previous
     * coroutine has completed. A refused start never overwrites the state
     * of the run that owns the slot.
     */
    fun start() {
        // Read the network outside the lock (binder call), but never publish
        // a failure over the state of a run that owns the slot.
        val statusOrError = runCatching { monitor.currentStatus() }
        synchronized(lock) {
            if (running) return
            val status = statusOrError.getOrElse {
                _state.value = SpeedTestState.Failed(SpeedTestFailure.Unexpected)
                return
            }
            if (status !is LocalNetworkStatus.LocalWifi) {
                _state.value = SpeedTestState.Failed(SpeedTestFailure.NoWiFi)
                return
            }
            running = true
            val myGeneration = generation.incrementAndGet()
            startNetworkKey = status.identity.key
            _state.value = SpeedTestState.Running(SpeedTestPhase.CHECKING_NETWORK)
            val job = try {
                scope.launch(ioDispatcher) { execute(myGeneration, status.identity.key) }
            } catch (t: Throwable) {
                // A stopped dispatcher must not leak the slot.
                running = false
                _state.value = SpeedTestState.Failed(SpeedTestFailure.Unexpected)
                return
            }
            runJob = job
            // Runs strictly after the coroutine body (and its finally).
            job.invokeOnCompletion { onRunCompleted(job) }
        }
    }

    /**
     * Releases the slot, under [lock] and only for the job that owns it.
     *
     * This is the only place where [running] returns to false, so a new
     * run can never start before the previous one has fully completed.
     */
    private fun onRunCompleted(job: Job) {
        synchronized(lock) {
            if (runJob !== job) return
            runJob = null
            running = false
        }
    }

    /**
     * Test-and-write under [lock]: a stale run can neither publish a
     * progress update, a result nor a failure after it was cancelled.
     */
    private fun publish(myGeneration: Int, newState: SpeedTestState) {
        synchronized(lock) {
            if (myGeneration == generation.get()) _state.value = newState
        }
    }

    private suspend fun execute(myGeneration: Int, expectedNetworkKey: String) {
        try {
            // Revalidate after taking the slot: the network may have
            // changed between currentStatus() and the coroutine start.
            val status = try {
                monitor.currentStatus()
            } catch (t: Throwable) {
                publish(myGeneration, SpeedTestState.Failed(SpeedTestFailure.Unexpected))
                return
            }
            if (status !is LocalNetworkStatus.LocalWifi) {
                publish(myGeneration, SpeedTestState.Failed(SpeedTestFailure.NoWiFi))
                return
            }
            if (status.identity.key != expectedNetworkKey) {
                publish(myGeneration, SpeedTestState.Cancelled)
                return
            }
            val result = withTimeout(policy.maxDurationMillis) {
                engine.run { progress ->
                    // Independent backstop (SEC-01): even a defective
                    // engine cannot exceed the prepaid caps. The byte
                    // counters traverse this wrapper, so the caps are
                    // enforced whether or not the engine enforces them.
                    val overDownload = progress.downloadBytes > policy.maxDownloadBytes
                    val overUpload = progress.uploadBytes > policy.maxUploadBytes
                    val overTotal = progress.downloadBytes + progress.uploadBytes >
                        policy.maxDownloadBytes + policy.maxUploadBytes
                    if (overDownload || overUpload || overTotal) {
                        runCatching { engine.cancel() }
                        throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
                    }
                    publish(myGeneration, progress)
                }
            }
            // The real engine does not know the Wi-Fi identity: fill the
            // blank network key and link from the repository's own view.
            // Engines that already report them (Fake) are untouched.
            val enriched = if (result.networkKey.isBlank()) {
                result.copy(
                    networkKey = expectedNetworkKey,
                    wifiLink = result.wifiLink ?: runCatching { linkProvider?.current() }.getOrNull(),
                )
            } else {
                result
            }
            publish(myGeneration, SpeedTestState.Done(enriched))
        } catch (e: TimeoutCancellationException) {
            publish(myGeneration, SpeedTestState.Failed(SpeedTestFailure.Timeout))
        } catch (e: SpeedTestFailureException) {
            publish(myGeneration, SpeedTestState.Failed(e.failure))
        } catch (e: CancellationException) {
            publish(myGeneration, SpeedTestState.Cancelled)
            throw e
        } catch (t: Throwable) {
            publish(myGeneration, SpeedTestState.Failed(SpeedTestFailure.Unexpected))
        } finally {
            synchronized(lock) {
                // Only the run that still owns the generation releases the
                // engine. A cancelled run already had engine.cancel() called
                // under this lock by cancel(); a stale run therefore can
                // never touch a successor's engine.
                if (myGeneration == generation.get()) {
                    runCatching { engine.cancel() }
                }
            }
        }
    }

    /**
     * Cancels the running test, its sockets and its callbacks.
     *
     * Everything happens under [lock], so the engine is closed before the
     * slot can be released and no successor can exist yet: an old
     * cancellation can never reach a newer run. A terminal `Cancelled`
     * state is published for every reason (user, network loss, network
     * change), except when a `Done`/`Failed` state was already published.
     */
    fun cancel(reason: CancelReason = CancelReason.USER) {
        synchronized(lock) {
            if (!running) return
            generation.incrementAndGet()
            runCatching { engine.cancel() }
            runJob?.cancel()
            if (_state.value is SpeedTestState.Running) {
                _state.value = SpeedTestState.Cancelled
            }
        }
    }

    /** A cold flow that cancels the test when the network leaves Wi-Fi or changes identity. */
    fun watchNetworkLoss(): Flow<Unit> = callbackFlow {
        val job = launch {
            monitor.observeStatus().collect { status ->
                if (!isRunning) return@collect
                val lost = status !is LocalNetworkStatus.LocalWifi
                val changed = status is LocalNetworkStatus.LocalWifi &&
                    status.identity.key != startNetworkKey
                if (lost) {
                    cancel(CancelReason.NETWORK_LOST)
                    trySend(Unit)
                } else if (changed) {
                    cancel(CancelReason.NETWORK_CHANGED)
                    trySend(Unit)
                }
            }
        }
        awaitClose { job.cancel() }
    }
}
