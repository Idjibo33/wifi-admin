package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.FakeLocalNetworkPermissionGateway
import dev.wifiadmin.app.data.FakeNetworkMonitor
import dev.wifiadmin.app.data.LocalNetworkPermissionState
import dev.wifiadmin.app.data.LocalNetworkPermissionStatus
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 15 — aucun benchmark n'est déclenché par une autorisation.
 *
 * Sans autorisation LAN requise, le moteur ne doit jamais ouvrir de socket.
 * Après accord, le moteur reste **Idle** : seul le tap utilisateur relance
 * une mesure (jamais d'auto-start, jamais de retry silencieux).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedTestPermissionGateTest {

    private val dispatcher = StandardTestDispatcher()
    private val wifi = LocalNetworkStatus.LocalWifi(
        NetworkIdentity(interfaceName = "wlan0", addressPrefix = "192.168.1.0/24", gateway = "192.168.1.1"),
    )

    @Test
    fun `permission requise bloque le demarrage sans toucher le moteur`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(wifi)
        val engine = FakeSpeedTestEngine()
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Required,
                canAskAgain = true,
            ),
        )
        val repo = SpeedTestRepository(engine, monitor, scope = this, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)

        repo.start()
        advanceUntilIdle()

        assertFalse(repo.isRunning)
        assertEquals(0L, engine.runCountSnapshot())
        val state = repo.state.value
        assertTrue(state is SpeedTestState.Failed)
        assertEquals(SpeedTestFailure.PermissionRequired, (state as SpeedTestState.Failed).reason)
    }

    @Test
    fun `accord n-auto-demarre pas de benchmark`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(wifi)
        val engine = FakeSpeedTestEngine()
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Granted,
                canAskAgain = false,
            ),
        )
        val repo = SpeedTestRepository(engine, monitor, scope = this, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)

        // L'accord de la permission ne déclenche rien par lui-même.
        assertEquals(SpeedTestState.Idle, repo.state.value)
        assertEquals(0L, engine.runCountSnapshot())

        // Seul le tap utilisateur démarre une mesure.
        repo.start()
        advanceUntilIdle()
        assertEquals(1L, engine.runCountSnapshot())
    }

    @Test
    fun `revocation annule le run en cours`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(wifi)
        val engine = FakeSpeedTestEngine(clock = null, chunkDelayMillis = 10L)
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Granted,
                canAskAgain = false,
            ),
        )
        val repo = SpeedTestRepository(engine, monitor, scope = this, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)

        repo.start()
        // Laisser la coroutine démarrer puis révoquer.
        gate.onRequestResult(granted = false, rationale = true)
        repo.onLocalNetworkPermissionRevoked()
        advanceUntilIdle()

        assertFalse(repo.isRunning)
        val state = repo.state.value
        assertTrue(state is SpeedTestState.Cancelled || state is SpeedTestState.Failed)
    }

    @Test
    fun `permission not required ne bloque pas`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(wifi)
        val engine = FakeSpeedTestEngine()
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.NotRequired,
                canAskAgain = false,
            ),
        )
        val repo = SpeedTestRepository(engine, monitor, scope = this, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)

        repo.start()
        advanceUntilIdle()
        assertEquals(1L, engine.runCountSnapshot())
    }
}
