package dev.wifiadmin.app.data.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 16 — the validation budget can only lower the production caps.
 *
 * These tests are pure JVM: no socket, no server, no Internet. They prove
 * that a Gradle property (or a future caller) cannot widen the transfer
 * beyond the product contract, and that the exact debug preset is legal.
 */
class SpeedTestBudgetTest {

    private val production = SpeedTestPolicy()

    @Test
    fun `production caps are the reference`() {
        val policy = SpeedTestBudget.production
        assertEquals(50L * 1024 * 1024, policy.maxDownloadBytes)
        assertEquals(25L * 1024 * 1024, policy.maxUploadBytes)
        assertEquals(30_000L, policy.maxDurationMillis)
        assertEquals(5, policy.latencyProbes)
        assertFalse(SpeedTestBudget.isReduced(policy))
    }

    @Test
    fun `the mission 16 reduced preset is exact`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 1L shl 20,
            maxUploadBytes = 256L * 1024,
            maxDurationMillis = 10_000L,
        )
        assertEquals(1L shl 20, policy.maxDownloadBytes)
        assertEquals(256L * 1024, policy.maxUploadBytes)
        assertEquals(10_000L, policy.maxDurationMillis)
        assertEquals(5, policy.latencyProbes)
        assertTrue(SpeedTestBudget.isReduced(policy))
        // Init constraint: callTimeout must cover the duration cap.
        assertTrue(policy.callTimeoutMillis >= policy.maxDurationMillis)
    }

    @Test
    fun `caps above production are clamped, never widened`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 100L * 1024 * 1024,
            maxUploadBytes = 100L * 1024 * 1024,
            maxDurationMillis = 120_000L,
        )
        assertEquals(production.maxDownloadBytes, policy.maxDownloadBytes)
        assertEquals(production.maxUploadBytes, policy.maxUploadBytes)
        assertEquals(production.maxDurationMillis, policy.maxDurationMillis)
        assertFalse(SpeedTestBudget.isReduced(policy))
    }

    @Test
    fun `non positive caps fall back to production`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 0L,
            maxUploadBytes = -1L,
            maxDurationMillis = 0L,
        )
        assertEquals(production.maxDownloadBytes, policy.maxDownloadBytes)
        assertEquals(production.maxUploadBytes, policy.maxUploadBytes)
        assertEquals(production.maxDurationMillis, policy.maxDurationMillis)
    }

    @Test
    fun `production values passed explicitly reproduce the default policy`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = production.maxDownloadBytes,
            maxUploadBytes = production.maxUploadBytes,
            maxDurationMillis = production.maxDurationMillis,
        )
        assertEquals(SpeedTestPolicy(), policy)
    }

    @Test
    fun `one byte above production is clamped`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = production.maxDownloadBytes + 1,
            maxUploadBytes = production.maxUploadBytes + 1,
            maxDurationMillis = production.maxDurationMillis + 1,
        )
        assertEquals(production.maxDownloadBytes, policy.maxDownloadBytes)
        assertEquals(production.maxUploadBytes, policy.maxUploadBytes)
        assertEquals(production.maxDurationMillis, policy.maxDurationMillis)
        assertFalse(SpeedTestBudget.isReduced(policy))
    }

    @Test
    fun `mixed overrides reduce only the widened values`() {
        val policy = SpeedTestBudget.fromCaps(
            maxDownloadBytes = 1L shl 20,
            maxUploadBytes = production.maxUploadBytes,
            maxDurationMillis = production.maxDurationMillis,
        )
        assertEquals(1L shl 20, policy.maxDownloadBytes)
        assertEquals(production.maxUploadBytes, policy.maxUploadBytes)
        assertEquals(production.maxDurationMillis, policy.maxDurationMillis)
        assertTrue(SpeedTestBudget.isReduced(policy))
    }
}
