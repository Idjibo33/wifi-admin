package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The repository performs blocking network I/O. It must never do so on the
 * main thread: Android raises `NetworkOnMainThreadException`, which would be
 * misreported as "the router is unreachable".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouterRepositoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** An adapter that records the thread it was called from. */
    private class ThreadRecordingAdapter : RouterAdapter {
        override val displayName = "Fake"
        var connectThread: String? = null
        var fetchThread: String? = null

        override suspend fun connect(host: String, username: String, password: String): Boolean {
            connectThread = Thread.currentThread().name
            return true
        }

        override suspend fun fetchDevices(): DeviceSnapshot {
            fetchThread = Thread.currentThread().name
            return DeviceSnapshot(emptyList())
        }

        override fun disconnect() = Unit
    }

    @Test
    fun `the repository runs the network call on the IO dispatcher`() = runTest(dispatcher) {
        val adapter = ThreadRecordingAdapter()
        val repository = RouterRepository(adapter)

        repository.connect("192.168.1.1", "admin", "secret")

        assertTrue(
            "the connection must not run on the main thread, was: ${adapter.connectThread}",
            adapter.connectThread != Thread.currentThread().name
        )
    }

    @Test
    fun `the refresh runs the network call on the IO dispatcher`() = runTest(dispatcher) {
        val adapter = ThreadRecordingAdapter()
        val repository = RouterRepository(adapter)
        repository.connect("192.168.1.1", "admin", "secret")

        repository.refresh()

        assertTrue(
            "the refresh must not run on the main thread, was: ${adapter.fetchThread}",
            adapter.fetchThread != Thread.currentThread().name
        )
    }

    @Test
    fun `a successful connection updates the state`() = runTest(dispatcher) {
        val repository = RouterRepository(ThreadRecordingAdapter())

        val accepted = repository.connect("192.168.1.1", "admin", "secret")

        assertTrue(accepted)
        assertTrue(repository.state.value.isConnected)
        assertEquals("192.168.1.1", repository.state.value.host)
    }

    @Test
    fun `an unreachable router is reported as unreachable`() = runTest(dispatcher) {
        val adapter = object : RouterAdapter {
            override val displayName = "Fake"
            override suspend fun connect(host: String, username: String, password: String): Boolean =
                throw RouterUnavailableException.Unreachable(null)

            override suspend fun fetchDevices(): DeviceSnapshot = DeviceSnapshot(emptyList())
            override fun disconnect() = Unit
        }
        val repository = RouterRepository(adapter)

        val accepted = repository.connect("192.168.1.1", "admin", "secret")

        assertFalse(accepted)
        assertTrue(repository.state.value.error is RouterUnavailableException.Unreachable)
    }

    @Test
    fun `a refresh failure keeps the previous snapshot`() = runTest(dispatcher) {
        var failNext = false
        val adapter = object : RouterAdapter {
            override val displayName = "Fake"
            override suspend fun connect(host: String, username: String, password: String) = true

            override suspend fun fetchDevices(): DeviceSnapshot {
                if (failNext) throw RouterUnavailableException.Unreachable(null)
                return DeviceSnapshot(emptyList())
            }

            override fun disconnect() = Unit
        }
        val repository = RouterRepository(adapter)
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()
        val snapshot = repository.state.value.snapshot

        failNext = true
        repository.refresh()

        assertEquals(snapshot, repository.state.value.snapshot)
        assertTrue(repository.state.value.error is RouterUnavailableException.Unreachable)
    }
}
