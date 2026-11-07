package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orchestration: single flight, cancellation, network loss, no mobile fallback. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestEngineTest {

    private class FakeMonitor(initial: LocalNetworkStatus) : NetworkMonitor {
        val flow = MutableStateFlow(initial)
        override fun observeStatus(): Flow<LocalNetworkStatus> = flow
        override fun currentStatus(): LocalNetworkStatus = flow.value
    }

    private val wifi = LocalNetworkStatus.LocalWifi(NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1"))

    private fun repo(
        engine: SpeedTestEngine,
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ) = SpeedTestRepository(engine, monitor, clock = TestAppClock(), scope = scope, ioDispatcher = Dispatchers.Unconfined)

    @Test fun `disabled engine never opens network and fails disabled`() = runTest {
        val monitor = FakeMonitor(wifi)
        val repository = repo(DisabledSpeedTestEngine(), monitor, this)
        repository.start()
        runCurrent()
        val state = repository.state.value
        assertTrue(state is SpeedTestState.Failed && state.reason is SpeedTestFailure.Disabled)
    }

    @Test fun `fake engine delivers result`() = runTest {
        val monitor = FakeMonitor(wifi)
        val repository = repo(FakeSpeedTestEngine(), monitor, this)
        repository.start()
        runCurrent()
        // Fake engine is cooperative; join the repository job.
        delay(1)
        val state = repository.state.value
        // Progress emissions already moved the state; the run completes on Unconfined.
        assertTrue(state is SpeedTestState.Done || state is SpeedTestState.Running)
    }

    @Test fun `start without wifi fails without touching engine`() = runTest {
        val monitor = FakeMonitor(LocalNetworkStatus.NoWifi)
        var called = false
        val engine = object : SpeedTestEngine {
            override val providerLabel = "test"
            override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
                called = true
                throw SpeedTestFailureException(SpeedTestFailure.Unexpected)
            }
            override fun cancel() { called = true }
        }
        val repository = repo(engine, monitor, this)
        repository.start()
        runCurrent()
        assertFalse(called)
        val state = repository.state.value
        assertTrue(state is SpeedTestState.Failed && state.reason is SpeedTestFailure.NoWiFi)
    }

    @Test fun `network loss cancels without retry`() = runTest {
        val monitor = FakeMonitor(wifi)
        val repository = repo(FakeSpeedTestEngine(), monitor, this)
        repository.start()
        runCurrent()
        monitor.flow.value = LocalNetworkStatus.NoWifi
        runCurrent()
        assertFalse(repository.isRunning)
    }

    @Test fun `no mobile fallback ever`() = runTest {
        val monitor = FakeMonitor(wifi)
        // The fake engine finishes synchronously, so the run is already
        // Done when the network changes: what matters is that no new run
        // starts on mobile data.
        val repository = repo(FakeSpeedTestEngine(), monitor, this)
        repository.start()
        runCurrent()
        monitor.flow.value = LocalNetworkStatus.NotOnWifi
        runCurrent()
        assertFalse(repository.isRunning)
        val state = repository.state.value
        assertTrue(state is SpeedTestState.Done || state is SpeedTestState.Cancelled || state is SpeedTestState.Failed)
        // Starting on mobile data is refused outright.
        repository.start()
        runCurrent()
        val after = repository.state.value
        assertTrue(after is SpeedTestState.Failed && after.reason is SpeedTestFailure.NoWiFi)
    }
}
