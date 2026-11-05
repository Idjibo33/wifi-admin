package dev.wifiadmin.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The refresh policy of the application.
 *
 * The values live here rather than in the screens so that the behaviour is
 * testable without waiting, and so that a single place decides how often the
 * router is contacted.
 */
data class RefreshPolicy(
    /** The interval between two automatic reads. */
    val pollIntervalMillis: Long = 30_000L,
    /**
     * A read that happened less recently than this is still fresh enough.
     *
     * It is what lets the dashboard and the device list share one read
     * instead of each performing its own.
     */
    val freshnessMillis: Long = 25_000L,
    /**
     * The intervals used after consecutive failures, in order.
     *
     * The last value is used for every further failure. The back-off exists
     * because the firmware refuses logins for 180 seconds after too many
     * failures, and because a router that does not answer should not be
     * hammered.
     */
    val backoffIntervalsMillis: List<Long> = listOf(30_000L, 60_000L, 120_000L, 300_000L),
) {
    init {
        require(pollIntervalMillis > 0) { "the poll interval must be positive" }
        require(freshnessMillis >= 0) { "the freshness window cannot be negative" }
    }

    /** The delay to wait before the next automatic read. */
    fun delayAfter(failures: Int): Long {
        if (failures <= 0) return pollIntervalMillis
        val index = (failures - 1).coerceAtMost(backoffIntervalsMillis.lastIndex)
        return backoffIntervalsMillis.getOrElse(index) { pollIntervalMillis }
    }
}

/**
 * The single owner of the automatic refresh.
 *
 * ## Why it exists
 *
 * The dashboard and the device list show the same data. Without this class,
 * each screen would start its own loop, and the router would be contacted
 * several times for the same information — which matters because the
 * firmware refuses logins for 180 seconds after too many failures.
 *
 * ## What it guarantees
 *
 * - **One loop for the whole application.** The screens start and stop it,
 *   but there is only ever one.
 * - **One read at a time.** The repository deduplicates the reads themselves,
 *   so a manual refresh never overlaps a scheduled one.
 * - **The polling stops in the background.** The screens drive it with their
 *   own lifecycle, and the loop dies with the scope that owns it.
 * - **A cooldown suspends the polling.** Asking a router that just refused
 *   to log in is what turns a temporary refusal into a lockout.
 * - **The failures are counted**, so the back-off can grow.
 */
class RefreshCoordinator(
    private val repository: RouterRepository,
    private val scope: CoroutineScope,
    val policy: RefreshPolicy = RefreshPolicy(),
    private val ioDispatcher: CoroutineDispatcher = repository.networkDispatcher,
) {

    /** The running loop, or null when the polling is stopped. */
    private var pollJob: Job? = null

    /** Consecutive failures, used to compute the back-off. */
    private var consecutiveFailures = 0

    /** True while the polling loop is running. */
    val isPolling: Boolean get() = pollJob?.isActive == true

    /** The number of consecutive failures since the last success. */
    val failureCount: Int get() = consecutiveFailures

    /**
     * Starts the automatic refresh, if it is not already running.
     *
     * The method is **idempotent**: a screen may call it again after a
     * rotation or a return to the foreground, and the loop must not be
     * duplicated.
     *
     * The first read is immediate: the user who opens the dashboard wants
     * the data now, not in thirty seconds.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return

        pollJob = scope.launch(ioDispatcher) {
            while (isActive) {
                // A read that is not stale is skipped: two screens polling at
                // the same time produce one read, and a rotation does not
                // trigger an extra one.
                val result = repository.refreshIfStale(policy.freshnessMillis)
                if (result.isSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                delay(nextDelayMillis())
            }
        }
    }

    /**
     * Stops the automatic refresh.
     *
     * The loop is cancelled, not merely flagged: a cancelled coroutine does
     * not keep a wake-up pending, and the router is not contacted in the
     * background.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The delay before the next automatic read.
     *
     * A router that just refused to log in is left alone until the penalty
     * is over, and the delay grows with the number of failures.
     */
    private suspend fun nextDelayMillis(): Long {
        val cooldown = repository.cooldownRemainingMillis()
        if (cooldown != null) {
            // The router is refusing logins: wait exactly as long as it says.
            return cooldown
        }
        return policy.delayAfter(consecutiveFailures)
    }
}
