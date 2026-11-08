package dev.wifiadmin.app.data.speedtest

/**
 * Pure arithmetic of a speed measurement.
 *
 * Mbps = (bytes × 8) / (seconds × 1_000_000). The class is free of Android
 * and of coroutines so every rule is unit-testable on the JVM: zero rates,
 * latency as the median of the probes (never min or max), a cap reached
 * before stabilisation (result is a floor, never a precise figure).
 */
object SpeedTestCalculator {

    /** Megabits per second for a completed transfer. */
    fun mbps(bytes: Long, durationMillis: Long): Double? {
        if (bytes < 0 || durationMillis <= 0) return null
        if (bytes == 0L) return 0.0
        return (bytes * 8.0) / (durationMillis / 1000.0) / 1_000_000.0
    }

    /**
     * The typical latency of a probe series: the median, robust against a
     * single spike. Null when no probe succeeded (never 0 ms).
     */
    fun medianLatencyMillis(probesMillis: List<Long>): Long? {
        val sorted = probesMillis.filter { it >= 0 }.sorted()
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** True when a cap stopped the transfer before a stable window. */
    fun isCapped(
        downloadBytes: Long, uploadBytes: Long,
        policy: SpeedTestPolicy,
    ): Boolean = downloadBytes >= policy.maxDownloadBytes || uploadBytes >= policy.maxUploadBytes

    /** A coarse, honest gauge scale: the next rounded step above the value. */
    fun gaugeScaleMaxMbps(valueMbps: Double?): Double {
        val steps = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)
        val value = valueMbps ?: 0.0
        return steps.firstOrNull { it >= value } ?: steps.last()
    }

    /** True when a transfer window is too short or too small to trust. */
    fun isTooShortToTrust(bytes: Long, durationMillis: Long): Boolean =
        bytes < 0 || durationMillis < 1_000L
}
