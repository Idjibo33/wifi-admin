package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seam S3 : le repository protège même le moteur réel (backstop plafonds). */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestRepositoryBackstopTest {

    private val wifi = LocalNetworkStatus.LocalWifi(
        NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1"),
    )

    private class FakeMonitor(initial: LocalNetworkStatus) : NetworkMonitor {
        val flow = MutableStateFlow(initial)
        override fun observeStatus(): Flow<LocalNetworkStatus> = flow
        override fun currentStatus(): LocalNetworkStatus = flow.value
    }

    /** A defective engine: it reports more bytes than the caps allow. */
    private class OverCapEngine : SpeedTestEngine {
        override val providerLabel = "over-cap"
        override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = 99_000_000))
            return SpeedTestResult(
                1.0, 1.0, 1L, 99_000_000, 0, 1_000, false, "over-cap", "k", null, 1L,
            )
        }
        override fun cancel() = Unit
    }

    @Test fun `bytes beyond caps are stopped as Incomplete`() = runTest {
        val repository = SpeedTestRepository(
            engine = OverCapEngine(),
            monitor = FakeMonitor(wifi),
            clock = TestAppClock(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.start()
        runCurrent()
        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Incomplete, (state as SpeedTestState.Failed).reason)
        assertFalse(repository.isRunning)
    }

    @Test fun `bytes within caps still reach Done`() = runTest {
        val engine = object : SpeedTestEngine {
            override val providerLabel = "ok"
            override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
                onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = 10))
                return SpeedTestResult(1.0, 1.0, 1L, 10L, 10L, 1_000, false, "ok", "k", null, 1L)
            }
            override fun cancel() = Unit
        }
        val repository = SpeedTestRepository(
            engine = engine,
            monitor = FakeMonitor(wifi),
            clock = TestAppClock(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.start()
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
    }

    @Test fun `over cap bytes in the terminal result are failed`() = runTest {
        val engine = object : SpeedTestEngine {
            override val providerLabel = "silent-overcap"
            override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
                // No progress at all, but the terminal result claims
                // bytes beyond the caps: still failed, never published.
                return SpeedTestResult(1.0, 1.0, 1L, 99_000_000L, 0L, 1_000, false, "silent-overcap", "k", null, 1L)
            }
            override fun cancel() = Unit
        }
        val repository = SpeedTestRepository(
            engine = engine,
            monitor = FakeMonitor(wifi),
            clock = TestAppClock(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.start()
        runCurrent()
        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Incomplete, (state as SpeedTestState.Failed).reason)
    }
}
