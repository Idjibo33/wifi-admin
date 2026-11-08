package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Concurrency: single flight, cancellation, old-result protection, failure, network key. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestRepositoryConcurrencyTest {

    private class FakeMonitor(initial: LocalNetworkStatus) : NetworkMonitor {
        val flow = MutableStateFlow(initial)
        override fun observeStatus(): Flow<LocalNetworkStatus> = flow
        override fun currentStatus(): LocalNetworkStatus = flow.value
    }

    private class LatchEngine(
        val gate: CompletableDeferred<SpeedTestResult> = CompletableDeferred(),
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        var runs: Int = 0,
        var cancelCalls: Int = 0,
        var toThrow: Throwable? = null,
    ) : SpeedTestEngine {
        override val providerLabel = "latch"
        override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
            runs++
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
            started.complete(Unit)
            toThrow?.let { throw it }
            return gate.await()
        }
        override fun cancel() { cancelCalls++ }
    }

    private val identityA = NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")
    private val identityB = NetworkIdentity("wlan0", "192.168.2.0/24", "192.168.2.1")
    private val wifiA = LocalNetworkStatus.LocalWifi(identityA)
    private val wifiB = LocalNetworkStatus.LocalWifi(identityB)

    private fun result(label: String) = SpeedTestResult(
        downloadMbps = 1.0, uploadMbps = 1.0, latencyMillis = 1,
        downloadBytes = 1, uploadBytes = 1, durationMillis = 1,
        capped = false, providerLabel = label, networkKey = "k", wifiLink = null, capturedAtWallMillis = 1,
    )

    private fun repo(engine: SpeedTestEngine, monitor: NetworkMonitor, scope: CoroutineScope) =
        SpeedTestRepository(engine, monitor, scope = scope, ioDispatcher = Dispatchers.Unconfined)

    @Test fun `concurrent starts run a single engine run`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = LatchEngine()
        val repository = repo(engine, monitor, this)
        repository.start()
        repository.start()
        runCurrent()
        assertEquals(1, engine.runs)
        assertTrue(repository.isRunning)
        engine.gate.complete(result("first"))
        advanceUntilIdle()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test fun `cancellation cancels the suspended run`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = LatchEngine()
        val repository = repo(engine, monitor, this)
        repository.start()
        runCurrent()
        repository.cancel()
        runCurrent()
        assertEquals(1, engine.runs)
        assertTrue(repository.state.value is SpeedTestState.Cancelled)
        assertFalse(repository.isRunning)
        assertTrue(engine.cancelCalls >= 1)
    }

    @Test fun `old completion cannot overwrite new result`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val firstGate = CompletableDeferred<SpeedTestResult>()
        val engine = object : SpeedTestEngine {
            override val providerLabel = "old-vs-new"
            var n = 0
            override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
                n++
                onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
                return if (n == 1) firstGate.await() else result2()
            }
            fun result2() = SpeedTestResult(
                downloadMbps = 2.0, uploadMbps = 2.0, latencyMillis = 2,
                downloadBytes = 2, uploadBytes = 2, durationMillis = 2,
                capped = false, providerLabel = "second", networkKey = "k", wifiLink = null, capturedAtWallMillis = 2,
            )
            override fun cancel() {}
        }
        val repository = repo(engine, monitor, this)
        repository.start()
        runCurrent()
        repository.cancel()
        runCurrent()
        repository.start()
        runCurrent()
        val state = repository.state.value
        assertTrue(state is SpeedTestState.Done)
        assertEquals("second", (state as SpeedTestState.Done).result.providerLabel)
    }

    @Test fun `unexpected error becomes failed without stuck running`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = LatchEngine().apply { toThrow = IllegalStateException("boom") }
        val repository = repo(engine, monitor, this)
        repository.start()
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Failed)
        assertFalse(repository.isRunning)
    }

    @Test fun `identity change cancels running test`() = runTest {
        val monitor = FakeMonitor(wifiA)
        val engine = LatchEngine()
        val repository = repo(engine, monitor, this)
        val collector = launch { repository.watchNetworkLoss().collect {} }
        repository.start()
        runCurrent()
        assertTrue(repository.isRunning)
        monitor.flow.value = wifiB
        runCurrent()
        assertFalse(repository.isRunning)
        collector.cancel()
    }
}
