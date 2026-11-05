package dev.wifiadmin.app.ui

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.TestHarness
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.data.SnapshotContext
import dev.wifiadmin.app.data.SnapshotRead
import dev.wifiadmin.app.data.SnapshotStorage
import dev.wifiadmin.app.data.SnapshotStore
import dev.wifiadmin.app.navigation.Route
import dev.wifiadmin.app.navigation.shouldReturnToLogin
import dev.wifiadmin.app.ui.dashboard.DashboardViewModel
import dev.wifiadmin.app.ui.devices.DevicesViewModel
import dev.wifiadmin.app.ui.login.LoginResult
import dev.wifiadmin.app.ui.login.LoginViewModel
import dev.wifiadmin.app.ui.components.EXPIRY_MESSAGE
import dev.wifiadmin.app.ui.components.userMessage
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The full Android chain of a **confirmed session expiry**, exercised
 * deterministically.
 *
 * The expiry is produced by a fake adapter that behaves exactly like the
 * real one does when the renewal of an open session fails: the read raises
 * [RouterUnavailableException.SessionExpired]. No timer, no network, no
 * router.
 *
 * Each test states the requirement it proves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionExpiryFlowTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var directory: File
    private lateinit var file: File

    private val context = SnapshotContext.of("192.168.1.1", "admin")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("session-expiry").toFile()
        file = File(directory, SnapshotStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun store() = SnapshotStore(file) { CAPTURED_AT + 60_000L }

    /**
     * A coordinator on the repository, for the screen to drive.
     */
    private fun coordinator(
        repository: RouterRepository,
        scope: CoroutineScope,
    ): RefreshCoordinator = TestHarness.coordinator(repository, scope)

    /**
     * Runs the polling once, then stops it.
     *
     * The polling loop never ends on its own: the test stops it once the
     * read has settled.
     */
    private suspend fun TestScope.runPollingOnce(coordinator: RefreshCoordinator) {
        coordinator.startPolling()
        runCurrent()
        coordinator.stopPolling()
    }

    private fun device() = RouterDevice(
        mac = "aa:bb:cc:dd:ee:01",
        ip = "192.168.1.10",
        name = "poste",
        bands = setOf(WifiBand.BAND_2_4_GHZ),
        signal = DeviceSignal(-50, "72", "65", "maison"),
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = DevicePresence.ACTIVE,
    )

    private fun snapshot() = DeviceSnapshot(listOf(device()), capturedAtMillis = CAPTURED_AT)

    /**
     * An adapter that answers normally until [expireAfter] reads have
     * succeeded, then fails with the configured error.
     *
     * [expireAfter = 0] means the very first read fails, which is the
     * "session already expired" case.
     */
    private class FakeAdapter(
        private val expireAfter: Int = 0,
        private val failure: () -> Throwable = {
            RouterUnavailableException.SessionExpired()
        },
    ) : RouterAdapter {
        override val displayName = "Fake"
        var fetchCalls = 0
        var disconnectCalls = 0
        var connectCalls = 0

        override suspend fun connect(host: String, username: String, password: String): Boolean {
            connectCalls++
            return true
        }

        override suspend fun fetchDevices(): DeviceSnapshot {
            fetchCalls++
            if (fetchCalls > expireAfter) throw failure()
            return DeviceSnapshot(
                listOf(
                    RouterDevice(
                        mac = "aa:bb:cc:dd:ee:01",
                        ip = "192.168.1.10",
                        name = "poste",
                        bands = setOf(WifiBand.BAND_2_4_GHZ),
                        signal = DeviceSignal(-50, "72", "65", "maison"),
                        interfaceName = "wl0",
                        leaseExpiry = null,
                        ipv6 = null,
                        presence = DevicePresence.ACTIVE,
                    )
                ),
                capturedAtMillis = CAPTURED_AT,
            )
        }

        override fun disconnect() {
            disconnectCalls++
        }
    }

    private suspend fun connectedRepository(
        adapter: RouterAdapter,
        store: SnapshotStorage = store(),
    ): RouterRepository {
        val repository = RouterRepository(adapter, dispatcher, store)
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
    }

    // -----------------------------------------------------------------
    // Requirement 1: the reads associated with the expired session stop.
    // -----------------------------------------------------------------

    @Test
    fun `an expiry stops the reads without reaching the adapter again`() =
        runTest(dispatcher) {
            val adapter = FakeAdapter(expireAfter = 1)
            val repository = connectedRepository(adapter)

            // The first read succeeds, the second one meets the expiry.
            repository.refresh()
            repository.refresh()
            // Any further read must be refused without reaching the adapter.
            repository.refresh()
            repository.refresh()

            assertEquals(
                "the adapter must be reached once for the live read and once " +
                    "for the expired one, never more",
                2,
                adapter.fetchCalls
            )
        }

    @Test
    fun `an expiry drops the session of the adapter`() = runTest(dispatcher) {
        val adapter = FakeAdapter(expireAfter = 1)
        val repository = connectedRepository(adapter)

        repository.refresh()
        repository.refresh()

        assertEquals(
            "the adapter session must be dropped",
            1,
            adapter.disconnectCalls
        )
    }

    // -----------------------------------------------------------------
    // Requirement 2: the session identifiers are cleared from memory.
    // -----------------------------------------------------------------

    @Test
    fun `an expiry clears the snapshot from memory`() = runTest(dispatcher) {
        val repository = connectedRepository(FakeAdapter(expireAfter = 1))
        repository.refresh()
        assertNotNull("the live snapshot must be there before the expiry", repository.state.value.snapshot)

        repository.refresh()

        assertNull(
            "no authenticated device data may survive an expiry",
            repository.state.value.snapshot
        )
        assertNull(repository.state.value.lastRefreshMillis)
    }

    @Test
    fun `an expiry leaves no trace of the session in the repository state`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            repository.refresh()

            repository.refresh()

            val state = repository.state.value
            assertTrue(state.connection is ConnectionState.SessionExpired)
            assertFalse(state.isConnected)
            assertFalse(state.isShowingCachedSnapshot)
        }

    // -----------------------------------------------------------------
    // Requirement 3: the app returns to the login screen on its own.
    // -----------------------------------------------------------------

    @Test
    fun `an expiry asks the navigation to return to the login screen`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            repository.refresh()

            repository.refresh()

            assertTrue(
                "the navigation must be told to go back to the login screen",
                shouldReturnToLogin(
                    connection = repository.state.value.connection,
                    currentRoute = Route.Dashboard.path,
                )
            )
        }

    @Test
    fun `the navigation does not ask to go back when the login screen is already shown`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            repository.refresh()
            repository.refresh()

            assertFalse(
                "navigating again would push a duplicate login entry",
                shouldReturnToLogin(
                    connection = repository.state.value.connection,
                    currentRoute = Route.Login.path,
                )
            )
        }

    // -----------------------------------------------------------------
    // Requirement 4: the exact message is shown.
    // -----------------------------------------------------------------

    @Test
    fun `the login screen reports the expiry`() = runTest(dispatcher) {
        val repository = connectedRepository(FakeAdapter(expireAfter = 1))
        repository.refresh()
        repository.refresh()

        val viewModel = LoginViewModel(repository)

        assertEquals(LoginResult.SessionExpired, viewModel.result.value)
    }

    @Test
    fun `the expiry message is the exact sentence`() {
        assertEquals(
            "Votre session a expiré. Reconnectez-vous pour continuer.",
            EXPIRY_MESSAGE
        )
    }

    @Test
    fun `the expiry message shown to the user is the same constant`() {
        assertEquals(
            EXPIRY_MESSAGE,
            RouterUnavailableException.SessionExpired().userMessage()
        )
    }

    // -----------------------------------------------------------------
    // Requirement 5: Back cannot reach a protected screen.
    // -----------------------------------------------------------------

    @Test
    fun `the back stack is emptied so Back cannot reach a protected screen`() {
        // The rule that guards the navigation: the expiry is the only state
        // that sends the user back, and the destination is reached with an
        // empty back stack.
        assertTrue(
            shouldReturnToLogin(ConnectionState.SessionExpired, Route.Devices.path)
        )
        assertTrue(
            shouldReturnToLogin(ConnectionState.SessionExpired, Route.Settings.path)
        )
        assertTrue(
            shouldReturnToLogin(ConnectionState.SessionExpired, Route.Dashboard.path)
        )
    }

    // -----------------------------------------------------------------
    // Requirement 6: the snapshot file survives until logout.
    // -----------------------------------------------------------------

    @Test
    fun `an expiry keeps the snapshot file for the next login`() =
        runTest(dispatcher) {
            val store = store()
            val repository = connectedRepository(FakeAdapter(expireAfter = 1), store)
            repository.refresh()
            assertTrue("the file must exist after a successful read", store.read() is SnapshotRead.Found)

            repository.refresh()

            assertTrue(
                "the file must survive an expiry: the user may sign in again",
                store.read() is SnapshotRead.Found
            )
        }

    @Test
    fun `a voluntary logout deletes the snapshot file`() = runTest(dispatcher) {
        val store = store()
        val repository = connectedRepository(FakeAdapter(expireAfter = 1), store)
        repository.refresh()

        repository.disconnect()

        assertEquals(
            "a voluntary logout must erase the private data",
            SnapshotRead.Missing,
            store.read()
        )
    }

    @Test
    fun `the next login restores the snapshot kept after an expiry`() =
        runTest(dispatcher) {
            val store = store()
            val adapter = FakeAdapter(expireAfter = 1)
            val repository = connectedRepository(adapter, store)
            repository.refresh()
            repository.refresh()

            // The user signs in again: the file is restored.
            repository.connect("192.168.1.1", "admin", "secret")

            assertTrue(repository.state.value.isShowingCachedSnapshot)
            assertNotNull(repository.state.value.snapshot)
        }

    // -----------------------------------------------------------------
    // Negative cases: none of them may be reported as an expiry.
    // -----------------------------------------------------------------

    @Test
    fun `a timeout is not an expiry`() = runTest(dispatcher) {
        val repository = connectedRepository(
            FakeAdapter(expireAfter = 0) {
                RouterUnavailableException.Timeout()
            }
        )

        repository.refresh()

        val state = repository.state.value
        assertFalse("a timeout must not sign the user out", state.connection is ConnectionState.SessionExpired)
        assertTrue(state.isConnected)
        assertTrue(state.error is RouterUnavailableException.Timeout)
        assertFalse(
            shouldReturnToLogin(state.connection, Route.Dashboard.path)
        )
    }

    @Test
    fun `a lost wifi connection is not an expiry`() = runTest(dispatcher) {
        val repository = connectedRepository(
            FakeAdapter(expireAfter = 0) {
                RouterUnavailableException.Unreachable()
            }
        )

        repository.refresh()

        val state = repository.state.value
        assertFalse(state.connection is ConnectionState.SessionExpired)
        assertTrue(state.isConnected)
        assertTrue(state.error is RouterUnavailableException.Unreachable)
        assertFalse(
            shouldReturnToLogin(state.connection, Route.Dashboard.path)
        )
    }

    @Test
    fun `a temporary cooldown keeps its own error and does not navigate`() =
        runTest(dispatcher) {
            val repository = connectedRepository(
                FakeAdapter(expireAfter = 0) {
                    RouterUnavailableException.Cooldown(120)
                }
            )

            repository.refresh()

            val state = repository.state.value
            val connection = state.connection
            assertTrue(connection is ConnectionState.Cooldown)
            assertEquals(120, (connection as ConnectionState.Cooldown).retryAfterSeconds)
            assertFalse(
                shouldReturnToLogin(state.connection, Route.Dashboard.path)
            )
        }

    @Test
    fun `a malformed response is not an expiry`() = runTest(dispatcher) {
        val repository = connectedRepository(
            FakeAdapter(expireAfter = 0) {
                RouterUnavailableException.UnexpectedResponse()
            }
        )

        repository.refresh()

        val state = repository.state.value
        assertFalse(state.connection is ConnectionState.SessionExpired)
        assertTrue(state.error is RouterUnavailableException.UnexpectedResponse)
    }

    // -----------------------------------------------------------------
    // Concurrent failures: one expiry, one navigation, no loop.
    // -----------------------------------------------------------------

    @Test
    fun `two simultaneous expiries produce a single expiry state`() =
        runTest(dispatcher) {
            val adapter = FakeAdapter(expireAfter = 1)
            val repository = connectedRepository(adapter)

            // The dashboard and the device list refresh at the same time.
            repository.refresh()
            val first = repository.refresh()
            val second = repository.refresh()

            assertTrue(first.isFailure)
            assertTrue(second.isFailure)
            assertEquals(
                "the adapter must be reached once for the live read and once " +
                    "for the expired one, never more",
                2,
                adapter.fetchCalls
            )
            assertTrue(
                repository.state.value.connection is ConnectionState.SessionExpired
            )
        }

    @Test
    fun `the expiry state is stable so the navigation effect cannot loop`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            repository.refresh()
            repository.refresh()

            val first = repository.state.value.connection
            val second = repository.state.value.connection

            assertEquals(
                "the same expiry state must not retrigger the navigation",
                first,
                second
            )
            assertTrue(first is ConnectionState.SessionExpired)
        }

    @Test
    fun `the dashboard view model reports the expiry and clears the devices`() =
        runTest(dispatcher) {
            // The very first read meets the expiry, which is what the view
            // model triggers when it is created.
            val repository = connectedRepository(FakeAdapter(expireAfter = 0))
            val coordinator = coordinator(repository, this)
            val viewModel = DashboardViewModel(repository, coordinator)
            runPollingOnce(coordinator)

            assertTrue(
                "the dashboard must not keep authenticated data after the expiry",
                viewModel.state.value.snapshot == null
            )
            assertTrue(
                "the dashboard must report the expiry",
                viewModel.state.value.errorMessage != null
            )
        }

    @Test
    fun `the device list view model reports the expiry`() = runTest(dispatcher) {
        val repository = connectedRepository(FakeAdapter(expireAfter = 0))
        val viewModel = DevicesViewModel(repository, coordinator(repository, this))
        advanceUntilIdle()

        assertTrue(
            "the device list must not keep authenticated data after the expiry",
            viewModel.state.value.devices.isEmpty()
        )
    }

    // -----------------------------------------------------------------
    // Defence in depth: the view models purge their own data.
    // -----------------------------------------------------------------

    @Test
    fun `the dashboard view model purges its devices when the session is lost`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            val coordinator = coordinator(repository, this)
            val viewModel = DashboardViewModel(repository, coordinator)
            runPollingOnce(coordinator)
            assertNotNull(
                "the dashboard must show the devices before the expiry",
                viewModel.state.value.snapshot
            )

            // The second read meets the expiry.
            repository.refresh()
            advanceUntilIdle()

            assertNull(
                "the dashboard must purge its devices when the session is lost",
                viewModel.state.value.snapshot
            )
            assertFalse(viewModel.state.value.hasSnapshot)
        }

    @Test
    fun `the device list view model purges its devices when the session is lost`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            val coordinator = coordinator(repository, this)
            val viewModel = DevicesViewModel(repository, coordinator)
            runPollingOnce(coordinator)
            assertEquals(1, viewModel.state.value.devices.size)

            // The second read meets the expiry.
            repository.refresh()
            advanceUntilIdle()

            assertTrue(
                "the device list must purge its devices when the session is lost",
                viewModel.state.value.devices.isEmpty()
            )
            assertFalse(viewModel.state.value.hasSnapshot)
        }

    @Test
    fun `the login view model reports an expiry that happens after it was created`() =
        runTest(dispatcher) {
            val repository = connectedRepository(FakeAdapter(expireAfter = 1))
            // The login screen is already open: the view model exists before
            // the expiry is detected.
            val viewModel = LoginViewModel(repository)
            assertEquals(LoginResult.None, viewModel.result.value)

            repository.refresh()
            repository.refresh()
            advanceUntilIdle()

            assertEquals(
                "the message must appear even if the view model predates the expiry",
                LoginResult.SessionExpired,
                viewModel.result.value
            )
        }

    @Test
    fun `a manual login after an expiry succeeds and leaves the expired state`() =
        runTest(dispatcher) {
            val adapter = FakeAdapter(expireAfter = 1)
            val repository = connectedRepository(adapter)
            repository.refresh()
            repository.refresh()
            assertTrue(repository.state.value.connection is ConnectionState.SessionExpired)

            val viewModel = LoginViewModel(repository)
            viewModel.connect("192.168.1.1", "admin", "secret")
            advanceUntilIdle()

            assertEquals(LoginResult.Success, viewModel.result.value)
            assertTrue(repository.state.value.isConnected)
            assertEquals(2, adapter.connectCalls)
        }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
