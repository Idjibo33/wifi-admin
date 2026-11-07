package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkMonitor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) : SpeedTestEngine {

    override val providerLabel: String = providerLabelFake

    private val runCount = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    @Volatile private var cancelled = false

    fun clearCounters() { runCount.set(0); totalBytes.set(0) }

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
            if (!ensureActive()) throw CancellationException("cancelled")
            if (failMidway && dl > dlTarget / 2) throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
            dl += chunk; totalBytes.addAndGet(chunk)
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = dl))
        }
        onProgress(SpeedTestState.Running(SpeedTestPhase.UPLOAD, downloadMbps = 42.0))
        var ul = 0L
        val ulTarget = policy.maxUploadBytes / 4
        while (ul < ulTarget) {
            if (!ensureActive()) throw CancellationException("cancelled")
            ul += chunk; totalBytes.addAndGet(chunk)
            onProgress(SpeedTestState.Running(SpeedTestPhase.UPLOAD, uploadBytes = ul))
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
    private val mutex = Mutex()
    private var running: Job? = null
    private var generation = 0
    private var startNetworkKey: String? = null

    private val _state = MutableStateFlow<SpeedTestState>(SpeedTestState.Idle)
    val state = _state.asStateFlow()

    val isRunning: Boolean get() = running?.isActive == true

    /**
     * Starts one test. Returns immediately; progress is observed through
     * [state]. A second start while one is running is refused as
     * [SpeedTestFailure.TooBusy] without touching the first test.
     */
    fun start() {
        scope.launch(ioDispatcher) {
            mutex.withLock {
                if (running?.isActive == true) {
                    _state.value = SpeedTestState.Failed(SpeedTestFailure.TooBusy)
                    return@launch
                }
                val status = monitor.currentStatus()
                if (status !is LocalNetworkStatus.LocalWifi) {
                    _state.value = SpeedTestState.Failed(SpeedTestFailure.NoWiFi)
                    return@launch
                }
                startNetworkKey = status.identity.key
                generation++
                _state.value = SpeedTestState.Running(SpeedTestPhase.CHECKING_NETWORK)
            }

            val myGeneration = generation
            val job = Job()
            mutex.withLock { running = job }
            try {
                val result = engine.run { progress ->
                    if (myGeneration == generation) {
                        _state.value = progress
                    }
                }
                if (myGeneration == generation) {
                    _state.value = SpeedTestState.Done(result)
                }
            } catch (e: SpeedTestFailureException) {
                if (myGeneration == generation) {
                    _state.value = SpeedTestState.Failed(e.failure)
                }
            } catch (e: CancellationException) {
                if (myGeneration == generation) {
                    _state.value = SpeedTestState.Cancelled
                }
                throw e
            } finally {
                engine.cancel()
                mutex.withLock {
                    if (running === job) running = null
                }
            }
        }
    }

    /** Cancels the current test, its sockets and its callbacks. */
    fun cancel(reason: CancelReason = CancelReason.USER) {
        engine.cancel()
        running?.cancel()
        generation++
        if (reason == CancelReason.USER) {
            _state.value = SpeedTestState.Cancelled
        }
    }

    /** A cold flow that cancels the test when the network leaves Wi-Fi. */
    fun watchNetworkLoss(): Flow<Unit> = callbackFlow {
        val job = scope.launch {
            monitor.observeStatus().collect { status ->
                if (isRunning && status !is LocalNetworkStatus.LocalWifi) {
                    cancel(CancelReason.NETWORK_LOST)
                    send(Unit)
                } else if (isRunning && status is LocalNetworkStatus.LocalWifi &&
                    status.identity.key != startNetworkKey
                ) {
                    cancel(CancelReason.NETWORK_CHANGED)
                    send(Unit)
                }
            }
        }
        awaitClose { job.cancel() }
    }
}
