package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.ReadFreshnessPolicy
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.TestHarness
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * A repository whose network calls run on the test dispatcher, so the
     * coroutines stay under the control of the test.
     *
     * The session is opened first: [RouterRepository.refresh] refuses to read
     * without one, exactly like the production flow where the user signs in
     * before any device list is fetched.
     */
    private suspend fun testRepository(adapter: RouterAdapter): RouterRepository {
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            // Every read reaches the adapter: the freshness window must not
            // hide a call the test is asserting on.
            freshness = ReadFreshnessPolicy { false },
        )
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
    }

    /**
     * Builds the view model and starts the polling, exactly as the screen
     * does when it becomes visible.
     *
     * The view model does not read on its own any more: the reads belong
     * to the coordinator, which is shared with the dashboard.
     */
    private suspend fun viewModel(
        adapter: RouterAdapter,
        scope: CoroutineScope,
    ): DevicesViewModel = viewModelAndCoordinator(adapter, scope).first

    /**
     * Builds the view model and the coordinator that shares the reads.
     *
     * The polling loop never ends on its own: the test stops it once the
     * first read has settled.
     */
    private suspend fun viewModelAndCoordinator(
        adapter: RouterAdapter,
        scope: CoroutineScope,
    ): Pair<DevicesViewModel, RefreshCoordinator> {
        val repository = testRepository(adapter)
        val coordinator = TestHarness.coordinator(repository, scope)
        coordinator.startPolling()
        return DevicesViewModel(repository, coordinator) to coordinator
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** An adapter that always answers with the same snapshot. */
    private class FakeAdapter(
        private val snapshot: DeviceSnapshot = DeviceSnapshot(emptyList())
    ) : RouterAdapter {
        override val displayName = "Fake"
        var fetchCalls = 0

        override suspend fun connect(host: String, username: String, password: String): Boolean = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            fetchCalls++
            return snapshot
        }

        override fun disconnect() = Unit
    }

    private fun device(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.10",
        name: String = "poste",
        presence: DevicePresence = DevicePresence.ACTIVE,
        bands: Set<WifiBand> = setOf(WifiBand.BAND_2_4_GHZ)
    ) = RouterDevice(
        mac = mac,
        ip = ip,
        name = name,
        bands = bands,
        signal = DeviceSignal(rssiDbm = -50, txRateMbps = "72", rxRateMbps = "65", ssid = null),
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = presence
    )

    private val sampleDevices = listOf(
        device(
            mac = "aa:bb:cc:dd:ee:01",
            ip = "192.168.1.10",
            name = "Poste",
            presence = DevicePresence.ACTIVE
        ),
        device(
            mac = "aa:bb:cc:dd:ee:02",
            ip = "192.168.1.11",
            name = "Téléphone",
            presence = DevicePresence.ACTIVE,
            bands = setOf(WifiBand.BAND_5_GHZ)
        ),
        device(
            mac = "aa:bb:cc:dd:ee:03",
            ip = "192.168.1.12",
            name = "Imprimante",
            presence = DevicePresence.CABLED,
            bands = emptySet()
        )
    )

    @Test
    fun `the search matches the name regardless of the case`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setQuery("télé")
        runCurrent()
        coordinator.stopPolling()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("Téléphone", vm.state.value.filteredDevices.first().name)
    }

    @Test
    fun `the search matches the ip address regardless of the case`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setQuery("192.168.1.12")
        runCurrent()
        coordinator.stopPolling()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("192.168.1.12", vm.state.value.filteredDevices.first().ip)
    }

    @Test
    fun `the search matches the mac address regardless of the case`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setQuery("AA:BB:CC:DD:EE:02")
        runCurrent()
        coordinator.stopPolling()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("aa:bb:cc:dd:ee:02", vm.state.value.filteredDevices.first().mac)
    }

    @Test
    fun `an empty query keeps every device`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setQuery("")
        runCurrent()
        coordinator.stopPolling()

        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
    }

    @Test
    fun `the wifi filter keeps only the associated devices`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setFilter(DeviceFilter.WIFI)
        runCurrent()
        coordinator.stopPolling()

        val result = vm.state.value.filteredDevices
        assertEquals(2, result.size)
        assertTrue(result.all { it.presence == DevicePresence.ACTIVE })
    }

    @Test
    fun `the wired filter keeps only the cabled devices`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setFilter(DeviceFilter.WIRED)
        runCurrent()
        coordinator.stopPolling()

        val result = vm.state.value.filteredDevices
        assertEquals(1, result.size)
        assertEquals(DevicePresence.CABLED, result.first().presence)
    }

    @Test
    fun `the all filter keeps every device`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        vm.setFilter(DeviceFilter.ALL)
        runCurrent()
        coordinator.stopPolling()

        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
    }

    @Test
    fun `the query and the filter combine`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(sampleDevices)), this)

        // The only match is an associated device, so the wired filter empties
        // the result.
        vm.setQuery("Téléphone")
        vm.setFilter(DeviceFilter.WIRED)
        runCurrent()
        coordinator.stopPolling()

        assertTrue(vm.state.value.filteredDevices.isEmpty())

        // And the matching filter keeps it.
        vm.setFilter(DeviceFilter.WIFI)
        runCurrent()
        coordinator.stopPolling()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("Téléphone", vm.state.value.filteredDevices.first().name)
    }

    @Test
    fun `the refresh populates the devices from the repository`() = runTest(dispatcher) {
        val adapter = FakeAdapter(DeviceSnapshot(sampleDevices))
        val (vm, coordinator) = viewModelAndCoordinator(adapter, this)

        vm.refresh()
        runCurrent()
        coordinator.stopPolling()

        assertEquals(sampleDevices.size, vm.state.value.devices.size)
        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun `an empty device list yields an empty result`() = runTest(dispatcher) {
        val (vm, coordinator) = viewModelAndCoordinator(FakeAdapter(DeviceSnapshot(emptyList())), this)

        vm.refresh()
        runCurrent()
        coordinator.stopPolling()

        assertTrue(vm.state.value.devices.isEmpty())
        assertTrue(vm.state.value.filteredDevices.isEmpty())
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun `the refresh is triggered when the polling starts`() = runTest(dispatcher) {
        val adapter = FakeAdapter(DeviceSnapshot(sampleDevices))

        val (vm, coordinator) = viewModelAndCoordinator(adapter, this)
        runCurrent()
        coordinator.stopPolling()

        assertEquals(
            "the screen must show the devices without waiting for thirty seconds",
            1,
            adapter.fetchCalls
        )
        assertEquals(sampleDevices.size, vm.state.value.devices.size)
    }
}
