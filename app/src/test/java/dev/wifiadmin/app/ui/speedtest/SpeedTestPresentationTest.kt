package dev.wifiadmin.app.ui.speedtest

import dev.wifiadmin.app.data.speedtest.SpeedTestFailure
import dev.wifiadmin.app.data.speedtest.SpeedTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam S4-pure: the French presentation helpers that SpeedTestContent
 * composes (provider banner, capped rates, consumption, failures).
 * Expected values are independent literals, never recomputed with the
 * production formulas.
 */
class SpeedTestPresentationTest {

    // -- Provider banner: providerLabel + "vers <hôte>" --------------------

    @Test fun `configured banner shows provider and host without URL scheme`() {
        val text = providerBannerText(providerLabel = "LibreSpeed", targetHost = "speed.example.org")
        assertEquals(
            "Fournisseur : LibreSpeed · vers speed.example.org (HTTPS) · Consommation max. 30 Mo/test",
            text,
        )
    }

    @Test fun `banner never shows full URL or credentials`() {
        val text = providerBannerText(
            providerLabel = "LibreSpeed",
            targetHost = "https://user:pass@speed.example.org:443/app/evil?token=abc",
        )
        assertEquals(
            "Fournisseur : LibreSpeed · vers speed.example.org (HTTPS) · Consommation max. 30 Mo/test",
            text,
        )
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("user:pass"))
        assertFalse(text.contains("token"))
    }

    @Test fun `banner extracts host from LibreSpeed style label`() {
        val text = providerBannerText(
            providerLabel = "LibreSpeed — vers speed.example.org",
            targetHost = null,
        )
        assertTrue(text.contains("LibreSpeed — vers speed.example.org"))
        assertTrue(text.contains("Consommation max. 30 Mo/test"))
        assertFalse(text.contains("https://"))
    }

    @Test fun `banner hides IP literal target`() {
        val text = providerBannerText(providerLabel = "LibreSpeed", targetHost = "203.0.113.7")
        assertFalse("must never display an IP: $text", text.contains("203.0.113.7"))
        assertTrue(text.contains("Fournisseur : LibreSpeed"))
    }

    // -- Capped rates ------------------------------------------------------

    @Test fun `capped download is shown as a floor with plafonné suffix`() {
        assertEquals("≥ 42,5 Mbps (plafonné)", formatMbpsCapped(42.5, capped = true))
    }

    @Test fun `non capped download stays a plain figure`() {
        assertEquals("42,5", formatMbpsCapped(42.5, capped = false))
        assertEquals("—", formatMbpsCapped(null, capped = true))
    }

    // -- Consumption -------------------------------------------------------

    @Test fun `consumption is shown in Mo for both directions`() {
        // 5 MiB / 2.5 MiB are chosen so "5,0 Mo / 2,5 Mo" is an
        // independent literal: 1 Mo = 1 048 576 octets (policy units).
        assertEquals("5,0 Mo / 2,5 Mo", formatConsumption(5L * 1024 * 1024, 2L * 1024 * 1024 + 512L * 1024))
        assertEquals("5,0 Mo", formatMo(5L * 1024 * 1024))
        assertEquals("—", formatMo(null))
    }

    @Test fun `consumption line describes the measured result`() {
        val result = SpeedTestResult(
            downloadMbps = 42.5, uploadMbps = 18.2, latencyMillis = 24,
            downloadBytes = 5L * 1024 * 1024, uploadBytes = 2L * 1024 * 1024 + 512L * 1024,
            durationMillis = 8_000, capped = false, providerLabel = "LibreSpeed",
            networkKey = "host", wifiLink = null, capturedAtWallMillis = 1L,
        )
        assertEquals("Consommation : 5,0 Mo / 2,5 Mo (réception / envoi)", consumptionLine(result))
    }

    // -- Failures: French, distinct, no leak --------------------------------

    @Test fun `each failure has its own French message without stacktrace`() {
        val messages = listOf(
            SpeedTestFailure.NoWiFi,
            SpeedTestFailure.Disabled,
            SpeedTestFailure.Timeout,
            SpeedTestFailure.ServerUnavailable,
            SpeedTestFailure.Incomplete,
            SpeedTestFailure.Unexpected,
        ).map(::failureMessage)
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { message ->
            assertTrue("French message expected: $message", message.isNotBlank())
            assertFalse(message.contains("Exception"))
            assertFalse(message.contains("stacktrace"))
            assertFalse(message.contains("token"))
            assertFalse(message.contains("@"))
        }
        assertEquals("Test de vitesse Internet indisponible dans cette version.", failureMessage(SpeedTestFailure.Disabled))
    }

    @Test fun `timeout and incomplete stay distinct`() {
        assertFalse(
            failureMessage(SpeedTestFailure.Timeout).equals(failureMessage(SpeedTestFailure.Incomplete)),
        )
    }
}
