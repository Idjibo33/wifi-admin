package dev.wifiadmin.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mutualisation of the reads.
 *
 * This is the heart of Mission 09: the dashboard and the device list show
 * the same data, so they must cost **one** request, not two.
 *
 * The counter on the adapter is the proof. A design that only *serialises*
 * the reads would pass a test that checks "no two reads at the same time",
 * and would fail this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    private fun coordinator(
        adapter: RouterAdapter,
        scope: kotlinx.coroutines.CoroutineScope,
        freshness: ReadFreshnessPolicy = ReadFreshnessPolicy.Default,
    ): Pair<RouterRepository, ReadCoordinator> {
        val repository = TestHarness.repository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            scope = scope,
            clock = TestHarness.virtualClock(scope.coroutineContext[kotlinx.coroutines.test.TestCoroutineScheduler]!!),
        )
        return repository to ReadCoordinator(
            adapter = adapter,
            clock = TestHarness.virtualClock(scope.coroutineContext[kotlinx.coroutines.test.TestCoroutineScheduler]!!),
            scope = scope,
            freshness = freshness,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `two concurrent reads reach the adapter only once`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val (_, coordinator) = coordinator(adapter, this)

        val first = async { coordinator.read() }
        val second = async { coordinator.read() }
        advanceUntilIdle()

        assertEquals(
            "the router must be asked once, not twice",
            1,
            adapter.fetchCalls
        )
        first.await()
        second.await()
    }

    @Test
    fun `both concurrent readers receive the same snapshot`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_700_000_000_000L)
        }
        val (_, coordinator) = coordinator(adapter, this)

        val first = async { coordinator.read() }
        val second = async { coordinator.read() }
        advanceUntilIdle()

        val firstOutcome = first.await()
        val secondOutcome = second.await()

        assertEquals(firstOutcome, secondOutcome)
    }

    @Test
    fun `a fresh read is reused instead of asking the router again`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val (_, coordinator) = coordinator(adapter, this)

            coordinator.read()
            advanceUntilIdle()
            assertEquals(1, adapter.fetchCalls)

            val second = coordinator.read()
            advanceUntilIdle()

            assertEquals(
                "the data is still fresh: the router must not be asked again",
                1,
                adapter.fetchCalls
            )
            assertTrue(second is ReadOutcome.Success)
        }

    @Test
    fun `a manual read right after a scheduled read does not ask the router again`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val (_, coordinator) = coordinator(adapter, this)

            // The scheduled read.
            coordinator.read()
            advanceUntilIdle()

            // The manual read, a moment later.
            coordinator.read()
            advanceUntilIdle()

            assertEquals(
                "the user wants fresh data, and a read that just happened is fresh",
                1,
                adapter.fetchCalls
            )
        }

    @Test
    fun `a forced read ignores the freshness window`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter()
        val (_, coordinator) = coordinator(adapter, this)

        coordinator.read()
        advanceUntilIdle()
        coordinator.read(force = true)
        advanceUntilIdle()

        assertEquals(
            "a forced read always reaches the router",
            2,
            adapter.fetchCalls
        )
    }

    @Test
    fun `a failed read is not reusable`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            throw RouterUnavailableException.Unreachable()
        }
        val (_, coordinator) = coordinator(adapter, this)

        coordinator.read()
        advanceUntilIdle()

        coordinator.read()
        advanceUntilIdle()

        assertEquals(
            "a failure does not make the data fresh: the next attempt must " +
                "reach the router",
            2,
            adapter.fetchCalls
        )
    }

    @Test
    fun `the state reports that a read is in progress`() = runTest(dispatcher) {
        // The adapter suspends, so the read is still running when the state
        // is observed.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : RouterAdapter {
            override val displayName = "Fake"
            override suspend fun connect(host: String, username: String, password: String) = true
            override suspend fun fetchDevices(): dev.wifiadmin.router.DeviceSnapshot {
                gate.await()
                return FakeRouterAdapter.oneDevice(1_700_000_000_000L)
            }
            override fun disconnect() = Unit
        }
        val (_, coordinator) = coordinator(adapter, this)

        val read = async { coordinator.read() }
        runCurrent()

        assertTrue(
            "the state must say that a read is running",
            coordinator.state.value.isReading
        )
        assertTrue(
            "the state must say since when",
            coordinator.state.value.readingStartedAtMonotonicMillis != null
        )

        gate.complete(Unit)
        read.await()
        advanceUntilIdle()
        assertEquals(false, coordinator.state.value.isReading)
    }

    @Test
    fun `invalidating the coordinator forgets the reusable snapshot`() =
        runTest(dispatcher) {
            val adapter = FakeRouterAdapter()
            val (_, coordinator) = coordinator(adapter, this)

            coordinator.read()
            advanceUntilIdle()
            assertEquals(1, adapter.fetchCalls)

            coordinator.invalidate()
            coordinator.read()
            advanceUntilIdle()

            assertEquals(
                "after an invalidation the router must be asked again",
                2,
                adapter.fetchCalls
            )
        }
}
