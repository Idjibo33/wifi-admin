package dev.wifiadmin.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a change of network means for the session.
 *
 * The rule that matters: **a network change is not a session expiry.** The
 * session is suspended, not ended, and the last known devices stay on
 * screen. Mission 08 says a network failure is never an expiry, and a
 * changed network is a network failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkChangeTest {

    private val dispatcher = StandardTestDispatcher()

    private val localIdentity = NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")
    private val otherIdentity = NetworkIdentity("wlan0", "192.168.0.0/24", "192.168.0.1")

    // -----------------------------------------------------------------
    // The assessment rule, which is a pure function.
    // -----------------------------------------------------------------

    @Test
    fun `the local wifi network of the router is the local default network`() {
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(localIdentity),
            "192.168.1.1",
        )

        assertEquals(NetworkConfidence.LOCAL_DEFAULT, assessment.confidence)
        assertFalse(assessment.isNetworkLost)
    }

    @Test
    fun `a local wifi network without internet is not rejected`() {
        // The rule does not even know about the Internet: a local network
        // without it must stay usable.
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(localIdentity),
            "192.168.1.1",
        )

        assertEquals(NetworkConfidence.LOCAL_DEFAULT, assessment.confidence)
    }

    @Test
    fun `the loss of the wifi is the absence of the local network`() {
        val assessment = assessNetwork(LocalNetworkStatus.NoWifi, "192.168.1.1")

        assertEquals(NetworkConfidence.ABSENT, assessment.confidence)
        assertTrue(assessment.isNetworkLost)
    }

    @Test
    fun `the mobile data is not the local network`() {
        val assessment = assessNetwork(LocalNetworkStatus.NotOnWifi, "192.168.1.1")

        assertEquals(NetworkConfidence.ABSENT, assessment.confidence)
    }

    @Test
    fun `another wifi network is a different network`() {
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(otherIdentity),
            "192.168.1.1",
        )

        assertEquals(NetworkConfidence.DIFFERENT, assessment.confidence)
        assertTrue(assessment.isNetworkLost)
    }

    @Test
    fun `without a session the network is only described`() {
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(localIdentity),
            sessionHost = null,
        )

        assertEquals(NetworkConfidence.LOCAL_DEFAULT, assessment.confidence)
        assertNull(assessment.sessionHost)
    }

    // -----------------------------------------------------------------
    // The repository, with the session open.
    // -----------------------------------------------------------------

    @Test
    fun `the loss of the wifi does not sign the user out`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
        val repository = connectedRepository(monitor)

        observingNetwork(repository) {
            monitor.emit(LocalNetworkStatus.NoWifi)
            advanceUntilIdle()
        }

        assertFalse(
            "a network change must never expire the session",
            repository.state.value.connection is ConnectionState.SessionExpired
        )
        assertTrue(
            "the session is suspended, not ended",
            repository.state.value.connection is ConnectionState.NetworkChanged
        )
    }

    @Test
    fun `a network change suspends the reads`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
        val adapter = FakeRouterAdapter()
        val repository = connectedRepository(monitor, adapter)

        observingNetwork(repository) {
            monitor.emit(LocalNetworkStatus.NoWifi)
            advanceUntilIdle()
        }

        assertTrue(
            "the reads must be suspended",
            repository.state.value.connection is ConnectionState.NetworkChanged
        )

        val callsBefore = adapter.fetchCalls
        repository.refresh()
        advanceUntilIdle()

        assertEquals(
            "no read may reach the router while the network is wrong",
            callsBefore,
            adapter.fetchCalls
        )
    }

    @Test
    fun `a network change does not sign the user out`() = runTest(dispatcher) {
        val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
        val repository = connectedRepository(monitor)

        observingNetwork(repository) {
            monitor.emit(LocalNetworkStatus.LocalWifi(otherIdentity))
            advanceUntilIdle()
        }

        assertFalse(
            "a changed network is not an expiry",
            repository.state.value.connection is ConnectionState.SessionExpired
        )
    }

    @Test
    fun `a network change keeps the last known snapshot on screen`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val adapter = FakeRouterAdapter {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            val repository = connectedRepository(monitor, adapter)
            repository.refresh()
            advanceUntilIdle()
            assertNotNull(repository.state.value.snapshot)

            observingNetwork(repository) {
                monitor.emit(LocalNetworkStatus.NoWifi)
                advanceUntilIdle()
            }

            assertNotNull(
                "the last known devices stay on screen",
                repository.state.value.snapshot
            )
        }

    @Test
    fun `coming back to the right network resumes the reads`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val adapter = FakeRouterAdapter {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            val repository = connectedRepository(monitor, adapter)

            observingNetwork(repository) {
                monitor.emit(LocalNetworkStatus.NoWifi)
                advanceUntilIdle()
                assertTrue(
                    repository.state.value.connection is ConnectionState.NetworkChanged
                )

                monitor.emit(LocalNetworkStatus.LocalWifi(localIdentity))
                advanceUntilIdle()
            }

            assertTrue(
                "the session is valid again",
                repository.state.value.isConnected
            )
        }

    @Test
    fun `a network change never returns to the login screen`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val repository = connectedRepository(monitor)

            observingNetwork(repository) {
                monitor.emit(LocalNetworkStatus.NoWifi)
                advanceUntilIdle()
            }

            val connection = repository.state.value.connection
            assertFalse(
                "a network change must not sign the user out",
                dev.wifiadmin.app.navigation.shouldReturnToLogin(
                    connection,
                    dev.wifiadmin.app.navigation.Route.Dashboard.path,
                )
            )
            assertFalse(
                "a network change must not leave the protected screen",
                dev.wifiadmin.app.navigation.shouldLeaveProtectedScreen(
                    connection,
                    dev.wifiadmin.app.navigation.Route.Dashboard.path,
                )
            )
        }

    @Test
    fun `the session is not invalidated by a network change`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val repository = connectedRepository(monitor)

            observingNetwork(repository) {
                monitor.emit(LocalNetworkStatus.NoWifi)
                advanceUntilIdle()
            }

            assertTrue(
                "the session may still be valid: the user may come back",
                repository.state.value.isConnected ||
                    repository.state.value.connection is ConnectionState.NetworkChanged
            )
        }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Observes the network changes while [body] runs, then stops.
     *
     * The collector waits for ever, and the test must not wait for it: the
     * observation is always cancelled, even when an assertion fails.
     */
    private suspend fun TestScope.observingNetwork(
        repository: RouterRepository,
        body: suspend () -> Unit,
    ) {
        val observation = repository.observeNetworkChanges(this)
        try {
            body()
        } finally {
            observation.cancel()
        }
    }

    private suspend fun TestScope.connectedRepository(
        monitor: NetworkMonitor,
        adapter: FakeRouterAdapter = FakeRouterAdapter(),
    ): RouterRepository {
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
            networkMonitor = monitor,
        )
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
    }
}
