package dev.wifiadmin.app.ui.dashboard

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.FakeRouterAdapter
import dev.wifiadmin.app.data.ReadFreshnessPolicy
import dev.wifiadmin.app.data.RefreshCoordinator
import dev.wifiadmin.app.data.RouterRepository
import dev.wifiadmin.app.data.TestAppClock
import dev.wifiadmin.app.data.TestHarness
import kotlinx.coroutines.CoroutineScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelRefreshReuseTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A manual refresh that reuses a read which just succeeded must not leave
     * the spinner spinning forever: the coordinator reuses the snapshot and
     * emits no new read state, so the view model has to clear it on success.
     */
    @Test
    fun `a reused manual refresh clears the spinner`() = runTest(dispatcher) {
        val adapter = FakeRouterAdapter {
            FakeRouterAdapter.oneDevice(1_000L)
        }
        val repository = RouterRepository(
            adapter = adapter,
            ioDispatcher = dispatcher,
            clock = TestAppClock(),
            freshness = ReadFreshnessPolicy.Default,
        )
        repository.connect("192.168.1.1", "admin", "secret")
        repository.refreshForced()
        assertEquals(1, adapter.fetchCalls)

        val coordinator = TestHarness.coordinator(repository, backgroundScope)
        val viewModel = DashboardViewModel(repository, coordinator)

        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasSnapshot)
        assertFalse(viewModel.state.value.isRefreshing)

        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(
            "the spinner must stop after a reused manual refresh",
            viewModel.state.value.isRefreshing,
        )
        assertEquals("no second router request", 1, adapter.fetchCalls)
    }
}