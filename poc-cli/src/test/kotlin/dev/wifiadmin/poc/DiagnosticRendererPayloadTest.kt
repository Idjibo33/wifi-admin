package dev.wifiadmin.poc

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.RouterDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRendererPayloadTest {

    @Test
    fun `an absent payload is not reported as zero devices`() {
        val report = DiagnosticRenderer.render(
            devices = emptyList(),
            payloadAbsent = true,
        )

        assertTrue(report.contains("INCONNU"))
        assertFalse(
            "the report must not claim the network is empty",
            report.contains("Appareils détectés : 0"),
        )
    }

    @Test
    fun `a confirmed empty network is reported as zero devices`() {
        val report = DiagnosticRenderer.render(
            devices = emptyList(),
            payloadAbsent = false,
        )

        assertTrue(report.contains("Appareils détectés : 0"))
        assertFalse(report.contains("INCONNU"))
    }

    @Test
    fun `a device is reported normally`() {
        val report = DiagnosticRenderer.render(
            devices = listOf(
                RouterDevice(
                    mac = "AA:BB:CC:DD:EE:01",
                    ip = "192.168.1.10",
                    presence = DevicePresence.ACTIVE,
                    name = "Test",
                    bands = emptySet(),
                    signal = null,
                    interfaceName = "",
                    leaseExpiry = null,
                    ipv6 = null,
                )
            ),
            payloadAbsent = false,
        )

        assertTrue(report.contains("Appareils détectés : 1"))
        assertFalse(report.contains("INCONNU"))
    }
}
