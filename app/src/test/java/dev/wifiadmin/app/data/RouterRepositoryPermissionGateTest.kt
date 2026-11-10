package dev.wifiadmin.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 15 — aucune connexion LAN prématurée, gérée sans redémarrage.
 *
 * Le repository ne doit jamais ouvrir un socket tant que l'autorisation
 * requise est absente (API 37+), mais il ne doit pas non plus perdre la
 * session ni le snapshot quand elle est révoquée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouterRepositoryPermissionGateTest {

    private val dispatcher = StandardTestDispatcher()

    private fun gateway(status: LocalNetworkPermissionStatus): FakeLocalNetworkPermissionGateway {
        val gateway = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = when (status) {
                    LocalNetworkPermissionStatus.Required -> LocalNetworkPermissionStatus.Required
                    LocalNetworkPermissionStatus.Refused -> LocalNetworkPermissionStatus.Required
                    LocalNetworkPermissionStatus.PermanentlyRefused -> LocalNetworkPermissionStatus.Required
                    LocalNetworkPermissionStatus.NotRequired -> LocalNetworkPermissionStatus.NotRequired
                    LocalNetworkPermissionStatus.Granted -> LocalNetworkPermissionStatus.NotRequired
                },
                canAskAgain = status == LocalNetworkPermissionStatus.Required ||
                    status == LocalNetworkPermissionStatus.Refused,
            ),
        )
        when (status) {
            LocalNetworkPermissionStatus.NotRequired -> {
                gateway.onRequestResult(granted = true, rationale = false)
            }
            LocalNetworkPermissionStatus.Granted -> {
                gateway.onRequestResult(granted = true, rationale = false)
            }
            LocalNetworkPermissionStatus.Required -> {
                // Jamais demandée.
            }
            LocalNetworkPermissionStatus.Refused -> {
                gateway.requestIfNeeded { }
                gateway.onRequestResult(granted = false, rationale = true)
            }
            LocalNetworkPermissionStatus.PermanentlyRefused -> {
                gateway.requestIfNeeded { }
                gateway.onRequestResult(granted = false, rationale = true)
                gateway.requestIfNeeded { }
                gateway.onRequestResult(granted = false, rationale = false)
            }
        }
        return gateway
    }

    @Test
    fun `permission accordee autorise la connexion`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val gate = gateway(LocalNetworkPermissionStatus.Granted)
        val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)

        val ok = repo.connect("192.168.1.1", "admin", "pw")
        assertTrue(ok)
        assertEquals(1, adapter.connectCalls)
    }

    @Test
    fun `permission requise bloque la connexion sans socket`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val gate = gateway(LocalNetworkPermissionStatus.Required)
        val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)

        val ok = repo.connect("192.168.1.1", "admin", "pw")
        assertFalse(ok)
        assertEquals(0, adapter.connectCalls)
        assertTrue(
            repo.state.value.error is RouterUnavailableException.LocalNetworkPermissionDenied,
        )
    }

    @Test
    fun `permission refusee bloque la connexion sans socket`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val gate = gateway(LocalNetworkPermissionStatus.Refused)
        val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)

        val ok = repo.connect("192.168.1.1", "admin", "pw")
        assertFalse(ok)
        assertEquals(0, adapter.connectCalls)
        assertTrue(
            repo.state.value.error is RouterUnavailableException.LocalNetworkPermissionDenied,
        )
    }

    @Test
    fun `autorisation apres refus permet de reconnecter sans redemarrage sans double requete`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val gate = gateway(LocalNetworkPermissionStatus.Required)
            val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)

            assertFalse(repo.connect("192.168.1.1", "admin", "pw"))
            assertEquals(0, adapter.connectCalls)

            // L'utilisateur accorde dans le dialogue système.
            gate.onRequestResult(granted = true, rationale = false)
            repo.onLocalNetworkPermissionGranted()

            // Une seule connexion après accord, pas de retry auto double.
            assertTrue(repo.connect("192.168.1.1", "admin", "pw"))
            assertEquals(1, adapter.connectCalls)
        }

    @Test
    fun `revocation conserve la session et le snapshot sans re-ouvrir de socket`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val gate = gateway(LocalNetworkPermissionStatus.Granted)
            val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)
            assertTrue(repo.connect("192.168.1.1", "admin", "pw"))
            val afterConnect = adapter.fetchCalls
            repo.refreshForced().isSuccess
            val snapshotBefore = repo.state.value.snapshot

            // L'utilisateur révoque depuis les réglages.
            gate.onRequestResult(granted = false, rationale = true)
            repo.onLocalNetworkPermissionRevoked()

            // Une lecture demandée ne doit pas atteindre l'adaptateur.
            val failed = repo.refreshForced()
            assertTrue(failed.isFailure)
            assertTrue(
                (failed.exceptionOrNull() as? RouterUnavailableException)
                    is RouterUnavailableException.LocalNetworkPermissionDenied
                    || repo.state.value.error is RouterUnavailableException.LocalNetworkPermissionDenied,
            )
            assertEquals(afterConnect + 1, adapter.fetchCalls)
            // La session et le snapshot sont conservés (pas d'expiry).
            assertTrue(repo.state.value.connection is ConnectionState.Connected)
            assertEquals(snapshotBefore, repo.state.value.snapshot)
            assertFalse(repo.state.value.connection is ConnectionState.SessionExpired)
        }

    @Test
    fun `retour des reglages apres re-accord reprend une lecture unique`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val gate = gateway(LocalNetworkPermissionStatus.Granted)
            val repo = RouterRepository(adapter, localNetworkPermissionGateway = gate)
            assertTrue(repo.connect("192.168.1.1", "admin", "pw"))
            val callsBefore = adapter.fetchCalls

            gate.onRequestResult(granted = false, rationale = true)
            repo.onLocalNetworkPermissionRevoked()
            repo.refreshForced() // bloqué, pas d'appel réseau
            assertEquals(callsBefore, adapter.fetchCalls)

            // L'utilisateur réaccorde dans les réglages.
            gate.onRequestResult(granted = true, rationale = false)
            repo.onLocalNetworkPermissionGranted()

            val afterGrant = adapter.fetchCalls
            assertTrue(repo.refreshForced().isSuccess)
            assertEquals(afterGrant + 1, adapter.fetchCalls)
            assertEquals(null, repo.state.value.error)
        }
}
