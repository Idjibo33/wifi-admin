package dev.wifiadmin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.data.ReadFreshnessPolicy
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le bouton « Actualiser », sur l'appareil reel.
 *
 * ## Le defaut constate
 *
 * `ReadCoordinator.read()` documente son propre contrat :
 *
 * > « The manual button does **not** force: the user wants fresh data, and a
 * > read that completed a moment ago **is** fresh data. »
 *
 * Mais `RouterRepository.refresh()` appelle `performRead(force = true)`, et
 * `performRead` transmet ce `force` au coordinateur : une lecture manuelle
 * **contourne** la fenetre de fraicheur et demande systematiquement le
 * routeur, meme si la lecture d'il y a une seconde suffit.
 *
 * Mission 09 §3.3 exige l'inverse : « le routeur n'est pas sollicité
 * plusieurs fois ».
 *
 * Ce test **constate** le comportement sur l'appareil. Il ne corrige rien :
 * la correction est une decision de conception, hors du perimetre de la
 * recette.
 */
@RunWith(AndroidJUnit4::class)
class ManualRefreshInstrumentedTest {

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

    private class CountingAdapter : RouterAdapter {
        override val displayName: String = "Counting"
        val fetchCalls = AtomicInteger(0)

        override suspend fun connect(host: String, username: String, password: String) = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            fetchCalls.incrementAndGet()
            return DeviceSnapshot(
                devices = listOf(device(1)),
                capturedAtMillis = System.currentTimeMillis(),
                payloadAbsent = false,
            )
        }

        override fun disconnect() = Unit
    }

    @Test
    fun aManualRefreshRightAfterAScheduledReadReusesTheFreshRead() {
        val adapter = CountingAdapter()
        val repository = RouterRepository(
            adapter = adapter,
            // The **production** freshness window: the one the application
            // uses, not a disabled one.
            freshness = ReadFreshnessPolicy.Default,
        )

        runBlocking {
            repository.connect("192.168.1.1", "admin", "secret")

            // A first, successful read.
            repository.refresh()
            val afterFirst = adapter.fetchCalls.get()

            // The user presses the refresh button again, immediately.
            repository.refresh()
            val afterSecond = adapter.fetchCalls.get()

            println("=== MANUAL REFRESH ===")
            println("fetchCallsAfterFirstRefresh=$afterFirst")
            println("fetchCallsAfterSecondRefresh=$afterSecond")
            println("=== END MANUAL REFRESH ===")

            assertEquals("the first read must reach the router", 1, afterFirst)

            // The data is less than a second old: it **is** fresh data. The
            // manual refresh reuses it instead of asking the router again,
            // which would double the traffic for nothing — and the firmware
            // refuses logins after too many failures.
            assertEquals(
                "the manual refresh must reuse the very recent read",
                1,
                afterSecond
            )
        }
    }

    /**
     * The forced read is the one that always asks the router.
     *
     * It is used when the data cannot be trusted: a changed network, a
     * restored cache, or an explicit decision to refresh.
     */
    @Test
    fun aForcedRefreshAlwaysReachesTheRouter() {
        val adapter = CountingAdapter()
        val repository = RouterRepository(
            adapter = adapter,
            freshness = ReadFreshnessPolicy.Default,
        )

        runBlocking {
            repository.connect("192.168.1.1", "admin", "secret")
            repository.refresh()
            val afterFirst = adapter.fetchCalls.get()

            repository.refreshForced()
            val afterForced = adapter.fetchCalls.get()

            println("=== FORCED REFRESH ===")
            println("fetchCallsAfterFirstRefresh=$afterFirst")
            println("fetchCallsAfterForcedRefresh=$afterForced")
            println("=== END FORCED REFRESH ===")

            assertEquals("the first read must reach the router", 1, afterFirst)
            assertEquals(
                "the forced read must always reach the router",
                2,
                afterForced
            )
        }
    }

    @Test
    fun theScheduledReadDoesReuseAFreshSnapshot() {
        // The counterpart: the *scheduled* read respects the window.
        val adapter = CountingAdapter()
        val repository = RouterRepository(
            adapter = adapter,
            freshness = ReadFreshnessPolicy.Default,
        )

        runBlocking {
            repository.connect("192.168.1.1", "admin", "secret")
            repository.refresh()
            val afterFirst = adapter.fetchCalls.get()

            // `refreshIfStale` is what the polling uses.
            repository.refreshIfStale(25_000L)
            val afterScheduled = adapter.fetchCalls.get()

            println("=== SCHEDULED READ ===")
            println("fetchCallsAfterFirstRefresh=$afterFirst")
            println("fetchCallsAfterScheduledRead=$afterScheduled")
            println("=== END SCHEDULED READ ===")

            assertEquals("the first read must reach the router", 1, afterFirst)
            assertEquals(
                "the scheduled read must reuse the fresh snapshot",
                1,
                afterScheduled
            )
        }
    }
}
