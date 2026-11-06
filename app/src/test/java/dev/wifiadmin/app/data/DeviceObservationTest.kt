package dev.wifiadmin.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * When the application is allowed to date an observation.
 *
 * The rule is strict: **only a read that really reached the router counts**.
 * A snapshot restored from the disk, and a snapshot the coordinator reused
 * without asking the router, are not observations.
 *
 * These tests use a **real file** and a **new store instance** to read it
 * back: an in-memory fake would prove nothing about the persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceObservationTest {

    private val dispatcher = StandardTestDispatcher()

    private fun sampleDevice(mac: String = "aa:bb:cc:dd:ee:01") = dev.wifiadmin.router.RouterDevice(
        mac = mac,
        ip = "192.168.1.10",
        name = "Poste",
        bands = emptySet(),
        signal = null,
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = dev.wifiadmin.router.DevicePresence.ACTIVE,
    )

    /**
     * A store whose file survives the repository: it is the same directory
     * for the whole test.
     */
    private fun storeIn(directory: java.io.File) = DeviceMetadataStore(directory) {
        1_700_000_060_000L
    }

    @Test
    fun `a confirmed read records the observation`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("observation").toFile()
        val store = storeIn(directory)
        val repository = RouterRepository(
            adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) },
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        advanceUntilIdle()

        val metadata = repository.deviceMetadata()
        assertEquals(1, metadata.size)
        assertNotNull(
            "a confirmed read must record the observation",
            metadata["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
        assertEquals(
            1_700_000_000_000L,
            metadata["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
    }

    @Test
    fun `a snapshot restored from the disk does not record an observation`() =
        runTest(dispatcher) {
            val directory = Files.createTempDirectory("cache").toFile()
            val snapshotFile = java.io.File(directory, "snapshot.json")
            val metadataDirectory = java.io.File(directory, "metadata")

            // A snapshot that was really read, and its metadata.
            val snapshotStore = SnapshotStore(snapshotFile) { 1_700_000_060_000L }
            // The network identity is empty here: the repository has no
            // network monitor, so its context carries an empty one.
            val context = SnapshotContext.of(
                host = "192.168.1.1",
                account = "admin",
                networkId = "",
            )
            snapshotStore.write(
                dev.wifiadmin.router.DeviceSnapshot(
                    devices = listOf(sampleDevice()),
                    capturedAtMillis = 1_700_000_000_000L,
                ),
                context,
            )

            val store = DeviceMetadataStore(metadataDirectory) { 1_700_000_060_000L }
            val repository = RouterRepository(
                adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) },
                ioDispatcher = dispatcher,
                snapshotStore = snapshotStore,
                metadataStore = store,
                clock = TestHarness.virtualClock(testScheduler),
                scope = this,
                freshness = ReadFreshnessPolicy { false },
            )

            // The session opens and the snapshot is restored from the disk.
            repository.connect("192.168.1.1", "admin", "secret")
            advanceUntilIdle()

            assertTrue(
                "the snapshot must have been restored",
                repository.state.value.isShowingCachedSnapshot
            )
            assertNull(
                "a snapshot restored from the disk is NOT an observation",
                repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
            )
        }

    @Test
    fun `a reused read does not move the observation`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("reuse").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            // The production window: a read that just succeeded is reused.
            freshness = ReadFreshnessPolicy.Default,
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        advanceUntilIdle()

        val afterFirst = repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        assertNotNull("the first read must record the observation", afterFirst)
        val callsAfterFirst = adapter.fetchCalls

        // The second read happens inside the freshness window: the
        // coordinator reuses the snapshot and the router is not asked.
        repository.refresh()
        advanceUntilIdle()

        assertEquals(
            "the router must not have been asked again",
            callsAfterFirst,
            adapter.fetchCalls
        )
        assertEquals(
            "a reused read must not move the observation",
            afterFirst,
            repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
    }

    @Test
    fun `a failed read does not move the observation`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("failure").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        var fail = false
        val adapter = FakeRouterAdapter {
            if (fail) throw RouterUnavailableException.Unreachable(null)
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        advanceUntilIdle()

        val afterFirst = repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        assertNotNull(afterFirst)

        fail = true
        repository.refresh()
        advanceUntilIdle()

        assertEquals(
            "a failed read must not move the observation",
            afterFirst,
            repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
    }

    @Test
    fun `an absent payload does not move the observation`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("absent").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        var absent = false
        val adapter = FakeRouterAdapter {
            if (absent) {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L).copy(payloadAbsent = true)
            } else {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
        }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        advanceUntilIdle()

        val afterFirst = repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        assertNotNull(afterFirst)

        absent = true
        repository.refresh()
        advanceUntilIdle()

        assertEquals(
            "a reply without a device list is NOT an observation",
            afterFirst,
            repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
    }

    @Test
    fun `an empty device list confirms that no device is there`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("empty-list").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        var empty = false
        val adapter = FakeRouterAdapter {
            if (empty) {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L).copy(devices = emptyList())
            } else {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
        }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        runCurrent()

        val afterFirst = repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        assertNotNull(afterFirst)

        // The router answers with an **empty** list. That is not the same as
        // answering without a list: the router said it knows no device.
        // The device that was there keeps its last observation, because it
        // was not seen in this read.
        empty = true
        advanceTimeBy(2_000)
        repository.refresh()
        runCurrent()

        assertEquals(
            "a device that is absent keeps its last observation",
            afterFirst,
            repository.deviceMetadata()["aa:bb:cc:dd:ee:01"]?.lastSeenMillis
        )
    }

    @Test
    fun `the metadata survive a new store instance`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("persistence").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        val repository = RouterRepository(
            adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) },
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )

        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        advanceUntilIdle()
        repository.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        advanceUntilIdle()

        // A **new** instance reads the file: the data is on the disk, not in
        // a memory cache.
        val reopened = DeviceMetadataStore(directory) { 1_700_000_060_000L }
        val read = reopened.read(
            MetadataContext.of(
                host = "192.168.1.1",
                account = "admin",
                networkId = "",
            )
        )
        assertTrue("the metadata must have been written", read is MetadataRead.Found)
        val records = (read as MetadataRead.Found).records
        assertEquals("Téléphone de Marie", records.first().metadata.customName)
        assertNotNull(records.first().metadata.lastSeenMillis)
    }
}
