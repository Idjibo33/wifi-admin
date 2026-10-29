package dev.wifiadmin.poc

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRendererTest {

    private fun device(
        name: String = "pixel-6",
        ip: String = "192.168.1.192",
        mac: String = "aa:bb:cc:dd:ee:ff",
        bands: Set<WifiBand> = setOf(WifiBand.BAND_5_GHZ),
        signal: DeviceSignal? = DeviceSignal(-60, "866", "866", "MyHomeNetwork"),
        interfaceName: String = "wlan",
        leaseExpiry: String? = "3600",
        ipv6: String? = null,
        presence: DevicePresence = DevicePresence.ACTIVE
    ) = RouterDevice(
        mac = mac,
        ip = ip,
        name = name,
        bands = bands,
        signal = signal,
        interfaceName = interfaceName,
        leaseExpiry = leaseExpiry,
        ipv6 = ipv6,
        presence = presence
    )

    @Test
    fun `the diagnostic never exposes a mac an ip a name or a ssid`() {
        val output = DiagnosticRenderer.render(
            listOf(
                device(),
                device(name = "iPhone", ip = "10.0.0.5", mac = "11:22:33:44:55:66",
                    signal = DeviceSignal(-70, "72", "54", "SecretWifiName"))
            )
        )

        listOf(
            "aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66",
            "192.168.1.192", "10.0.0.5",
            "pixel-6", "iPhone",
            "MyHomeNetwork", "SecretWifiName"
        ).forEach { secret ->
            assertFalse("the diagnostic must not contain '$secret'", output.contains(secret))
        }
    }

    @Test
    fun `the diagnostic reports the device count and the presence breakdown`() {
        val output = DiagnosticRenderer.render(
            listOf(
                device(presence = DevicePresence.ACTIVE),
                device(presence = DevicePresence.ACTIVE),
                device(presence = DevicePresence.CABLED, interfaceName = "lan"),
                device(presence = DevicePresence.UNKNOWN, leaseExpiry = null),
                device(presence = DevicePresence.STALE, leaseExpiry = "expired")
            )
        )

        assertTrue(output.contains("5"))       // total
        assertTrue(output.contains("ACTIVE"))
        assertTrue(output.contains("CABLED"))
        assertTrue(output.contains("UNKNOWN"))
        assertTrue(output.contains("STALE"))
    }

    @Test
    fun `the diagnostic counts the devices per band`() {
        val output = DiagnosticRenderer.render(
            listOf(
                device(bands = setOf(WifiBand.BAND_5_GHZ)),
                device(bands = setOf(WifiBand.BAND_2_4_GHZ)),
                device(bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ)),
                device(bands = emptySet(), interfaceName = "lan")
            )
        )

        assertTrue(output.contains("2.4 GHz"))
        assertTrue(output.contains("5 GHz"))
    }

    @Test
    fun `the diagnostic describes the shape of expires without showing the value`() {
        val output = DiagnosticRenderer.render(
            listOf(
                device(leaseExpiry = "3600"),
                device(leaseExpiry = "24h"),
                device(leaseExpiry = null)
            )
        )

        // The numeric value must not leak: only its shape is reported.
        assertFalse(output.contains("3600"))
        assertFalse(output.contains("24h"))
        assertTrue(output.contains("expires"))
    }

    @Test
    fun `the diagnostic reports which commands answered`() {
        val output = DiagnosticRenderer.render(
            listOf(device()),
            commands = listOf(232, 100, 223, 224, 225)
        )

        assertTrue(output.contains("223"))
        assertTrue(output.contains("224"))
        assertTrue(output.contains("225"))
    }

    @Test
    fun `an empty device list is reported without crashing`() {
        val output = DiagnosticRenderer.render(emptyList())

        assertTrue(output.contains("0"))
    }
}
