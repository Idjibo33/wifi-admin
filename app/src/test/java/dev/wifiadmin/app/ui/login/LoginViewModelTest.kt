package dev.wifiadmin.app.ui.login

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** An adapter that records what it was asked to do. */
    private class FakeAdapter(
        private val accepted: Boolean = true,
        private val error: RouterUnavailableException? = null
    ) : RouterAdapter {
        override val displayName = "Fake"
        var connectCalls = 0
        var lastHost: String? = null
        var lastUsername: String? = null
        var lastPassword: String? = null

        override suspend fun connect(host: String, username: String, password: String): Boolean {
            connectCalls++
            lastHost = host
            lastUsername = username
            lastPassword = password
            error?.let { throw it }
            return accepted
        }

        override suspend fun fetchDevices(): DeviceSnapshot = DeviceSnapshot(emptyList())

        override fun disconnect() = Unit
    }

    /** An adapter whose connection attempt never completes on its own. */
    private class SuspendingAdapter : RouterAdapter {
        override val displayName = "Fake"
        var connectCalls = 0

        override suspend fun connect(host: String, username: String, password: String): Boolean {
            connectCalls++
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun fetchDevices(): DeviceSnapshot = DeviceSnapshot(emptyList())

        override fun disconnect() = Unit
    }


    /**
     * A repository whose network calls run on the test dispatcher, so the
     * coroutines stay under the control of the test.
     */
    private fun testRepository(adapter: RouterAdapter) =
        RouterRepository(adapter, dispatcher)

    @Test
    fun `the form starts with the default host and username`() {
        val vm = LoginViewModel(testRepository(FakeAdapter()))

        assertEquals("192.168.1.1", vm.state.value.host)
        assertEquals("admin", vm.state.value.username)
    }

    @Test
    fun `a successful connection reports success`() = runTest(dispatcher) {
        val vm = LoginViewModel(testRepository(FakeAdapter(accepted = true)))

        vm.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertEquals(LoginResult.Success, vm.result.value)
    }

    @Test
    fun `a refused connection reports invalid credentials`() = runTest(dispatcher) {
        val vm = LoginViewModel(testRepository(FakeAdapter(accepted = false)))

        vm.connect("192.168.1.1", "admin", "wrong")
        advanceUntilIdle()

        assertEquals(LoginResult.InvalidCredentials, vm.result.value)
    }

    /**
     * A wrong password must never be reported as a success: the login screen
     * only navigates away on [LoginResult.Success].
     */
    @Test
    fun `a refused connection never reports success`() = runTest(dispatcher) {
        val vm = LoginViewModel(testRepository(FakeAdapter(accepted = false)))

        vm.connect("192.168.1.1", "admin", "wrong")
        advanceUntilIdle()

        assertFalse(vm.result.value == LoginResult.Success)
    }

    @Test
    fun `a cooldown reports how long to wait`() = runTest(dispatcher) {
        val adapter = FakeAdapter(error = RouterUnavailableException.Cooldown(37))
        val vm = LoginViewModel(testRepository(adapter))

        vm.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertEquals(LoginResult.Cooldown(37), vm.result.value)
    }

    @Test
    fun `an unreachable router reports a network error`() = runTest(dispatcher) {
        val adapter = FakeAdapter(error = RouterUnavailableException.Unreachable(null))
        val vm = LoginViewModel(testRepository(adapter))

        vm.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertEquals(LoginResult.NetworkError, vm.result.value)
    }

    @Test
    fun `the host and username are trimmed before being sent`() = runTest(dispatcher) {
        val adapter = FakeAdapter()
        val vm = LoginViewModel(testRepository(adapter))

        vm.connect("  192.168.1.1  ", "  admin  ", "secret")
        advanceUntilIdle()

        assertEquals("192.168.1.1", adapter.lastHost)
        assertEquals("admin", adapter.lastUsername)
    }

    @Test
    fun `the password is never stored in the state`() = runTest(dispatcher) {
        val adapter = FakeAdapter()
        val vm = LoginViewModel(testRepository(adapter))

        vm.connect("192.168.1.1", "admin", "super-secret")
        advanceUntilIdle()

        assertFalse(vm.state.value.toString().contains("super-secret"))
        assertFalse(adapter.lastPassword.orEmpty().isEmpty())
    }

    @Test
    fun `connecting twice while the first attempt is in flight is refused`() = runTest(dispatcher) {
        val adapter = SuspendingAdapter()
        val vm = LoginViewModel(testRepository(adapter))

        vm.connect("192.168.1.1", "admin", "secret")
        // The first attempt is still suspended: a second one must be refused.
        vm.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertEquals(1, adapter.connectCalls)
    }

    @Test
    fun `the repository reflects the connected state`() = runTest(dispatcher) {
        val repository = testRepository(FakeAdapter())
        val vm = LoginViewModel(repository)

        vm.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertTrue(repository.state.value.isConnected)
        assertEquals(ConnectionState.Connected, repository.state.value.connection)
    }
}
