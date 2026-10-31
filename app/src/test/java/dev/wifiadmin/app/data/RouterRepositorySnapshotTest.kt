package dev.wifiadmin.app.data

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The repository and the snapshot store together: what is restored after a
 * login, what survives a failure, and what a disconnect must erase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouterRepositorySnapshotTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var directory: File
    private lateinit var file: File
    private var now = CAPTURED_AT + 60_000L

    private val context = SnapshotContext.of("192.168.1.1", "admin")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("repository-snapshot").toFile()
        file = File(directory, SnapshotStore.FILE_NAME)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun store() = SnapshotStore(file) { now }

    private fun device(
        mac: String = "aa:bb:cc:dd:ee:01",
        ip: String = "192.168.1.10",
        presence: DevicePresence = DevicePresence.ACTIVE,
    ) = RouterDevice(
        mac = mac,
        ip = ip,
        name = "poste",
        bands = setOf(WifiBand.BAND_2_4_GHZ),
        signal = DeviceSignal(-50, "72", "65", "maison"),
        interfaceName = "wl0",
        leaseExpiry = null,
        ipv6 = null,
        presence = presence,
    )

    private fun snapshot(
        devices: List<RouterDevice> = listOf(device()),
        capturedAt: Long = CAPTURED_AT,
        payloadAbsent: Boolean = false,
    ) = DeviceSnapshot(devices, capturedAtMillis = capturedAt, payloadAbsent = payloadAbsent)

    private class FakeAdapter(
        private val answer: () -> DeviceSnapshot,
    ) : RouterAdapter {
        override val displayName = "Fake"
        var fetchCalls = 0
        var disconnectCalls = 0

        override suspend fun connect(host: String, username: String, password: String) = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            fetchCalls++
            return answer()
        }

        override fun disconnect() {
            disconnectCalls++
        }
    }

    private fun repository(adapter: RouterAdapter, store: SnapshotStorage) =
        RouterRepository(adapter, dispatcher, store)

    @Test
    fun `a snapshot from a previous session is restored after login`() = runTest(dispatcher) {
        val cached = snapshot()
        val store = store()
        store.write(cached, context)

        val repository = repository(FakeAdapter { cached }, store)
        val accepted = repository.connect("192.168.1.1", "admin", "secret")

        assertTrue(accepted)
        assertEquals(cached, repository.state.value.snapshot)
        assertEquals(cached.capturedAtMillis, repository.state.value.lastRefreshMillis)
        assertTrue(repository.state.value.isShowingCachedSnapshot)
    }

    @Test
    fun `a snapshot from another account is deleted, never shown`() = runTest(dispatcher) {
        val store = store()
        store.write(snapshot(), SnapshotContext.of("192.168.1.1", "autre"))

        val repository = repository(FakeAdapter { snapshot() }, store)
        repository.connect("192.168.1.1", "admin", "secret")

        assertNull(repository.state.value.snapshot)
        assertFalse(repository.state.value.isShowingCachedSnapshot)
        assertEquals(SnapshotRead.Missing, store.read())
    }

    @Test
    fun `a snapshot from another host is deleted, never shown`() = runTest(dispatcher) {
        val store = store()
        store.write(snapshot(), SnapshotContext.of("192.168.0.1", "admin"))

        val repository = repository(FakeAdapter { snapshot() }, store)
        repository.connect("192.168.1.1", "admin", "secret")

        assertNull(repository.state.value.snapshot)
        assertEquals(SnapshotRead.Missing, store.read())
    }

    @Test
    fun `a successful read replaces the cache and is marked live`() = runTest(dispatcher) {
        val cached = snapshot(capturedAt = CAPTURED_AT)
        val fresh = snapshot(capturedAt = CAPTURED_AT + 30_000L)
        val store = store()
        store.write(cached, context)

        val repository = repository(FakeAdapter { fresh }, store)
        repository.connect("192.168.1.1", "admin", "secret")
        val result = repository.refresh()

        assertTrue(result.isSuccess)
        assertEquals(fresh, repository.state.value.snapshot)
        assertFalse(repository.state.value.isShowingCachedSnapshot)
        val read = store.read() as SnapshotRead.Found
        assertEquals(fresh.capturedAtMillis, read.record.snapshot.capturedAtMillis)
    }

    @Test
    fun `a network failure keeps the cached snapshot`() = runTest(dispatcher) {
        val cached = snapshot()
        val store = store()
        store.write(cached, context)

        val repository = repository(
            FakeAdapter { throw RouterUnavailableException.Unreachable() },
            store
        )
        repository.connect("192.168.1.1", "admin", "secret")
        val result = repository.refresh()

        assertTrue(result.isFailure)
        assertEquals(cached, repository.state.value.snapshot)
        assertTrue(repository.state.value.isShowingCachedSnapshot)
        assertTrue(repository.state.value.error is RouterUnavailableException.Unreachable)
        assertTrue(repository.state.value.isConnected)
    }

    @Test
    fun `a timeout is not a session expiry`() = runTest(dispatcher) {
        val repository = repository(
            FakeAdapter { throw RouterUnavailableException.Timeout() },
            store()
        )
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        assertTrue(repository.state.value.isConnected)
        assertTrue(repository.state.value.error is RouterUnavailableException.Timeout)
        assertFalse(repository.state.value.connection is ConnectionState.SessionExpired)
    }

    @Test
    fun `a cooldown is not a session expiry`() = runTest(dispatcher) {
        val adapter = FakeAdapter { throw RouterUnavailableException.Cooldown(42) }
        val repository = repository(adapter, store())
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        val connection = repository.state.value.connection
        assertTrue(connection is ConnectionState.Cooldown)
        assertEquals(42, (connection as ConnectionState.Cooldown).retryAfterSeconds)
        assertFalse(repository.state.value.connection is ConnectionState.SessionExpired)
    }

    @Test
    fun `an absent payload never overwrites the stored snapshot`() = runTest(dispatcher) {
        val cached = snapshot()
        val store = store()
        store.write(cached, context)

        val repository = repository(
            FakeAdapter { DeviceSnapshot(emptyList(), payloadAbsent = true) },
            store
        )
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        assertEquals(cached, repository.state.value.snapshot)
        assertTrue(repository.state.value.payloadAbsent)
        val read = store.read() as SnapshotRead.Found
        assertEquals(cached.capturedAtMillis, read.record.snapshot.capturedAtMillis)
    }

    @Test
    fun `an absent payload is never written`() = runTest(dispatcher) {
        val store = store()
        val repository = repository(
            FakeAdapter { DeviceSnapshot(emptyList(), payloadAbsent = true) },
            store
        )
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        assertEquals(SnapshotRead.Missing, store.read())
        assertTrue(repository.state.value.payloadAbsent)
    }

    @Test
    fun `a disconnect deletes the stored snapshot`() = runTest(dispatcher) {
        val store = store()
        store.write(snapshot(), context)
        val repository = repository(FakeAdapter { snapshot() }, store)
        repository.connect("192.168.1.1", "admin", "secret")

        repository.disconnect()

        assertEquals(SnapshotRead.Missing, store.read())
        assertNull(repository.state.value.snapshot)
        assertFalse(repository.state.value.isConnected)
    }

    @Test
    fun `an expiry clears the memory but keeps the file`() = runTest(dispatcher) {
        val cached = snapshot()
        val store = store()
        store.write(cached, context)
        val repository = repository(
            FakeAdapter { throw RouterUnavailableException.SessionExpired() },
            store
        )
        repository.connect("192.168.1.1", "admin", "secret")

        repository.refresh()

        assertTrue(repository.state.value.connection is ConnectionState.SessionExpired)
        assertNull("no authenticated data may remain in memory", repository.state.value.snapshot)
        // The user may sign in again on the same router: the file is kept.
        assertTrue(store.read() is SnapshotRead.Found)
    }

    @Test
    fun `an expiry stops further reads without a new network call`() = runTest(dispatcher) {
        val adapter = FakeAdapter { throw RouterUnavailableException.SessionExpired() }
        val repository = repository(adapter, store())
        repository.connect("192.168.1.1", "admin", "secret")

        repository.refresh()
        repository.refresh()

        assertEquals("only the first read may reach the adapter", 1, adapter.fetchCalls)
    }

    @Test
    fun `a manual login after an expiry works`() = runTest(dispatcher) {
        val adapter = FakeAdapter { throw RouterUnavailableException.SessionExpired() }
        val repository = repository(adapter, store())
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        val accepted = repository.connect("192.168.1.1", "admin", "secret")

        assertTrue(accepted)
        assertTrue(repository.state.value.isConnected)
    }

    @Test
    fun `an older read cannot overwrite a newer stored snapshot`() = runTest(dispatcher) {
        val newer = snapshot(capturedAt = CAPTURED_AT + 10_000L)
        val older = snapshot(capturedAt = CAPTURED_AT)
        val store = store()
        store.write(newer, context)

        val repository = repository(FakeAdapter { older }, store)
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refresh()

        val read = store.read() as SnapshotRead.Found
        assertEquals(
            "the newer snapshot must survive an older read",
            newer.capturedAtMillis,
            read.record.snapshot.capturedAtMillis
        )
    }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
