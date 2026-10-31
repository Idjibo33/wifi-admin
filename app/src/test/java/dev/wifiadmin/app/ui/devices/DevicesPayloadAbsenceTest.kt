package dev.wifiadmin.app.ui.devices

import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The screen must not claim that no device is connected when the router
 * delivered no device list at all.
 *
 * "The router answered nothing" and "the network is empty" are different
 * facts, and the user must be able to tell them apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesPayloadAbsenceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an absent payload is reported as such`() = runTest(dispatcher) {
        val repository = RouterRepository(
            adapter = FakeAdapter(
                DeviceSnapshot(emptyList(), payloadAbsent = true)
            ),
            ioDispatcher = dispatcher,
        )
        repository.connect("192.168.1.1", "admin", "secret")

        val vm = DevicesViewModel(repository)

        vm.refresh()
        advanceUntilIdle()

        assertTrue("the state must say the payload was absent", vm.state.value.payloadAbsent)
    }

    @Test
    fun `a present payload is not reported as absent`() = runTest(dispatcher) {
        val repository = RouterRepository(
            adapter = FakeAdapter(
                DeviceSnapshot(emptyList(), payloadAbsent = false)
            ),
            ioDispatcher = dispatcher,
        )
        repository.connect("192.168.1.1", "admin", "secret")

        val vm = DevicesViewModel(repository)

        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.payloadAbsent)
    }

    /** An adapter that always answers with the given snapshot. */
    private class FakeAdapter(
        private val snapshot: DeviceSnapshot,
    ) : RouterAdapter {
        override val displayName: String = "Fake"

        override suspend fun connect(host: String, username: String, password: String): Boolean = true

        override suspend fun fetchDevices(): DeviceSnapshot = snapshot

        override fun disconnect() = Unit
    }
}
