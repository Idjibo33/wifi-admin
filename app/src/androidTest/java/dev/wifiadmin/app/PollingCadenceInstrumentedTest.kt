package dev.wifiadmin.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.ConnectionState
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RefreshPolicy
import dev.wifiadmin.app.data.RouterAdapter
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.RouterUnavailableException
import dev.wifiadmin.router.DeviceSnapshot
import dev.wifiadmin.router.RouterDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * La cadence du rafraîchissement automatique, **mesurée sur l'appareil**.
 *
 * ## Pourquoi un adaptateur factice
 *
 * Le routeur réel refuse les connections sans le mot de passe administrateur,
 * qui n'est pas accessible ici. Un adaptateur factice permet de mesurer la
 * cadence **vraie** — celle du code de production, avec le vrai
 * [RefreshCoordinator], le vrai [RouterRepository] et le vrai horloge
 * monotone — sans jamais contacter le routeur.
 *
 * Ce que le test mesure :
 *
 * - le nombre de lectures sur une fenetre donnee ;
 * - l'intervalle **reel** entre deux lectures, mesure avec
 *   [SystemClock.elapsedRealtime] ;
 * - l'absence de lecture en arriere-plan ;
 * - la reprise unique au retour au premier plan.
 *
 * Ce que le test ne mesure pas : le comportement du protocole face a un vrai
 * routeur. Il est couvert par `router-core` et par `tools/fake_router*.py`.
 */
@RunWith(AndroidJUnit4::class)
class PollingCadenceInstrumentedTest {

    /** Un appareil factice, sans donnee reelle. */
    private companion object {
        fun device(index: Int) = RouterDevice(
            mac = "00:00:00:00:00:%02X".format(index % 256),
            ip = "192.168.1.%d".format(100 + index % 100),
            name = "device-%d".format(index),
            bands = emptySet(),
            signal = null,
            interfaceName = "wlan0",
            leaseExpiry = null,
            ipv6 = null,
            presence = dev.wifiadmin.router.DevicePresence.ACTIVE,
        )
    }

    /**
     * Un adaptateur qui compte les lectures et note l'heure de chacune.
     *
     * Aucun octet ne part vers le reseau : c'est un objet en memoire.
     */
    private class CountingAdapter(
        val readTimesMillis: MutableList<Long> = Collections.synchronizedList(mutableListOf()),
        val readCount: AtomicInteger = AtomicInteger(0),
        val firstReadLatch: CountDownLatch = CountDownLatch(1),
        val failure: Throwable? = null,
    ) : RouterAdapter {
        override val displayName: String = "Counting"

        override suspend fun connect(host: String, username: String, password: String): Boolean = true

        override suspend fun fetchDevices(): DeviceSnapshot {
            readTimesMillis.add(SystemClock.elapsedRealtime())
            val n = readCount.incrementAndGet()
            firstReadLatch.countDown()
            failure?.let { throw it }
            return DeviceSnapshot(
                devices = listOf(device(1), device(2)),
                capturedAtMillis = System.currentTimeMillis(),
                payloadAbsent = false,
            )
        }

        override fun disconnect() = Unit
    }

    private class RealClock : AppClock {
        override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
        override fun wallClockMillis(): Long = System.currentTimeMillis()
    }

    /**
     * Un harnais qui reproduit le cablage de production:
     * `RouterRepository` + `RefreshCoordinator` sur un scope de l'application.
     */
    private class Harness(
        val adapter: CountingAdapter,
        val scope: CoroutineScope,
    ) {
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = Dispatchers.IO,
            scope = scope,
            clock = RealClock(),
        )
        val coordinator = RefreshCoordinator(
            repository = repository,
            scope = scope,
            policy = RefreshPolicy(),
        )
    }

    // -----------------------------------------------------------------
    // T01 — une lecture par periode de 30 s.
    // -----------------------------------------------------------------

    @Test
    fun oneReadPerThirtySecondPeriodOnTheRealDevice() {
        val adapter = CountingAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val harness = Harness(adapter, scope)
            kotlinx.coroutines.runBlocking {
                harness.repository.connect("192.168.1.1", "admin", "secret")
            }

            harness.coordinator.startPolling()

            // La premiere lecture est immediate.
            assertTrue(
                "the first read must happen at once",
                adapter.firstReadLatch.await(10, TimeUnit.SECONDS)
            )

            // On observe deux cycles de 30 s complets.
            val windowMillis = 62_000L
            val start = SystemClock.elapsedRealtime()
            Thread.sleep(windowMillis)
            val elapsed = SystemClock.elapsedRealtime() - start

            val times = adapter.readTimesMillis.toList()
            val afterFirst = times.size - 1

            println("=== T01 CADENCE ===")
            println("windowMillis=$elapsed")
            println("totalReads=${times.size}")
            println("readsAfterFirst=$afterFirst")
            times.forEachIndexed { i, t -> println("  read[$i] at +${t - times.first()}ms") }
            if (times.size >= 2) {
                val gaps = times.zipWithNext { a, b -> b - a }
                println("gapsMillis=$gaps")
            }
            println("=== END T01 ===")

            // Sur ~62 s, on attend 2 lectures automatiques en plus de la
            // premiere (a t=0, t~30, t~60). On tolere une lecture de bord.
            assertTrue(
                "at most 4 reads may happen in 62 s, got ${times.size}",
                times.size <= 4
            )
            assertTrue(
                "at least 3 reads must happen in 62 s (t=0, ~30, ~60), got ${times.size}",
                times.size >= 3
            )

            // L'intervalle reel mesure doit approcher la politique (30 s).
            val gaps = times.zipWithNext { a, b -> b - a }
            gaps.forEachIndexed { i, gap ->
                assertTrue(
                    "gap[$i] = $gap ms must be close to the 30 s policy",
                    gap in 28_000L..34_000L
                )
            }

            harness.coordinator.stopPolling()
        } finally {
            scope.cancel()
        }
    }

    // -----------------------------------------------------------------
    // T03 — aucune lecture en arriere-plan.
    // -----------------------------------------------------------------

    @Test
    fun noReadWhileTheLoopIsStopped() {
        val adapter = CountingAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val harness = Harness(adapter, scope)
            kotlinx.coroutines.runBlocking {
                harness.repository.connect("192.168.1.1", "admin", "secret")
            }

            harness.coordinator.startPolling()
            assertTrue(
                "the first read must happen at once",
                adapter.firstReadLatch.await(10, TimeUnit.SECONDS)
            )
            harness.coordinator.stopPolling()

            val readsAtStop = adapter.readCount.get()
            Thread.sleep(65_000L) // plus de deux periodes

            val readsAfter = adapter.readCount.get()
            println("=== T03 BACKGROUND ===")
            println("readsAtStop=$readsAtStop")
            println("readsAfter65s=$readsAfter")
            println("=== END T03 ===")

            assertEquals(
                "no read may happen while the polling is stopped",
                readsAtStop,
                readsAfter
            )
        } finally {
            scope.cancel()
        }
    }

    // -----------------------------------------------------------------
    // T03 (suite) — la reprise est unique.
    // -----------------------------------------------------------------

    @Test
    fun restartingThePollingProducesExactlyOneLoop() {
        val adapter = CountingAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val harness = Harness(adapter, scope)
            kotlinx.coroutines.runBlocking {
                harness.repository.connect("192.168.1.1", "admin", "secret")
            }

            // startPolling() est idempotent : l'appeler dix fois ne doit
            // creer qu'une seule boucle.
            repeat(10) { harness.coordinator.startPolling() }
            assertTrue(
                "the first read must happen at once",
                adapter.firstReadLatch.await(10, TimeUnit.SECONDS)
            )

            Thread.sleep(5_000L)
            val readsAfterFiveSeconds = adapter.readCount.get()

            println("=== T03 RESTART ===")
            println("readsAfter5s=$readsAfterFiveSeconds")
            println("=== END T03 RESTART ===")

            // Une seule boucle: une seule lecture immediate, et aucune
            // autre pendant 5 s (la periode est de 30 s).
            assertEquals(
                "the polling must be idempotent: one loop, one read",
                1,
                readsAfterFiveSeconds
            )

            harness.coordinator.stopPolling()
        } finally {
            scope.cancel()
        }
    }

    // -----------------------------------------------------------------
    // T04 — une erreur reseau n'est jamais une expiration.
    // -----------------------------------------------------------------

    @Test
    fun aNetworkFailureIsNeverAnExpiryOnTheDevice() {
        val adapter = CountingAdapter(
            failure = RouterUnavailableException.Unreachable()
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val harness = Harness(adapter, scope)
            kotlinx.coroutines.runBlocking {
                harness.repository.connect("192.168.1.1", "admin", "secret")
            }

            harness.coordinator.startPolling()
            assertTrue(
                "the first read must happen at once",
                adapter.firstReadLatch.await(10, TimeUnit.SECONDS)
            )

            val connection = harness.repository.state.value.connection
            println("=== T04 FAILURE ===")
            println("connection=$connection")
            println("=== END T04 FAILURE ===")

            assertFalse(
                "a network failure must never sign the user out",
                connection is ConnectionState.SessionExpired
            )
            assertTrue(
                "the session must be kept",
                connection is ConnectionState.Connected
            )

            harness.coordinator.stopPolling()
        } finally {
            scope.cancel()
        }
    }
}
