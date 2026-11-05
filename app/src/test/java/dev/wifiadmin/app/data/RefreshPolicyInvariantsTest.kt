package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three defects found by the review sub-agents after the Mission 09
 * delivery.
 *
 * - a manual refresh sent a **second** request right after a scheduled one,
 *   because the coordinator's freshness window was dead code;
 * - a cooldown **without** a duration neutralised the suspension, so the
 *   application kept asking a router that was refusing logins;
 * - a manual refresh **during** a cooldown knocked on the router's door,
 *   bypassing the suspension.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshPolicyInvariantsTest {

    private val dispatcher = StandardTestDispatcher()

    private suspend fun TestScope.harness(
        adapter: RouterAdapter,
        policy: RefreshPolicy = RefreshPolicy(),
    ): Triple<RouterRepository, RefreshCoordinator, RouterAdapter> {
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
        )
        repository.connect("192.168.1.1", "admin", "secret")
        // `StandardTestDispatcher` only runs a task when the test advances
        // the scheduler: without this, the suspending `connect()` above never
        // completes and the whole test deadlocks.
        runCurrent()
        val coordinator = TestHarness.coordinator(
            repository = repository,
            scope = this,
            policy = policy,
        )
        return Triple(repository, coordinator, adapter)
    }

    // -----------------------------------------------------------------
    // The manual refresh must not double a recent read.
    // -----------------------------------------------------------------

    @Test
    fun `a manual refresh right after a scheduled read does not ask again`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()
            assertEquals(1, adapter.fetchCalls)

            // The user presses the refresh button.
            // The refresh must run in the test scope: a suspending call made
            // directly in the test body would block it, because a
            // `StandardTestDispatcher` only runs when the test advances.
            //
            // `advanceTimeBy` and not `advanceUntilIdle`: the polling loop is
            // infinite, so `advanceUntilIdle` would run it for ever.
            val manual = async { repository.refresh() }
            advanceTimeBy(1_000L)
            runCurrent()
            manual.await()

            assertEquals(
                "the data is fresh: the router must not be asked again",
                1,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `a manual refresh after the freshness window asks again`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()
            assertEquals(1, adapter.fetchCalls)

            // The data is now older than the freshness window.
            advanceTimeBy(26_000)
            val manual = async { repository.refresh() }
            advanceTimeBy(1_000L)
            runCurrent()
            manual.await()

            assertEquals(
                "the data is stale: the router must be asked again",
                2,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `a manual refresh during a scheduled read does not double it`() =
        runTest(dispatcher) {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val adapter = object : RouterAdapter {
                override val displayName = "Fake"
                var fetchCalls = 0

                override suspend fun connect(
                    host: String,
                    username: String,
                    password: String,
                ) = true

                override suspend fun fetchDevices(): DeviceSnapshot {
                    fetchCalls++
                    gate.await()
                    return FakeRouterAdapter.oneDevice(1_700_000_000_000L)
                }

                override fun disconnect() = Unit
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()

            // The manual refresh arrives while the scheduled one runs.
            val manual = async { repository.refresh() }
            runCurrent()

            gate.complete(Unit)
            advanceTimeBy(1_000L)
            runCurrent()
            manual.await()

            assertEquals(
                "the two callers must share one request",
                1,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    // -----------------------------------------------------------------
    // The cooldown must always suspend.
    // -----------------------------------------------------------------

    @Test
    fun `a cooldown without a duration still suspends the reads`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.Cooldown(null)
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()
            assertEquals(1, adapter.fetchCalls)

            // The router refuses without saying for how long: the documented
            // penalty of 180 seconds is used.
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "a router that refuses logins must be left alone",
                1,
                adapter.fetchCalls
            )
            assertTrue(
                "the penalty must be known",
                repository.cooldownRemainingMillis() != null
            )
            coordinator.stopPolling()
        }

    @Test
    fun `a manual refresh during a cooldown does not reach the router`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter {
                throw RouterUnavailableException.Cooldown(180)
            }
            val (repository, coordinator, _) = harness(adapter)

            coordinator.startPolling()
            runCurrent()
            val callsAfterCooldown = adapter.fetchCalls

            val manual = async { repository.refresh() }
            advanceTimeBy(1_000L)
            runCurrent()
            manual.await()

            assertEquals(
                "the penalty must be respected by the manual refresh too",
                callsAfterCooldown,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `the reads resume once the penalty is over`() = runTest(dispatcher) {
        var fail = true
        val adapter = FakeRouterAdapter {
            if (fail) throw RouterUnavailableException.Cooldown(60)
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val (repository, coordinator, _) = harness(adapter)

        coordinator.startPolling()
        runCurrent()
        assertEquals(1, adapter.fetchCalls)

        // The penalty is over.
        fail = false
        advanceTimeBy(61_000)
        runCurrent()

        assertTrue(
            "the reads must resume after the penalty",
            adapter.fetchCalls > 1
        )
        assertFalse(
            "the session must not be an expiry",
            repository.state.value.connection is ConnectionState.SessionExpired
        )
        coordinator.stopPolling()
    }
}
