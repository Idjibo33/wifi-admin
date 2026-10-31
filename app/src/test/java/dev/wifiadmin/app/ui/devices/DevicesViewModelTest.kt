package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        val repository = RouterRepository(adapter, dispatcher)
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
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
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setQuery("télé")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("Téléphone", vm.state.value.filteredDevices.first().name)
    }

    @Test
    fun `the search matches the ip address regardless of the case`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setQuery("192.168.1.12")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("192.168.1.12", vm.state.value.filteredDevices.first().ip)
    }

    @Test
    fun `the search matches the mac address regardless of the case`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setQuery("AA:BB:CC:DD:EE:02")
        advanceUntilIdle()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("aa:bb:cc:dd:ee:02", vm.state.value.filteredDevices.first().mac)
    }

    @Test
    fun `an empty query keeps every device`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setQuery("")
        advanceUntilIdle()

        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
    }

    @Test
    fun `the wifi filter keeps only the associated devices`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setFilter(DeviceFilter.WIFI)
        advanceUntilIdle()

        val result = vm.state.value.filteredDevices
        assertEquals(2, result.size)
        assertTrue(result.all { it.presence == DevicePresence.ACTIVE })
    }

    @Test
    fun `the wired filter keeps only the cabled devices`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setFilter(DeviceFilter.WIRED)
        advanceUntilIdle()

        val result = vm.state.value.filteredDevices
        assertEquals(1, result.size)
        assertEquals(DevicePresence.CABLED, result.first().presence)
    }

    @Test
    fun `the all filter keeps every device`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        vm.setFilter(DeviceFilter.ALL)
        advanceUntilIdle()

        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
    }

    @Test
    fun `the query and the filter combine`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(sampleDevices))))

        // The only match is an associated device, so the wired filter empties
        // the result.
        vm.setQuery("Téléphone")
        vm.setFilter(DeviceFilter.WIRED)
        advanceUntilIdle()

        assertTrue(vm.state.value.filteredDevices.isEmpty())

        // And the matching filter keeps it.
        vm.setFilter(DeviceFilter.WIFI)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.filteredDevices.size)
        assertEquals("Téléphone", vm.state.value.filteredDevices.first().name)
    }

    @Test
    fun `the refresh populates the devices from the repository`() = runTest(dispatcher) {
        val adapter = FakeAdapter(DeviceSnapshot(sampleDevices))
        val vm = DevicesViewModel(testRepository(adapter))

        vm.refresh()
        advanceUntilIdle()

        assertEquals(sampleDevices.size, vm.state.value.devices.size)
        assertEquals(sampleDevices.size, vm.state.value.filteredDevices.size)
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun `an empty device list yields an empty result`() = runTest(dispatcher) {
        val vm = DevicesViewModel(testRepository(FakeAdapter(DeviceSnapshot(emptyList()))))

        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.devices.isEmpty())
        assertTrue(vm.state.value.filteredDevices.isEmpty())
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun `the refresh is triggered when the view model is created`() = runTest(dispatcher) {
        val adapter = FakeAdapter(DeviceSnapshot(sampleDevices))

        DevicesViewModel(testRepository(adapter))
        advanceUntilIdle()

        assertEquals(1, adapter.fetchCalls)
    }
}
