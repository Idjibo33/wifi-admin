package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.TestAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam repository (sans MockWebServer) : changement d'identité Wi-Fi pendant un run.
 * Attendu : état Cancelled, aucun retry automatique, slot libéré pour un nouveau start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestNetworkLossRepoTest {

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

    private class GatedEngine : SpeedTestEngine {
        override val providerLabel: String = "gated"

        var runs = 0
            private set
        var cancelCalls = 0
            private set
        var cancelledRuns = 0
            private set
        var gate = CompletableDeferred<SpeedTestResult>()

        override suspend fun run(
            onProgress: suspend (SpeedTestState.Running) -> Unit,
        ): SpeedTestResult {
            runs++
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
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

    @Test
    fun `changement identite wifi pendant run donne Cancelled sans retry et slot libere`() = runTest {
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

        assertEquals("aucun retry automatique", 1, engine.runs)
        assertEquals(1, engine.cancelledRuns)
        assertTrue(engine.cancelCalls >= 1)
        assertTrue(
            "changement identite = Cancelled, etat=${repository.state.value}",
            repository.state.value is SpeedTestState.Cancelled,
        )
        assertFalse("slot libere apres annulation", repository.isRunning)

        engine.gate = CompletableDeferred()
        repository.start()
        runCurrent()
        assertEquals(2, engine.runs)
        assertTrue(repository.isRunning)
        engine.gate.complete(result("reseau-b"))
        runCurrent()
        assertTrue(repository.state.value is SpeedTestState.Done)
        assertFalse(repository.isRunning)

        watcher.cancel()
        runCurrent()
    }
}
