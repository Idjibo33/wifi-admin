package dev.wifiadmin.app.ui.login

import dev.wifiadmin.app.data.FakeLocalNetworkPermissionGateway
import dev.wifiadmin.app.data.FakeRouterAdapter
import dev.wifiadmin.app.data.LocalNetworkPermissionState
import dev.wifiadmin.app.data.LocalNetworkPermissionStatus
import dev.wifiadmin.app.data.RouterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 15 — le refus de permission est visible et réparé sans redémarrage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginPermissionGateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `permission requise mappee correctement sans socket`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Required,
                canAskAgain = true,
            ),
        )
        val repo = RouterRepository(adapter, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)
        val vm = LoginViewModel(repo)

        vm.connect("192.168.1.1", "admin", "pw")
        advanceUntilIdle()

        assertEquals(0, adapter.connectCalls)
        assertTrue(vm.result.value is LoginResult.LocalNetworkPermissionDenied)
        assertFalse(vm.state.value.isConnecting)
    }

    @Test
    fun `apres accord un nouvel essai reussit`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Required,
                canAskAgain = true,
            ),
        )
        val repo = RouterRepository(adapter, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)
        val vm = LoginViewModel(repo)

        vm.connect("192.168.1.1", "admin", "pw")
        advanceUntilIdle()
        assertTrue(vm.result.value is LoginResult.LocalNetworkPermissionDenied)
        assertEquals(0, adapter.connectCalls)

        gate.onRequestResult(granted = true, rationale = false)
        repo.onLocalNetworkPermissionGranted()
        vm.clearLocalNetworkPermissionDenied()

        vm.connect("192.168.1.1", "admin", "pw")
        advanceUntilIdle()
        assertEquals(1, adapter.connectCalls)
        assertTrue(vm.result.value is LoginResult.Success)
    }

    @Test
    fun `Net et timeout restent distincts`() = runTest(dispatcher) {
        val adapter = object : dev.wifiadmin.app.data.RouterAdapter {
            override val displayName = "Fake"
            override suspend fun connect(
                host: String, username: String, password: String,
            ): Boolean = throw dev.wifiadmin.app.data.RouterUnavailableException.Timeout(null)
            override suspend fun fetchDevices() = dev.wifiadmin.router.DeviceSnapshot(emptyList())
            override fun disconnect() = Unit
        }
        val gate = FakeLocalNetworkPermissionGateway(
            initial = LocalNetworkPermissionState(
                status = LocalNetworkPermissionStatus.Granted,
                canAskAgain = false,
            ),
        )
        val repo = RouterRepository(adapter, ioDispatcher = dispatcher, localNetworkPermissionGateway = gate)
        val vm = LoginViewModel(repo)

        vm.connect("192.168.1.1", "admin", "pw")
        advanceUntilIdle()
        assertTrue(vm.result.value is LoginResult.Timeout)
    }
}
