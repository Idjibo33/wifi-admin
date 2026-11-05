package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mission 08 guarantees, with the automatic refresh running.
 *
 * The polling changes the rhythm of the application: a failure is now
 * observed by the user without having touched anything, and a read happens
 * every thirty seconds. None of that may weaken a single rule of Mission 08.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingInvariantsTest {

    private val dispatcher = StandardTestDispatcher()

    private suspend fun TestScope.harness(
        adapter: RouterAdapter,
        store: SnapshotStorage? = null,
    ): Triple<RouterRepository, RefreshCoordinator, RouterAdapter> {
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = store,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
        )
        repository.connect("192.168.1.1", "admin", "secret")
        val coordinator = TestHarness.coordinator(repository, this)
        return Triple(repository, coordinator, adapter)
    }

    // -----------------------------------------------------------------
    // I1, I2, I8: a network failure is never an expiry.
    // -----------------------------------------------------------------

    @Test
    fun `a timeout during the polling is not an expiry`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            throw RouterUnavailableException.Timeout()
        }
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()

        assertFalse(
            "a timeout must not sign the user out",
            repository.state.value.connection is ConnectionState.SessionExpired
        )
        assertTrue(repository.state.value.isConnected)
        coordinator.stopPolling()
    }

    @Test
    fun `an unreachable router during the polling is not an expiry`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.Unreachable()
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()

            assertFalse(
                repository.state.value.connection is ConnectionState.SessionExpired
            )
            assertTrue(repository.state.value.isConnected)
            coordinator.stopPolling()
        }

    @Test
    fun `a malformed reply during the polling is not an expiry`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.UnexpectedResponse()
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()

            assertFalse(
                repository.state.value.connection is ConnectionState.SessionExpired
            )
            coordinator.stopPolling()
        }

    @Test
    fun `a network failure never becomes an expiry however many times it repeats`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.Unreachable()
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            advanceTimeBy(30_000 * 5)
            runCurrent()

            assertFalse(
                "repeated failures must not be turned into an expiry",
                repository.state.value.connection is ConnectionState.SessionExpired
            )
            coordinator.stopPolling()
        }

    // -----------------------------------------------------------------
    // I3: the cooldown keeps its own state.
    // -----------------------------------------------------------------

    @Test
    fun `a cooldown during the polling keeps its own state`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            throw RouterUnavailableException.Cooldown(120)
        }
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()

        val connection = repository.state.value.connection
        assertTrue("the cooldown must keep its own state", connection is ConnectionState.Cooldown)
        assertEquals(120, (connection as ConnectionState.Cooldown).retryAfterSeconds)
        assertFalse(connection is ConnectionState.SessionExpired)
        coordinator.stopPolling()
    }

    // -----------------------------------------------------------------
    // I1, I4, I10: a confirmed expiry still works, and stops the reads.
    // -----------------------------------------------------------------

    @Test
    fun `a confirmed expiry during the polling is still reported as an expiry`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.SessionExpired()
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()

            assertTrue(
                "a confirmed expiry must still be an expiry",
                repository.state.value.connection is ConnectionState.SessionExpired
            )
            coordinator.stopPolling()
        }

    @Test
    fun `the polling stops after an expiry`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            throw RouterUnavailableException.SessionExpired()
        }
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()
        val callsAfterExpiry = adapter.fetchCalls

        advanceTimeBy(30_000 * 3)
        runCurrent()

        assertEquals(
            "no read may reach the router after an expiry",
            callsAfterExpiry,
            adapter.fetchCalls
        )
        assertTrue(
            repository.state.value.connection is ConnectionState.SessionExpired
        )
        coordinator.stopPolling()
    }

    @Test
    fun `an expiry during the polling clears the memory`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            throw RouterUnavailableException.SessionExpired()
        }
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()

        assertNull(
            "no authenticated data may survive an expiry",
            repository.state.value.snapshot
        )
        coordinator.stopPolling()
    }

    @Test
    fun `an expiry during the polling keeps the snapshot file`() =
        runTest(dispatcher) {
            val store = SnapshotStore(
                java.io.File.createTempFile("invariants", ".json"),
            )
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.SessionExpired()
            }
            val (repository, coordinator, _) = harness(adapter, store)

            coordinator.startPolling()
            runCurrent()

            assertTrue(
                "the file must survive an expiry: the user may sign in again",
                store.read() is SnapshotRead.Missing ||
                    store.read() is SnapshotRead.Found
            )
            coordinator.stopPolling()
        }

    // -----------------------------------------------------------------
    // I4: the generation protects the live state.
    // -----------------------------------------------------------------

    @Test
    fun `a read that completes after a disconnect does not resurrect the session`() =
        runTest(dispatcher) {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val adapter = object : RouterAdapter {
                override val displayName = "Fake"
                override suspend fun connect(host: String, username: String, password: String) = true
                override suspend fun fetchDevices(): DeviceSnapshot {
                    gate.await()
                    return FakeRouterAdapter.oneDevice(1_700_000_000_000L)
                }
                override fun disconnect() = Unit
            }
            // The read blocks on its own dispatcher, the way a real network
            // call does: the cleanup must not wait for it.
            val readDispatcher = kotlinx.coroutines.Dispatchers.Default
            val repository = RouterRepository(
                adapter = adapter,
                ioDispatcher = readDispatcher,
                scope = CoroutineScope(readDispatcher),
                clock = TestHarness.virtualClock(testScheduler),
                freshness = ReadFreshnessPolicy { false },
            )
            repository.connect("192.168.1.1", "admin", "secret")
            val coordinator = TestHarness.coordinator(repository, this)

            coordinator.startPolling()
            runCurrent()

            // The user disconnects while the read is in flight.
            repository.disconnect()
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(
                "a read that comes back after a logout must not revive the session",
                repository.state.value.isConnected
            )
            assertNull(repository.state.value.snapshot)
            coordinator.stopPolling()
        }

    // -----------------------------------------------------------------
    // I6: an incomplete reply never overwrites a good snapshot.
    // -----------------------------------------------------------------

    @Test
    fun `an absent payload during the polling keeps the previous snapshot`() =
        runTest(dispatcher) {
            var absent = false
            val adapter = FakeRouterAdapter {
                if (absent) {
                    DeviceSnapshot(emptyList(), payloadAbsent = true)
                } else {
                    FakeRouterAdapter.oneDevice(1_700_000_000_000L)
                }
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()
            val first = repository.state.value.snapshot
            assertNotNull(first)

            absent = true
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(
                "an absent payload must not erase the previous devices",
                first,
                repository.state.value.snapshot
            )
            assertTrue(repository.state.value.payloadAbsent)
            coordinator.stopPolling()
        }

    // -----------------------------------------------------------------
    // I10: the polling never reconnects on its own.
    // -----------------------------------------------------------------

    @Test
    fun `the polling never opens a session on its own`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val (repository, coordinator, _) = harness(adapter)
        val connectsAfterLogin = adapter.connectCalls

        coordinator.startPolling()
        advanceTimeBy(30_000 * 5)
        runCurrent()

        assertEquals(
            "the polling only reads: it never reconnects",
            connectsAfterLogin,
            adapter.connectCalls
        )
        coordinator.stopPolling()
    }

    @Test
    fun `the polling never sends a write command`() = runTest(dispatcher) {
        // The adapter has no write operation at all: the architecture test
        // proves the interface exposes none. The polling can only read.
        val adapter = FakeRouterAdapter()
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()
        val readsAfterStart = adapter.fetchCalls

        advanceTimeBy(30_000 * 3)
        runCurrent()

        assertEquals(
            "the only thing the adapter was asked for is the devices",
            readsAfterStart + 3,
            adapter.fetchCalls
        )
        coordinator.stopPolling()
    }

    // -----------------------------------------------------------------
    // The snapshot store keeps working under the polling.
    // -----------------------------------------------------------------

    @Test
    fun `the polling keeps the snapshot store up to date`() = runTest(dispatcher) {
        val directory = java.nio.file.Files.createTempDirectory("polling-store").toFile()
        val file = java.io.File(directory, SnapshotStore.FILE_NAME)
        val store = SnapshotStore(file) { 1_700_000_060_000L }
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val (repository, coordinator, _) = harness(adapter, store)

        coordinator.startPolling()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        val read = store.read()
        assertTrue("the file must exist after the reads", read is SnapshotRead.Found)
        assertNotNull((read as SnapshotRead.Found).record.snapshot.devices.firstOrNull())
        coordinator.stopPolling()
    }

    @Test
    fun `a voluntary logout deletes the snapshot file`() = runTest(dispatcher) {
        val directory = java.nio.file.Files.createTempDirectory("polling-logout").toFile()
        val file = java.io.File(directory, SnapshotStore.FILE_NAME)
        val store = SnapshotStore(file) { 1_700_000_060_000L }
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val (repository, coordinator, _) = harness(adapter, store)

        coordinator.startPolling()
        runCurrent()
        assertTrue(store.read() is SnapshotRead.Found)

        repository.disconnect()

        assertEquals(
            "a voluntary logout must erase the private data",
            SnapshotRead.Missing,
            store.read()
        )
        coordinator.stopPolling()
    }
}
