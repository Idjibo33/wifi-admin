package dev.wifiadmin.app.data.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure arithmetic of the speed test: no network, full coverage. */
class SpeedTestCalculatorTest {

    @Test fun `mbps converts bytes and duration correctly`() {
        assertEquals(10.0, SpeedTestCalculator.mbps(10_000_000, 8_000)!!, 0.0001)
    }

    @Test fun `section fractional duration`() {
        assertEquals(16.0, SpeedTestCalculator.mbps(5_000_000, 2_500)!!, 0.0001)
    }

    @Test fun `zero bytes is zero, never a null`() {
        assertEquals(0.0, SpeedTestCalculator.mbps(0, 5_000)!!, 0.0001)
    }

    @Test fun `zero duration is null`() {
        assertNull(SpeedTestCalculator.mbps(1_000, 0))
    }

    @Test fun `negative input is null`() {
        assertNull(SpeedTestCalculator.mbps(-1, 1_000))
    }

    @Test fun `median latency ignores the spike`() {
        assertEquals(25L, SpeedTestCalculator.medianLatencyMillis(listOf(20, 25, 100, 22, 30)))
    }

    @Test fun `median latency with even count is defined`() {
        assertEquals(25L, SpeedTestCalculator.medianLatencyMillis(listOf(10, 20, 30, 40)))
    }

    @Test fun `median latency empty is null`() {
        assertNull(SpeedTestCalculator.medianLatencyMillis(emptyList()))
    }

    @Test fun `caps are hard limits`() {
        val policy = SpeedTestPolicy(maxDownloadBytes = 100, maxUploadBytes = 100)
        assertTrue(SpeedTestCalculator.isCapped(100, 0, policy))
        assertFalse(SpeedTestCalculator.isCapped(99, 99, policy))
    }

    @Test fun `gauge scale is always above the value`() {
        assertTrue(SpeedTestCalculator.gaugeScaleMaxMbps(42.0) >= 42.0)
        assertTrue(SpeedTestCalculator.gaugeScaleMaxMbps(null) >= 10.0)
        assertTrue(SpeedTestCalculator.gaugeScaleMaxMbps(2_000.0) == 1_000.0)
    }

    @Test fun `too short transfer is never trusted`() {
        assertTrue(SpeedTestCalculator.isTooShortToTrust(1_000, 500))
        assertFalse(SpeedTestCalculator.isTooShortToTrust(5_000_000, 5_000))
    }

    @Test fun `policy validates`() {
        assertThrows(IllegalArgumentException::class.java) { SpeedTestPolicy(maxDurationMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { SpeedTestPolicy(maxDownloadBytes = 0) }
    }
}
