package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle and concurrency of [SpeedTestRepository].
 *
 * Reproduces the reported race: a cancelled run keeps the engine open in a
 * suspended, non-cancellable teardown. No new run may start before that
 * teardown has returned, and no stale `engine.cancel()` may reach the next
 * run. All dispatchers are test dispatchers, so the windows are
 * deterministic — no sleep, no real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestRepositoryLifecycleTest {

    private val identityA = NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")
    private val identityB = NetworkIdentity("wlan0", "192.168.2.0/24", "192.168.2.1")
    private val wifiA = LocalNetworkStatus.LocalWifi(identityA)
    private val wifiB = LocalNetworkStatus.LocalWifi(identityB)

    private fun result(label: String) = SpeedTestResult(
        downloadMbps = 1.0,
        uploadMbps = 1.0,
        latencyMillis = 1L,
        downloadBytes = 1L,
        uploadBytes = 1L,
        durationMillis = 1L,
        capped = false,
        providerLabel = label,
        networkKey = "k",
        wifiLink = null,
        capturedAtWallMillis = 1L,
    )

    private fun TestScope.repository(
        engine: SpeedTestEngine,
        monitor: NetworkMonitor,
        policy: SpeedTestPolicy = SpeedTestPolicy(),
    ): SpeedTestRepository = SpeedTestRepository(
        engine = engine,
        monitor = monitor,
        clock = TestAppClock(),
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        policy = policy,
    )

    private class FakeMonitor(initial: LocalNetworkStatus) : NetworkMonitor {
        val flow = MutableStateFlow(initial)
        override fun observeStatus(): Flow<LocalNetworkStatus> = flow
        override fun currentStatus(): LocalNetworkStatus = flow.value
    }

    /**
     * Run 1 waits until cancelled, then "closes" in a suspended
     * [NonCancellable] teardown. Run 2 waits on [secondRunResult].
     * [cancel] only touches the gate of the run that is active at that
     * instant, so a stale cancellation is observable in [cancelLog].
     */
    private class CleanupSuspendingEngine : SpeedTestEngine {
        override val providerLabel: String = "cleanup-suspending"

        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val secondRunResult = CompletableDeferred<SpeedTestResult>()

        var runs = 0
            private set
        var cancelCalls = 0
            private set
        val cancelLog = mutableListOf<Int?>()

        private var activeRunId: Int? = null
        private var activeGate: CompletableDeferred<SpeedTestResult>? = null

        override suspend fun run(
            onProgress: suspend (SpeedTestState.Running) -> Unit,
        ): SpeedTestResult {
            val id = runs + 1
            runs = id
            val gate = if (id == 1) CompletableDeferred() else secondRunResult
            activeRunId = id
            activeGate = gate
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
            try {
                return gate.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanupGate.await()
                }
                throw e
            } finally {
                // Never let an old run clear the new run's identity.
                if (activeGate === gate) {
                    activeGate = null
                    activeRunId = null
                }
            }
        }

        override fun cancel() {
            cancelCalls++
            cancelLog += activeRunId
            activeGate?.cancel()
        }
    }

    /** Each run awaits a gate, or throws a preset error before awaiting. */
    private class GatedEngine : SpeedTestEngine {
        override val providerLabel: String = "gated"

        var runs = 0
            private set
        var cancelCalls = 0
            private set
        var cancelledRuns = 0
            private set

        var failNextWith: Throwable? = null
        var gate = CompletableDeferred<SpeedTestResult>()

        override suspend fun run(
            onProgress: suspend (SpeedTestState.Running) -> Unit,
        ): SpeedTestResult {
            runs++
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
            failNextWith?.let { failure ->
                failNextWith = null
                throw failure
            }
            return try {
                gate.await()
            } catch (e: CancellationException) {
                cancelledRuns++
                throw e
            }
        }

        override fun cancel() {
            cancelCalls++
        }
    }

    /** Production engine wrapped with observable invocation/byte counters. */
    private class CountingDisabledEngine : SpeedTestEngine {
        private val delegate = DisabledSpeedTestEngine()

        override val providerLabel: String = delegate.providerLabel

        var runCount = 0
            private set
        var transferredBytes = 0L
            private set

        override suspend fun run(
            onProgress: suspend (SpeedTestState.Running) -> Unit,
        ): SpeedTestResult {
            runCount++
            return delegate.run { progress ->
                transferredBytes += progress.downloadBytes + progress.uploadBytes
                onProgress(progress)
            }
        }

        override fun cancel() = delegate.cancel()
    }

    @Test
    fun `start is refused while cleanup is still suspended`() = runTest {
        val engine = CleanupSuspendingEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        assertEquals(1, engine.runs)
        assertTrue(repository.isRunning)

        repository.cancel()
        runCurrent()
        assertTrue(engine.cleanupStarted.isCompleted)
        assertTrue("the slot stays owned during cleanup", repository.isRunning)
        assertTrue(repository.state.value is SpeedTestState.Cancelled)

        repository.start() // must be refused: the slot is still owned
        runCurrent()

        assertEquals("no second engine run", 1, engine.runs)
        assertTrue(
            "a refused start must not clobber the state",
            repository.state.value is SpeedTestState.Cancelled,
        )

        engine.cleanupGate.complete(Unit)
        runCurrent()
        assertFalse(repository.isRunning)
    }

    @Test
    fun `a new start succeeds after cleanup completes and reaches Done`() = runTest {
        val engine = CleanupSuspendingEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        repository.cancel()
        runCurrent()
        assertTrue(engine.cleanupStarted.isCompleted)

        engine.cleanupGate.complete(Unit)
        runCurrent()
        assertFalse(repository.isRunning)

        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)

        engine.secondRunResult.complete(result("second"))
        runCurrent()

        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Done)
        assertEquals("second", (state as SpeedTestState.Done).result.providerLabel)
        assertFalse(repository.isRunning)
    }

    @Test
    fun `old cleanup never cancels the new run`() = runTest {
        val engine = CleanupSuspendingEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        repository.cancel()
        runCurrent()
        assertTrue(engine.cleanupStarted.isCompleted)

        // If this start were accepted, the old teardown would call
        // engine.cancel() after the new run had begun.
        repository.start()
        runCurrent()
        assertEquals(1, engine.runs)

        engine.cleanupGate.complete(Unit)
        runCurrent()
        assertEquals("only the user cancel reached the engine", 1, engine.cancelCalls)
        assertEquals(1, engine.runs)

        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)

        val cancelsWhenSecondRunStarted = engine.cancelCalls
        assertFalse("the old cleanup cancelled run 2", engine.cancelLog.contains(2))
        runCurrent()
        assertEquals(cancelsWhenSecondRunStarted, engine.cancelCalls)
        assertTrue("the second run is still active", repository.isRunning)

        engine.secondRunResult.complete(result("second"))
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test
    fun `sequential starts both reach Done`() = runTest {
        val engine = GatedEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        assertEquals(1, engine.runs)
        assertTrue(repository.isRunning)
        engine.gate.complete(result("A"))
        runCurrent()

        val first = repository.state.value
        assertTrue(first is SpeedTestState.Done)
        assertEquals("A", (first as SpeedTestState.Done).result.providerLabel)
        assertFalse(repository.isRunning)

        engine.gate = CompletableDeferred()
        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)
        engine.gate.complete(result("B"))
        runCurrent()

        val second = repository.state.value
        assertTrue(second is SpeedTestState.Done)
        assertEquals("B", (second as SpeedTestState.Done).result.providerLabel)
        assertFalse(repository.isRunning)
    }

    @Test
    fun `cancel during cleanup is idempotent and releases the slot`() = runTest {
        val engine = CleanupSuspendingEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        repository.cancel()
        runCurrent()
        assertTrue(engine.cleanupStarted.isCompleted)

        repository.cancel()
        repository.cancel()
        runCurrent()

        assertEquals("no run while cleaning up", 1, engine.runs)
        assertTrue(engine.cleanupStarted.isCompleted)
        assertTrue(repository.state.value is SpeedTestState.Cancelled)

        engine.cleanupGate.complete(Unit)
        runCurrent()
        assertFalse(repository.isRunning)

        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue("the slot was released", repository.isRunning)

        engine.secondRunResult.complete(result("after-cleanup"))
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test
    fun `identity change cancels the run without starting another one`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = GatedEngine()
        val repository = repository(engine, monitor)
        val watcher = launch { repository.watchNetworkLoss().collect {} }
        runCurrent()

        repository.start()
        runCurrent()
        assertTrue(repository.isRunning)
        assertEquals(1, engine.runs)

        monitor.flow.value = wifiB
        runCurrent()

        assertFalse(repository.isRunning)
        assertEquals("no automatic retry", 1, engine.runs)
        assertEquals("engine cancellation is real", 1, engine.cancelledRuns)
        assertTrue(engine.cancelCalls >= 1)
        assertTrue(
            "a network change publishes a terminal state",
            repository.state.value is SpeedTestState.Cancelled,
        )

        watcher.cancel()
        runCurrent()
    }

    @Test
    fun `wifi loss cancels the run and never falls back to mobile`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = GatedEngine()
        val repository = repository(engine, monitor)
        val watcher = launch { repository.watchNetworkLoss().collect {} }
        runCurrent()

        repository.start()
        runCurrent()
        assertTrue(repository.isRunning)

        monitor.flow.value = LocalNetworkStatus.NoWifi
        runCurrent()

        assertFalse(repository.isRunning)
        assertEquals(1, engine.runs)
        assertEquals(1, engine.cancelledRuns)
        assertTrue(
            "a network loss publishes a terminal state, not Running",
            repository.state.value is SpeedTestState.Cancelled,
        )

        // Another tap off Wi-Fi fails before the engine: no mobile fallback.
        repository.start()
        runCurrent()
        assertEquals("engine must not be called without Wi-Fi", 1, engine.runs)
        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.NoWiFi, (state as SpeedTestState.Failed).reason)

        watcher.cancel()
        runCurrent()
    }

    @Test
    fun `unexpected error fails without a stuck running state and allows a retry`() = runTest {
        val engine = GatedEngine().apply { failNextWith = IllegalStateException("boom") }
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()

        val failed = repository.state.value
        assertTrue("STATE=$failed", failed is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Unexpected, (failed as SpeedTestState.Failed).reason)
        assertFalse(repository.isRunning)

        engine.gate = CompletableDeferred()
        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)

        engine.gate.complete(result("retry"))
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test
    fun `timeout fails without a stuck running state and allows a retry`() = runTest {
        val engine = GatedEngine()
        val repository = repository(
            engine,
            FakeMonitor(wifiA),
            SpeedTestPolicy(maxDurationMillis = 1_000L),
        )

        repository.start()
        runCurrent()
        assertTrue(repository.isRunning)

        advanceTimeBy(1_000L)
        runCurrent()

        val failed = repository.state.value
        assertTrue("STATE=$failed", failed is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Timeout, (failed as SpeedTestState.Failed).reason)
        assertFalse(repository.isRunning)
        assertEquals(1, engine.cancelledRuns)

        engine.gate = CompletableDeferred()
        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)

        engine.gate.complete(result("retry"))
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test
    fun `cancel after Done keeps the result`() = runTest {
        val engine = GatedEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()
        engine.gate.complete(result("kept"))
        runCurrent()

        assertTrue(repository.state.value is SpeedTestState.Done)
        repository.cancel()

        val state = repository.state.value
        assertTrue("cancel must not mask a result", state is SpeedTestState.Done)
        assertEquals("kept", (state as SpeedTestState.Done).result.providerLabel)
    }

    @Test
    fun `disabled engine transfers zero bytes and releases the slot`() = runTest {
        val engine = CountingDisabledEngine()
        val repository = repository(engine, FakeMonitor(wifiA))

        repository.start()
        runCurrent()

        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Disabled, (state as SpeedTestState.Failed).reason)
        assertEquals(1, engine.runCount)
        assertEquals(0L, engine.transferredBytes)
        assertFalse(repository.isRunning)

        // The failure released the slot: the engine may be reached again.
        repository.start()
        runCurrent()
        assertEquals(2, engine.runCount)
        assertEquals(0L, engine.transferredBytes)
    }
}
