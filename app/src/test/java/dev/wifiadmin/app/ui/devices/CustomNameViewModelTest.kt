package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.DeviceMetadataStore
import dev.wifiadmin.app.data.FakeRouterAdapter
import dev.wifiadmin.app.data.MetadataContext
import dev.wifiadmin.app.data.MetadataRead
import dev.wifiadmin.app.data.ReadFreshnessPolicy
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.data.TestAppClock
import dev.wifiadmin.app.data.TestHarness
import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.nio.file.Files

/**
 * The custom names, seen through the screen's view model.
 *
 * The names are **local**: the router is never told about them, and the
 * filtering and the renaming never trigger a read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomNameViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun context() = MetadataContext.of(
        host = "192.168.1.1",
        account = "admin",
        networkId = "",
    )

    private fun repository(
        adapter: FakeRouterAdapter,
        directory: java.io.File,
        scope: kotlinx.coroutines.CoroutineScope,
        clock: dev.wifiadmin.app.data.AppClock,
    ): RouterRepository = RouterRepository(
        adapter = adapter,
        ioDispatcher = dispatcher,
        snapshotStore = null,
        metadataStore = DeviceMetadataStore(directory) { 1_700_000_060_000L },
        clock = clock,
        scope = scope,
        freshness = ReadFreshnessPolicy { false },
    )

    /**
     * Builds the view model and starts the polling, exactly as the screen
     * does when it becomes visible.
     *
     * The reads belong to the coordinator, which is shared with the
     * dashboard: the view model observes them.
     */
    private fun viewModel(
        repository: RouterRepository,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DevicesViewModel, dev.wifiadmin.app.data.RefreshCoordinator> {
        val coordinator = TestHarness.coordinator(repository, scope)
        coordinator.startPolling()
        return DevicesViewModel(repository, coordinator) to coordinator
    }

    // -----------------------------------------------------------------
    // The rename
    // -----------------------------------------------------------------

    @Test
    fun `a device is renamed and the router's name stays available`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("rename").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        runCurrent()

        val row = vm.state.value.rows.first()
        assertEquals("Téléphone de Marie", row.displayName)
        assertTrue(row.hasCustomName)
        // The router's own name is still there, under the custom one.
        assertEquals("poste", row.originalName)
        coordinator.stopPolling()
    }

    @Test
    fun `a custom name is removed and the router's name comes back`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("remove").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        runCurrent()
        vm.clearDeviceName("aa:bb:cc:dd:ee:01")
        runCurrent()

        val row = vm.state.value.rows.first()
        assertFalse(row.hasCustomName)
        assertEquals("poste", row.displayName)
        coordinator.stopPolling()
    }

    @Test
    fun `a custom name survives a change of address`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("ip-change").toFile()
        var ip = "192.168.1.10"
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L).let { snapshot ->
                snapshot.copy(
                    devices = snapshot.devices.map { it.copy(ip = ip) }
                )
            }
        }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        runCurrent()

        // The DHCP lease is renewed: the address changes, the device does
        // not. The clock is moved past the freshness window so the read
        // really reaches the router.
        ip = "192.168.1.42"
        advanceTimeBy(2_000)
        vm.refresh()
        runCurrent()
        runCurrent()

        val row = vm.state.value.rows.first()
        assertEquals("192.168.1.42", row.device.ip)
        assertEquals(
            "the custom name must follow the device across an address change",
            "Téléphone de Marie",
            row.displayName
        )
        coordinator.stopPolling()
    }

    @Test
    fun `a device without a usable identity cannot be renamed`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("no-identity").toFile()
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L).let { snapshot ->
                snapshot.copy(
                    devices = snapshot.devices.map { it.copy(mac = "") }
                )
            }
        }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.renameDevice("", "Téléphone de Marie")
        runCurrent()

        val row = vm.state.value.rows.first()
        assertNull(row.key)
        assertFalse(
            "a device without an identity must not carry a custom name",
            row.hasCustomName
        )
        coordinator.stopPolling()
    }

    @Test
    fun `a custom name is not sent to the router`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("no-write").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        val callsBefore = adapter.fetchCalls
        vm.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        runCurrent()

        assertEquals(
            "a rename must not reach the router",
            callsBefore,
            adapter.fetchCalls
        )
        assertEquals(0, adapter.connectCalls - 1)
        coordinator.stopPolling()
    }

    // -----------------------------------------------------------------
    // The local filtering
    // -----------------------------------------------------------------

    @Test
    fun `the filtering never triggers a read`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("local").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        val callsBefore = adapter.fetchCalls

        vm.setQuery("poste")
        vm.setFilter(DeviceBandFilter.BAND_5)
        vm.setSort(DeviceSort.SIGNAL)
        runCurrent()
        runCurrent()

        assertEquals(
            "the filtering must be purely local",
            callsBefore,
            adapter.fetchCalls
        )
        coordinator.stopPolling()
    }

    @Test
    fun `the filter and the query survive a refresh`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("survive").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.setQuery("poste")
        vm.setFilter(DeviceBandFilter.BAND_2_4)
        vm.setSort(DeviceSort.IP)
        runCurrent()

        vm.refresh()
        runCurrent()
        runCurrent()

        assertEquals("poste", vm.state.value.query)
        assertEquals(DeviceBandFilter.BAND_2_4, vm.state.value.filter)
        assertEquals(DeviceSort.IP, vm.state.value.sort)
        coordinator.stopPolling()
    }

    // -----------------------------------------------------------------
    // The session loss
    // -----------------------------------------------------------------

    @Test
    fun `a session loss purges the metadata from the memory`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("purge").toFile()
        var expired = false
        val adapter = FakeRouterAdapter {
            if (expired) throw RouterUnavailableException.SessionExpired()
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        vm.renameDevice("aa:bb:cc:dd:ee:01", "Téléphone de Marie")
        runCurrent()
        assertTrue(vm.state.value.metadata.isNotEmpty())

        // The session expires: the metadata leave the memory with the
        // devices. The clock is moved past the freshness window so the
        // read really reaches the router.
        expired = true
        advanceTimeBy(2_000)
        vm.refresh()
        runCurrent()

        assertTrue(
            "the repository must be in the expired state",
            repository.state.value.connection is ConnectionState.SessionExpired
        )
        assertTrue(
            "the metadata must be purged from the memory",
            vm.state.value.metadata.isEmpty()
        )
        assertTrue(vm.state.value.devices.isEmpty())
        coordinator.stopPolling()
    }

    // -----------------------------------------------------------------
    // The empty states
    // -----------------------------------------------------------------

    @Test
    fun `the empty state tells the reason apart`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("empty").toFile()
        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = repository(adapter, directory, this, TestHarness.virtualClock(testScheduler))
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.1.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        // No device matches the query.
        vm.setQuery("rien du tout")
        runCurrent()
        assertEquals(DeviceEmptyReason.NO_MATCH_QUERY, vm.state.value.emptyReason)

        // No device is on the band.
        vm.setQuery("")
        vm.setFilter(DeviceBandFilter.WIRED)
        runCurrent()
        assertEquals(DeviceEmptyReason.NO_MATCH_FILTER, vm.state.value.emptyReason)

        // Neither.
        vm.setQuery("rien du tout")
        runCurrent()
        assertEquals(DeviceEmptyReason.NO_MATCH_BOTH, vm.state.value.emptyReason)

        // And the matching filter empties nothing.
        vm.setQuery("")
        vm.setFilter(DeviceBandFilter.BAND_2_4)
        runCurrent()
        assertEquals(
            "the device list must have been loaded",
            1,
            vm.state.value.devices.size
        )
        assertEquals(
            "the device must be on the 2.4 GHz band",
            setOf(dev.wifiadmin.router.WifiBand.BAND_2_4_GHZ),
            vm.state.value.devices.first().bands
        )
        assertNull(vm.state.value.emptyReason)
        assertEquals(1, vm.state.value.filteredRows.size)
        coordinator.stopPolling()
    }

    @Test
    fun `the metadata of another router are never shown`() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("isolation").toFile()
        val store = DeviceMetadataStore(directory) { 1_700_000_060_000L }

        // The metadata of another router.
        store.setCustomName("aa:bb:cc:dd:ee:01", "Autre routeur", context())

        val adapter = FakeRouterAdapter { FakeRouterAdapter.oneDevice(1_700_000_000_000L) }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            snapshotStore = null,
            metadataStore = store,
            clock = TestHarness.virtualClock(testScheduler),
            scope = this,
            freshness = ReadFreshnessPolicy { false },
        )
        val (vm, coordinator) = viewModel(repository, this)

        repository.connect("192.168.0.1", "admin", "secret")
        vm.refresh()
        runCurrent()
        runCurrent()

        assertTrue(
            "the metadata of another router must never be shown",
            vm.state.value.rows.none { it.hasCustomName }
        )
        coordinator.stopPolling()
    }
}
