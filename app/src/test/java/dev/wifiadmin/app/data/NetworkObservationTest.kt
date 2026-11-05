package dev.wifiadmin.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network observation and the snapshot context.
 *
 * Two defects were found by the review sub-agents after the Mission 09
 * delivery:
 *
 * - the observation of the network was **never started** in the production
 *   code, so the whole feature was dead at runtime;
 * - the network identity was **never written** into the snapshot context,
 *   so the protection against a cache restored for another router did not
 *   exist.
 *
 * These tests are the regression guard: they would fail again if the
 * observation were not started, or if the identity were dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkObservationTest {

    private val dispatcher = StandardTestDispatcher()

    private val localIdentity = NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")

    /**
     * Another router, on another network.
     *
     * The interface, the address range and the gateway all differ: this is
     * the case the network identity **can** tell apart.
     */
    private val otherIdentity = NetworkIdentity("wlan1", "192.168.0.0/24", "192.168.0.1")

    // -----------------------------------------------------------------
    // The observation must be started.
    // -----------------------------------------------------------------

    @Test
    fun `the observation reaches the repository when it is started`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val repository = connectedRepository(monitor)

            val observation = repository.observeNetworkChanges(this)
            advanceUntilIdle()

            monitor.emit(LocalNetworkStatus.NoWifi)
            advanceUntilIdle()

            assertTrue(
                "the observation must reach the repository",
                repository.state.value.connection is ConnectionState.NetworkChanged
            )
            observation.cancel()
        }

    /**
     * The production container starts the observation.
     *
     * The test reads the **source**: the observation must be started by the
     * application, not only by the tests. An observation that is never
     * started would leave the feature dead at runtime, while every unit
     * test would still pass.
     */
    @Test
    fun `the application starts the network observation`() {
        val source = java.io.File(
            "src/main/java/dev/wifiadmin/app/WifiAdminApplication.kt"
        )
        assertTrue("the application source was not found", source.exists())

        val text = source.readText()
        assertTrue(
            "the application must start the network observation, otherwise " +
                "the network change detection never runs",
            text.contains("observeNetworkChanges()")
        )
    }

    // -----------------------------------------------------------------
    // The identity must be part of the snapshot context.
    // -----------------------------------------------------------------

    @Test
    fun `the snapshot context carries the identity of the network`() =
        runTest(dispatcher) {
            val monitor = FakeNetworkMonitor(LocalNetworkStatus.LocalWifi(localIdentity))
            val directory = java.nio.file.Files.createTempDirectory("context").toFile()
            val file = java.io.File(directory, SnapshotStore.FILE_NAME)
            val store = SnapshotStore(file) { 1_700_000_060_000L }
            val adapter = FakeRouterAdapter {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            val repository = TestHarness.repository(
                adapter = adapter,
                ioDispatcher = dispatcher,
                snapshotStore = store,
                scope = this,
                clock = TestHarness.virtualClock(testScheduler),
                networkMonitor = monitor,
            )
            val observation = repository.observeNetworkChanges(this)
            advanceUntilIdle()

            repository.connect("192.168.1.1", "admin", "secret")
            repository.refresh()
            advanceUntilIdle()

            val read = store.read()
            assertTrue("the snapshot must have been written", read is SnapshotRead.Found)
            val context = (read as SnapshotRead.Found).record.context
            assertEquals(
                "the context must carry the identity of the network",
                localIdentity.key,
                context.networkId
            )
            assertTrue(
                "the identity must not be empty",
                context.networkId.isNotBlank()
            )
            observation.cancel()
        }

    @Test
    fun `a snapshot is not restored for another network`() = runTest(dispatcher) {
        val directory = java.nio.file.Files.createTempDirectory("restore").toFile()
        val file = java.io.File(directory, SnapshotStore.FILE_NAME)
        val store = SnapshotStore(file) { 1_700_000_060_000L }

        // A snapshot written on the first network.
        store.write(
            DeviceSnapshotForTest,
            SnapshotContext.of(
                host = "192.168.1.1",
                account = "admin",
                networkId = localIdentity.key,
            ),
        )

        // The phone is now on another network, with the **same** account:
        // only the identity can tell the two routers apart.
        val monitor = FakeNetworkMonitor(
            LocalNetworkStatus.LocalWifi(otherIdentity)
        )
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = store,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
            networkMonitor = monitor,
        )
        val observation = repository.observeNetworkChanges(this)
        advanceUntilIdle()

        repository.connect("192.168.1.1", "admin", "secret")
        advanceUntilIdle()

        assertFalse(
            "the snapshot of another network must never be restored",
            repository.state.value.isShowingCachedSnapshot
        )
        assertEquals(
            "the snapshot of another network must be deleted",
            SnapshotRead.Missing,
            store.read()
        )
        observation.cancel()
    }

    // -----------------------------------------------------------------
    // The identity is the discriminant, not the gateway alone.
    // -----------------------------------------------------------------

    @Test
    fun `another network is a different network even with the same gateway`() {
        // Two networks, same gateway: only the identity of the session can
        // tell them apart.
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(
                NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")
            ),
            sessionHost = "192.168.1.1",
            sessionNetworkId = "wlan0|192.168.2.0/24|192.168.1.1",
        )

        assertEquals(NetworkConfidence.DIFFERENT, assessment.confidence)
    }

    @Test
    fun `the same identity is the local network`() {
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(localIdentity),
            sessionHost = "192.168.1.1",
            sessionNetworkId = localIdentity.key,
        )

        assertEquals(NetworkConfidence.LOCAL_DEFAULT, assessment.confidence)
    }

    @Test
    fun `an unknown session identity does not suspend the reads`() {
        // The doubt must not cost the user their session.
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(localIdentity),
            sessionHost = "192.168.1.1",
            sessionNetworkId = "",
        )

        assertEquals(NetworkConfidence.LOCAL_DEFAULT, assessment.confidence)
        assertFalse(assessment.isNetworkLost)
    }

    @Test
    fun `another gateway is a different network even without an identity`() {
        val assessment = assessNetwork(
            LocalNetworkStatus.LocalWifi(
                NetworkIdentity("wlan0", "192.168.0.0/24", "192.168.0.1")
            ),
            sessionHost = "192.168.1.1",
            sessionNetworkId = "",
        )

        assertEquals(NetworkConfidence.DIFFERENT, assessment.confidence)
    }

    @Test
    fun `the identity key contains the three parts of the network`() {
        val key = localIdentity.key

        assertTrue("the key must name the interface", key.contains("wlan0"))
        assertTrue("the key must name the range", key.contains("192.168.1.0/24"))
        assertTrue("the key must name the gateway", key.contains("192.168.1.1"))
        assertNotEquals(
            "two different networks must have different keys",
            localIdentity.key,
            NetworkIdentity("wlan0", "192.168.2.0/24", "192.168.1.1").key
        )
    }

    private suspend fun TestScope.connectedRepository(
        monitor: NetworkMonitor,
    ): RouterRepository {
        val repository = TestHarness.repository(
            adapter = FakeRouterAdapter(),
            ioDispatcher = dispatcher,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
            networkMonitor = monitor,
        )
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
    }

    private companion object {
        val DeviceSnapshotForTest = dev.wifiadmin.router.DeviceSnapshot(
            devices = listOf(
                dev.wifiadmin.router.RouterDevice(
                    mac = "aa:bb:cc:dd:ee:01",
                    ip = "192.168.1.10",
                    name = "poste",
                    bands = setOf(dev.wifiadmin.router.WifiBand.BAND_2_4_GHZ),
                    signal = dev.wifiadmin.router.DeviceSignal(-50, "72", "65", "maison"),
                    interfaceName = "wl0",
                    leaseExpiry = null,
                    ipv6 = null,
                    presence = dev.wifiadmin.router.DevicePresence.ACTIVE,
                )
            ),
            capturedAtMillis = 1_700_000_000_000L,
        )
    }
}
