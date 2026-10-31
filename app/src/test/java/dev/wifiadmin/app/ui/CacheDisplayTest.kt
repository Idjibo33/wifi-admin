package dev.wifiadmin.app.ui

import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.app.data.SnapshotContext
import dev.wifiadmin.app.data.SnapshotStore
import dev.wifiadmin.app.ui.dashboard.DashboardViewModel
import dev.wifiadmin.app.ui.devices.DevicesViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What the screens show when the repository holds a restored snapshot.
 *
 * The ViewModels must adopt it immediately, keep it when the refresh fails,
 * and drop the cache label as soon as a live read succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheDisplayTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var directory: File
    private lateinit var file: File

    private val context = SnapshotContext.of("192.168.1.1", "admin")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("cache-display").toFile()
        file = File(directory, SnapshotStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun store() = SnapshotStore(file) { CAPTURED_AT + 60_000L }

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

    private class FakeAdapter(
        private val answer: () -> DeviceSnapshot,
    ) : RouterAdapter {
        override val displayName = "Fake"
        override suspend fun connect(host: String, username: String, password: String) = true
        override suspend fun fetchDevices(): DeviceSnapshot = answer()
        override fun disconnect() = Unit
    }

    private suspend fun cachedRepository(adapter: RouterAdapter): RouterRepository {
        val store = store()
        store.write(snapshot(), context)
        val repository = RouterRepository(adapter, dispatcher, store)
        repository.connect("192.168.1.1", "admin", "secret")
        return repository
    }

    @Test
    fun `the dashboard adopts the restored snapshot before the first refresh`() =
        runTest(dispatcher) {
            val repository = cachedRepository(
                FakeAdapter { throw RouterUnavailableException.Unreachable() }
            )

            val viewModel = DashboardViewModel(repository)

            assertEquals(snapshot(), viewModel.state.value.snapshot)
            assertTrue(viewModel.state.value.isFromCache)
            assertTrue(viewModel.state.value.hasSnapshot)
            assertFalse(viewModel.state.value.isFirstLoad)
        }

    @Test
    fun `the dashboard keeps the cache when the refresh fails`() = runTest(dispatcher) {
        val repository = cachedRepository(
            FakeAdapter { throw RouterUnavailableException.Unreachable() }
        )
        val viewModel = DashboardViewModel(repository)

        advanceUntilIdle()

        assertEquals(snapshot(), viewModel.state.value.snapshot)
        assertTrue(viewModel.state.value.isFromCache)
        assertTrue(viewModel.state.value.isSyncFailed)
        assertTrue(viewModel.state.value.hasStaleData)
    }

    @Test
    fun `the dashboard drops the cache label after a live read`() = runTest(dispatcher) {
        val fresh = DeviceSnapshot(listOf(device()), capturedAtMillis = CAPTURED_AT + 30_000L)
        val repository = cachedRepository(FakeAdapter { fresh })
        val viewModel = DashboardViewModel(repository)

        advanceUntilIdle()

        assertEquals(fresh, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.isFromCache)
        assertFalse(viewModel.state.value.isSyncFailed)
        assertFalse(viewModel.state.value.hasStaleData)
    }

    @Test
    fun `the device list adopts the restored snapshot before the first refresh`() =
        runTest(dispatcher) {
            val repository = cachedRepository(
                FakeAdapter { throw RouterUnavailableException.Unreachable() }
            )

            val viewModel = DevicesViewModel(repository)

            assertEquals(1, viewModel.state.value.devices.size)
            assertTrue(viewModel.state.value.isFromCache)
            assertFalse(viewModel.state.value.isFirstLoad)
        }

    @Test
    fun `the device list keeps the cache when the refresh fails`() = runTest(dispatcher) {
        val repository = cachedRepository(
            FakeAdapter { throw RouterUnavailableException.Unreachable() }
        )
        val viewModel = DevicesViewModel(repository)

        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.devices.size)
        assertTrue(viewModel.state.value.isFromCache)
        assertTrue(viewModel.state.value.isSyncFailed)
    }

    @Test
    fun `an incomplete reply keeps the cached devices`() = runTest(dispatcher) {
        val repository = cachedRepository(
            FakeAdapter { DeviceSnapshot(emptyList(), payloadAbsent = true) }
        )
        val viewModel = DevicesViewModel(repository)

        advanceUntilIdle()

        assertEquals("cached devices must survive an absent payload", 1, viewModel.state.value.devices.size)
        assertTrue(viewModel.state.value.payloadAbsent)
        assertTrue(viewModel.state.value.isFromCache)
    }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
