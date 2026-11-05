package dev.wifiadmin.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.LocalNetworkStatus
import dev.wifiadmin.app.data.NetworkIdentity
import dev.wifiadmin.app.data.NetworkMonitor
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RefreshPolicy
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * La perte et le retour du reseau, **sur l'appareil reel**.
 *
 * Le telephone ne peut pas etre mis hors ligne dans un test instrumente :
 * le test doit piloter l'etat du reseau lui-meme. Un [NetworkMonitor]
 * factice remplace le `ConnectivityManager` — c'est la seule piece qui
 * change ; le [RouterRepository], le [RefreshCoordinator] et les regles de
 * navigation sont les **vrais**.
 *
 * Ce que le test prouve :
 *
 * - T04 : la perte du Wi-Fi **suspend** les lectures, ne les supprime pas ;
 * - T04 : la session est **conservee** (jamais une expiration) ;
 * - T04 : les dernieres donnees restent disponibles ;
 * - T05 : le retour du reseau **reprend** les lectures ;
 * - T05 : aucune fausse expiration n'apparait apres le retour.
 */
@RunWith(AndroidJUnit4::class)
class NetworkChangeInstrumentedTest {

    private companion object {
        fun device(index: Int) = RouterDevice(
            mac = "00:00:00:00:00:%02X".format(index),
            ip = "192.168.1.%d".format(100 + index),
            name = "device-%d".format(index),
            bands = emptySet(),
            signal = null,
            interfaceName = "wlan0",
            leaseExpiry = null,
            ipv6 = null,
            presence = DevicePresence.ACTIVE,
        )
    }

    private val localIdentity = NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")

    /** Un moniteur que le test pilote. */
    private class ControllableMonitor(
        initial: LocalNetworkStatus = LocalNetworkStatus.LocalWifi(
            NetworkIdentity("wlan0", "192.168.1.0/24", "192.168.1.1")
        )
    ) : NetworkMonitor {
        val status = MutableStateFlow<LocalNetworkStatus>(initial)

        override fun observeStatus(): Flow<LocalNetworkStatus> = status

        override fun currentStatus(): LocalNetworkStatus = status.value
    }

    /** Un adaptateur qui compte les lectures. */
    private class CountingAdapter : RouterAdapter {
        override val displayName: String = "Counting"
        val readCount = AtomicInteger(0)
        val readTimes = Collections.synchronizedList(mutableListOf<Long>())

        override suspend fun connect(host: String, username: String, password: String) = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            readTimes.add(SystemClock.elapsedRealtime())
            readCount.incrementAndGet()
            return DeviceSnapshot(
                devices = listOf(device(1), device(2)),
                capturedAtMillis = System.currentTimeMillis(),
                payloadAbsent = false,
            )
        }

        override fun disconnect() = Unit
    }

    @Test
    fun losingTheNetworkSuspendsTheReadsAndKeepsTheSession() {
        val adapter = CountingAdapter()
        val monitor = ControllableMonitor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = RouterRepository(
                adapter = adapter,
                ioDispatcher = Dispatchers.IO,
                scope = scope,
                networkMonitor = monitor,
            )
            val coordinator = RefreshCoordinator(
                repository = repository,
                scope = scope,
                policy = RefreshPolicy(),
            )
            repository.observeNetworkChanges(scope)

            runBlocking { repository.connect("192.168.1.1", "admin", "secret") }
            coordinator.startPolling()

            // Une lecture reussie.
            Thread.sleep(1_500L)
            val readsBeforeLoss = adapter.readCount.get()
            val snapshotBefore = repository.state.value.snapshot
            println("=== T04 NETWORK LOSS ===")
            println("readsBeforeLoss=$readsBeforeLoss")
            println("snapshotPresent=${snapshotBefore != null}")
            println("connectionBefore=${repository.state.value.connection}")
            assertTrue("a first read must have happened", readsBeforeLoss >= 1)
            assertNotNull("a snapshot must be present", snapshotBefore)

            // --- Le reseau disparait ---
            monitor.status.value = LocalNetworkStatus.NoWifi
            Thread.sleep(2_000L)

            val connectionAfterLoss = repository.state.value.connection
            println("connectionAfterLoss=$connectionAfterLoss")
            println("snapshotStillPresent=${repository.state.value.snapshot != null}")

            assertTrue(
                "the session must be suspended, not ended",
                connectionAfterLoss is ConnectionState.NetworkChanged
            )
            assertFalse(
                "a network change must never be an expiry",
                connectionAfterLoss is ConnectionState.SessionExpired
            )
            assertNotNull(
                "the last known devices must stay available",
                repository.state.value.snapshot
            )

            // --- Les lectures sont suspendues ---
            val readsAtLoss = adapter.readCount.get()
            Thread.sleep(40_000L) // plus d'une periode complete
            val readsAfterWait = adapter.readCount.get()
            println("readsAtLoss=$readsAtLoss")
            println("readsAfter40s=$readsAfterWait")
            assertEquals(
                "no read may reach the router while the network is gone",
                readsAtLoss,
                readsAfterWait
            )

            // --- T05 : le reseau revient ---
            monitor.status.value = LocalNetworkStatus.LocalWifi(localIdentity)
            Thread.sleep(3_000L)

            val connectionAfterRestore = repository.state.value.connection
            println("connectionAfterRestore=$connectionAfterRestore")
            assertTrue(
                "the session must be usable again on the right network",
                connectionAfterRestore is ConnectionState.Connected
            )
            assertFalse(
                "no false expiry may appear after the network comes back",
                connectionAfterRestore is ConnectionState.SessionExpired
            )

            // Les lectures reprennent.
            Thread.sleep(32_000L)
            val readsAfterRestore = adapter.readCount.get()
            println("readsAfterRestore=$readsAfterRestore")
            assertTrue(
                "the reads must resume when the network comes back",
                readsAfterRestore > readsAfterWait
            )

            coordinator.stopPolling()
            println("=== END T04 NETWORK LOSS ===")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDifferentNetworkSuspendsTheReadsWithoutEndingTheSession() {
        val adapter = CountingAdapter()
        val monitor = ControllableMonitor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = RouterRepository(
                adapter = adapter,
                ioDispatcher = Dispatchers.IO,
                scope = scope,
                networkMonitor = monitor,
            )
            val coordinator = RefreshCoordinator(
                repository = repository,
                scope = scope,
                policy = RefreshPolicy(),
            )
            repository.observeNetworkChanges(scope)

            runBlocking { repository.connect("192.168.1.1", "admin", "secret") }
            coordinator.startPolling()
            Thread.sleep(1_500L)

            val devicesBefore = repository.state.value.snapshot?.devices
            assertNotNull(devicesBefore)

            // Un autre routeur : autre interface, autre plage, autre passerelle.
            monitor.status.value = LocalNetworkStatus.DifferentNetwork(
                NetworkIdentity("wlan1", "192.168.0.0/24", "192.168.0.1")
            )
            Thread.sleep(2_000L)

            val connection = repository.state.value.connection
            println("=== T04 OTHER ROUTER ===")
            println("connection=$connection")
            println("devicesKept=${repository.state.value.snapshot?.devices?.size}")
            println("=== END T04 OTHER ROUTER ===")

            assertTrue(
                "another router must suspend the reads",
                connection is ConnectionState.NetworkChanged
            )
            assertFalse(
                "another router must never be an expiry",
                connection is ConnectionState.SessionExpired
            )
            assertEquals(
                "the last known devices must stay on screen",
                devicesBefore,
                repository.state.value.snapshot?.devices
            )

            coordinator.stopPolling()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTimeoutIsNotAnExpiryOnTheDevice() {
        val adapter = object : RouterAdapter {
            override val displayName: String = "Timeout"
            override suspend fun connect(host: String, username: String, password: String) = true
            override suspend fun fetchDevices(): DeviceSnapshot =
                throw RouterUnavailableException.Timeout()
            override fun disconnect() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = RouterRepository(
                adapter = adapter,
                ioDispatcher = Dispatchers.IO,
                scope = scope,
            )
            runBlocking { repository.connect("192.168.1.1", "admin", "secret") }
            runBlocking { repository.refresh() }

            val connection = repository.state.value.connection
            println("=== T04 TIMEOUT ===")
            println("connection=$connection")
            println("=== END T04 TIMEOUT ===")

            assertFalse(
                "a timeout must never sign the user out",
                connection is ConnectionState.SessionExpired
            )
            assertTrue("the session must be kept", connection is ConnectionState.Connected)
        } finally {
            scope.cancel()
        }
    }
}
