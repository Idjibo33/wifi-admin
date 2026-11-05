package dev.wifiadmin.app.data

import kotlinx.coroutines.CoroutineScope

/**
 * Builds the objects a screen needs, in a way the tests can control.
 *
 * The **freshness window is disabled** on purpose: a test that asserts the
 * router was reached must not be satisfied by a cached read. With the window
 * closed, every read really goes to the adapter.
 */
object TestHarness {

    /**
     * A clock that follows the **virtual** time of the test scheduler.
     *
     * The scheduler's `currentTime` is the elapsed time of the test, so a
     * `advanceTimeBy(30_000)` moves it by thirty seconds. The application
     * then sees exactly what it would see after thirty real seconds, and
     * the freshness window behaves as it does in production.
     */
    fun virtualClock(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): AppClock =
        object : AppClock {
            override fun monotonicMillis(): Long = scheduler.currentTime
            override fun wallClockMillis(): Long = WALL_CLOCK_ORIGIN + scheduler.currentTime
        }

    private const val WALL_CLOCK_ORIGIN = 1_700_000_000_000L

    fun repository(
        adapter: RouterAdapter,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        snapshotStore: SnapshotStorage? = null,
        clock: AppClock = TestAppClock(),
        scope: CoroutineScope = CoroutineScope(ioDispatcher),
        networkMonitor: NetworkMonitor? = null,
    ): RouterRepository = RouterRepository(
        adapter = adapter,
        ioDispatcher = ioDispatcher,
        snapshotStore = snapshotStore,
        clock = clock,
        scope = scope,
        // Every read reaches the adapter: the freshness window must not hide
        // a call the test is asserting on.
        freshness = ReadFreshnessPolicy { false },
        networkMonitor = networkMonitor,
    )

    fun coordinator(
        repository: RouterRepository,
        scope: CoroutineScope,
        clock: AppClock = TestAppClock(),
        policy: RefreshPolicy = RefreshPolicy(),
    ): RefreshCoordinator = RefreshCoordinator(
        repository = repository,
        scope = scope,
        policy = policy,
    )
}
