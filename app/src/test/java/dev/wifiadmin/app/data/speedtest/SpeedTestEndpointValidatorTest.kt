package dev.wifiadmin.app.data.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Seam S2 : validation pure de l'endpoint, sans Android, sans réseau. */
class SpeedTestEndpointValidatorTest {

    @Test fun `empty is not configured`() {
        assertNull(SpeedTestEndpointValidator.parse(""))
        assertNull(SpeedTestEndpointValidator.parse("   "))
    }

    @Test fun `http outside loopback is rejected`() {
        assertNull(SpeedTestEndpointValidator.parse("http://speed.example.org/"))
    }

    @Test fun `https is accepted and host label is host only`() {
        val endpoint = SpeedTestEndpointValidator.parse("https://speed.example.org/app/")
        assertEquals("speed.example.org", endpoint?.hostLabel)
        assertEquals("https://speed.example.org/app", endpoint?.baseUrl)
    }

    @Test fun `credentials in url are rejected`() {
        assertNull(SpeedTestEndpointValidator.parse("https://user:pass@speed.example.org/"))
    }

    @Test fun `token in query is rejected without logging the secret`() {
        assertNull(SpeedTestEndpointValidator.parse("https://speed.example.org/app/?token=sekret"))
    }

    @Test fun `loopback http is accepted for tests only`() {
        val endpoint = SpeedTestEndpointValidator.parse("http://127.0.0.1:8765/")
        assertEquals("127.0.0.1", endpoint?.hostLabel)
    }

    @Test fun `missing host is rejected`() {
        assertNull(SpeedTestEndpointValidator.parse("https://"))
    }

    @Test fun `fragment is rejected`() {
        assertNull(SpeedTestEndpointValidator.parse("https://speed.example.org/app#frag"))
    }
}
