package dev.wifiadmin.app.data.speedtest

/**
 * The hard budget of an Internet speed test.
 *
 * A speed test is the first feature that leaves the LAN. The caps are
 * therefore part of the product contract: a test never transfers more
 * bytes nor runs longer than allowed, retries included. The values are
 * small enough to protect a prepaid data plan and large enough to measure
 * a mediocre link; on a fast link the cap is reached before the measure
 * stabilises, and the UI says so (result marked "capped", never a precise
 * Mbps).
 */
data class SpeedTestPolicy(
    val maxDurationMillis: Long = 30_000L,
    val maxDownloadBytes: Long = 20L * 1024 * 1024,
    val maxUploadBytes: Long = 10L * 1024 * 1024,
    val latencyProbes: Int = 5,
    val connectTimeoutMillis: Long = 8_000L,
    val readTimeoutMillis: Long = 10_000L,
    val writeTimeoutMillis: Long = 10_000L,
    val callTimeoutMillis: Long = 30_000L,
) {
    init {
        require(maxDurationMillis in 1..60_000) { "invalid duration cap" }
        require(maxDownloadBytes > 0) { "invalid download cap" }
        require(maxUploadBytes > 0) { "invalid upload cap" }
        require(latencyProbes in 1..9) { "invalid probe count" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "invalid timeout" }
        require(writeTimeoutMillis > 0 && callTimeoutMillis > 0) { "invalid timeout" }
        require(callTimeoutMillis >= maxDurationMillis) { "call timeout below duration cap" }
    }
}
