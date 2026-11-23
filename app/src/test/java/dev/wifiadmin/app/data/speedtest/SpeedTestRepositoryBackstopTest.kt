package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
                downloadMbps = 1.0, uploadMbps = 1.0, latencyMillis = 1L,
                downloadBytes = 99_000_000, uploadBytes = 0, durationMillis = 1_000,
                capped = false, providerLabel = "over-cap", networkKey = "k",
                wifiLink = null, capturedAtWallMillis = 1L,
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
                return SpeedTestResult(
                    downloadMbps = 1.0, uploadMbps = 1.0, latencyMillis = 1L,
                    downloadBytes = 10L, uploadBytes = 10L, durationMillis = 1_000,
                    capped = false, providerLabel = "ok", networkKey = "k",
                    wifiLink = null, capturedAtWallMillis = 1L,
                )
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
                return SpeedTestResult(
                    downloadMbps = 1.0, uploadMbps = 1.0, latencyMillis = 1L,
                    downloadBytes = 99_000_000L, uploadBytes = 0L, durationMillis = 1_000,
                    capped = false, providerLabel = "silent-overcap", networkKey = "k",
                    wifiLink = null, capturedAtWallMillis = 1L,
                )
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

    /**
     * Mission 16 — the reduced budget is the one the repository enforces.
     * 1.5 Mio is under the production cap (20 Mio) but over the reduced
     * cap (1 Mio): it must be refused. This proves the repository applies
     * the reduced policy; the shared instance is wired in
     * `WifiAdminApplication`.
     */
    @Test fun `a reduced budget also bounds the repository backstop`() = runTest {
        val reduced = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 1L shl 20,
            maxUploadBytes = 256L * 1024,
            maxDurationMillis = 10_000L,
        )
        val engine = object : SpeedTestEngine {
            override val providerLabel = "reduced-overcap"
            override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
                onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = 1_500_000L))
                return SpeedTestResult(
                    downloadMbps = 1.0, uploadMbps = 1.0, latencyMillis = 1L,
                    downloadBytes = 1_500_000L, uploadBytes = 0L, durationMillis = 1_000,
                    capped = false, providerLabel = "reduced-overcap", networkKey = "k",
                    wifiLink = null, capturedAtWallMillis = 1L,
                )
            }
            override fun cancel() = Unit
        }
        val repository = SpeedTestRepository(
            engine = engine,
            monitor = FakeMonitor(wifi),
            clock = TestAppClock(),
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            policy = reduced,
        )
        repository.start()
        runCurrent()
        val state = repository.state.value
        assertTrue("STATE=$state", state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.Incomplete, (state as SpeedTestState.Failed).reason)
        // The UI reads these caps: they must be the reduced ones, not production.
        assertEquals(1L shl 20, repository.caps.maxDownloadBytes)
        assertEquals(256L * 1024, repository.caps.maxUploadBytes)
        assertEquals(10_000L, repository.caps.maxDurationMillis)
        assertFalse(repository.isRunning)
    }

    /**
     * Mission 16 — the duration cap is enforced on the wire, not only on
     * the coroutine. The fake engine blocks in a non-cooperative call
     * (like a blocking OkHttp read): without the watchdog, `withTimeout`
     * could not preempt it. The watchdog must call `cancel()`, which
     * releases the blocked call, and the run must be reported as a
     * timeout rather than a cancellation.
     */
    @Test fun `deadline cancels a blocking engine and reports a timeout`() = runBlocking {
        val released = java.util.concurrent.CountDownLatch(1)
        val engine = object : SpeedTestEngine {
            override val providerLabel = "blocking"
            override suspend fun run(
                onProgress: suspend (SpeedTestState.Running) -> Unit,
            ): SpeedTestResult {
                // Ignores coroutine cancellation: only cancel() unblocks it.
                released.await(10, java.util.concurrent.TimeUnit.SECONDS)
                throw CancellationException("released by cancel")
            }
            override fun cancel() {
                released.countDown()
            }
        }
        val repository = SpeedTestRepository(
            engine = engine,
            monitor = FakeMonitor(wifi),
            clock = TestAppClock(),
            scope = this,
            ioDispatcher = Dispatchers.IO,
            policy = SpeedTestPolicy(maxDurationMillis = 300L),
        )
        val startedAt = System.nanoTime()
        repository.start()
        val state = withTimeout(5_000L) {
            repository.state.first { it is SpeedTestState.Failed }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals(SpeedTestFailure.Timeout, (state as SpeedTestState.Failed).reason)
        assertTrue("deadline must cut the run: ${elapsedMillis}ms", elapsedMillis < 3_000L)
    }
}
