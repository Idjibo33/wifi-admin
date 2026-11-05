package dev.wifiadmin.app.data

/**
 * The two clocks the application needs.
 *
 * They are separated on purpose:
 *
 * - **monotonic** time measures **durations**. It never goes backwards, not
 *   even when the user changes the date or the time zone of the phone, so it
 *   is the only safe basis for a polling interval or a back-off delay.
 * - **wall clock** time **labels** a moment. It is what the user reads in
 *   "actualisées le …", and what a stored snapshot carries.
 *
 * On Android the monotonic clock is `SystemClock.elapsedRealtime()`. In the
 * tests it is a counter the test drives, so a 30 second polling interval can
 * be exercised without waiting 30 seconds.
 */
interface AppClock {

    /**
     * A monotonic timestamp, in milliseconds.
     *
     * The value only means something when compared to another value taken
     * from the same clock.
     */
    fun monotonicMillis(): Long

    /** The current wall-clock time, in epoch milliseconds. */
    fun wallClockMillis(): Long
}

/**
 * The production clock.
 *
 * [android.os.SystemClock.elapsedRealtime] is used for durations and
 * [System.currentTimeMillis] for labels. The Android dependency is isolated
 * here so the rest of the application can be tested on the JVM.
 */
class SystemAppClock : AppClock {

    override fun monotonicMillis(): Long = android.os.SystemClock.elapsedRealtime()

    override fun wallClockMillis(): Long = System.currentTimeMillis()
}

/**
 * A clock the tests drive.
 *
 * The monotonic time only moves when the test says so, which makes every
 * delay of the application observable and instantaneous.
 */
class TestAppClock(
    startMonotonicMillis: Long = 0L,
    startWallClockMillis: Long = 1_700_000_000_000L,
) : AppClock {

    private var monotonic = startMonotonicMillis
    private var wallClock = startWallClockMillis

    override fun monotonicMillis(): Long = monotonic

    override fun wallClockMillis(): Long = wallClock

    /** Moves both clocks forward. */
    fun advance(millis: Long) {
        require(millis >= 0) { "time only goes forward" }
        monotonic += millis
        wallClock += millis
    }
}
