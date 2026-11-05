package dev.wifiadmin.app.data

import dev.wifiadmin.router.DeviceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The automatic refresh: one loop, one read per period, and no read at all
 * when the screen is not visible.
 *
 * The thirty seconds are **virtual**: the test scheduler moves the clock,
 * nothing ever waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    private suspend fun TestScope.harness(
        adapter: FakeRouterAdapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(CAPTURED_AT)
        },
        policy: RefreshPolicy = RefreshPolicy(),
    ): Triple<RouterRepository, RefreshCoordinator, FakeRouterAdapter> {
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            scope = this,
            clock = TestHarness.virtualClock(testScheduler),
        )
        val coordinator = TestHarness.coordinator(
            repository = repository,
            scope = this,
            policy = policy,
        )
        // The session must be open: the application only reads when it is.
        repository.connect("192.168.1.1", "admin", "secret")
        return Triple(repository, coordinator, adapter)
    }

    @Test
    fun `the polling reads immediately when the screen becomes visible`() =
        runTest(dispatcher) {
            val (_, coordinator, adapter) = harness()

            coordinator.startPolling()
            runCurrent()

            assertEquals(
                "the user must not wait thirty seconds to see the devices",
                1,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `the polling reads every thirty seconds`() = runTest(dispatcher) {
        val (_, coordinator, adapter) = harness()

        coordinator.startPolling()
        runCurrent()
        assertEquals(1, adapter.fetchCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, adapter.fetchCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, adapter.fetchCalls)

        coordinator.stopPolling()
    }

    @Test
    fun `the polling does not read again before thirty seconds have passed`() =
        runTest(dispatcher) {
            val (_, coordinator, adapter) = harness()

            coordinator.startPolling()
            runCurrent()

            advanceTimeBy(29_999)
            runCurrent()

            assertEquals(
                "the read must not happen before the full period",
                1,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `stopping the polling prevents any further read`() = runTest(dispatcher) {
        val (_, coordinator, adapter) = harness()

        coordinator.startPolling()
        runCurrent()
        val before = adapter.fetchCalls

        coordinator.stopPolling()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(
            "the router must not be contacted in the background",
            before,
            adapter.fetchCalls
        )
    }

    @Test
    fun `starting the polling twice does not double the reads`() =
        runTest(dispatcher) {
            val (_, coordinator, adapter) = harness()

            coordinator.startPolling()
            coordinator.startPolling()
            runCurrent()

            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(
                "the loop must not be duplicated",
                2,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `a rotation does not trigger an extra read`() = runTest(dispatcher) {
        val (_, coordinator, adapter) = harness()

        coordinator.startPolling()
        runCurrent()
        assertEquals(1, adapter.fetchCalls)

        // The screen leaves the composition and comes back.
        coordinator.stopPolling()
        coordinator.startPolling()
        runCurrent()

        assertEquals(
            "the data is still fresh: no extra read is needed",
            1,
            adapter.fetchCalls
        )
        coordinator.stopPolling()
    }

    @Test
    fun `a return to the foreground after five minutes triggers a read`() =
        runTest(dispatcher) {
            val (_, coordinator, adapter) = harness()

            coordinator.startPolling()
            runCurrent()
            assertEquals(1, adapter.fetchCalls)

            // Five minutes in the background: the polling is stopped, and the
            // data is now stale.
            coordinator.stopPolling()
            advanceTimeBy(5 * 60_000)
            coordinator.startPolling()
            runCurrent()

            assertEquals(
                "the user expects fresh data when they come back",
                2,
                adapter.fetchCalls
            )
            coordinator.stopPolling()
        }

    @Test
    fun `the polling survives a failed read`() = runTest(dispatcher) {
        var fail = true
        val adapter = FakeRouterAdapter {
            if (fail) throw RouterUnavailableException.Unreachable()
            FakeRouterAdapter.oneDevice(CAPTURED_AT)
        }
        val (_, coordinator, _) = harness(adapter = adapter)

        coordinator.startPolling()
        runCurrent()
        assertEquals(1, adapter.fetchCalls)

        // The router comes back: the polling continues.
        fail = false
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(2, adapter.fetchCalls)
        coordinator.stopPolling()
    }

    @Test
    fun `the polling never opens a session on its own`() = runTest(dispatcher) {
        val (_, coordinator, adapter) = harness()

        val connectsAfterLogin = adapter.connectCalls
        coordinator.startPolling()
        runCurrent()
        advanceTimeBy(30_000 * 3)
        runCurrent()

        assertEquals(
            "the polling only reads: it never reconnects",
            connectsAfterLogin,
            adapter.connectCalls
        )
        coordinator.stopPolling()
    }

    @Test
    fun `a cooldown suspends the polling for the time the router asked`() =
        runTest(dispatcher) {
            var fail = true
            val adapter = FakeRouterAdapter {
                if (fail) throw RouterUnavailableException.Cooldown(180)
                FakeRouterAdapter.oneDevice(CAPTURED_AT)
            }
            val (_, coordinator, _) = harness(adapter = adapter)

            coordinator.startPolling()
            runCurrent()
            assertEquals(1, adapter.fetchCalls)

            // The router refuses logins for 180 seconds: the polling must not
            // knock on its door again before that.
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "a router that refuses logins must be left alone",
                1,
                adapter.fetchCalls
            )

            // After the penalty, the reads resume.
            fail = false
            advanceTimeBy(180_000)
            runCurrent()
            assertTrue("the reads must resume after the penalty", adapter.fetchCalls > 1)
            coordinator.stopPolling()
        }

    @Test
    fun `no coroutine is left running after the polling is stopped`() =
        runTest(dispatcher) {
            val (_, coordinator, adapter) = harness()

            coordinator.startPolling()
            runCurrent()
            coordinator.stopPolling()

            advanceTimeBy(300_000)
            advanceUntilIdle()

            val before = adapter.fetchCalls
            advanceTimeBy(300_000)
            advanceUntilIdle()

            assertEquals(before, adapter.fetchCalls)
            assertFalse(coordinator.isPolling)
        }

    companion object {
        private const val CAPTURED_AT = 1_700_000_000_000L
    }
}
