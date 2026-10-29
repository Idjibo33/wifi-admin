package dev.wifiadmin.poc

import dev.wifiadmin.router.DevicePresence
import dev.wifiadmin.router.DeviceSignal
import dev.wifiadmin.router.RouterDevice
import dev.wifiadmin.router.WifiBand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTest {

    private fun device(
        name: String = "pixel-6",
        ip: String = "192.168.1.192",
        mac: String = "aa:bb:cc:dd:ee:ff",
        bands: Set<WifiBand> = setOf(WifiBand.BAND_5_GHZ),
        signal: DeviceSignal? = DeviceSignal(-60, "866", "866", "wifi"),
        interfaceName: String = "wlan",
        presence: DevicePresence = DevicePresence.ACTIVE
    ) = RouterDevice(
        mac = mac,
        ip = ip,
        name = name,
        bands = bands,
        signal = signal,
        interfaceName = interfaceName,
        leaseExpiry = "3600",
        ipv6 = "",
        presence = presence
    )

    @Test
    fun `console output lists name ip mac band signal and presence`() {
        val output = ConsoleRenderer.render(listOf(device()))

        assertTrue(output.contains("pixel-6"))
        assertTrue(output.contains("192.168.1.192"))
        assertTrue(output.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(output.contains("5 GHz"))
        assertTrue(output.contains("-60 dBm"))
        assertTrue(output.contains("active"))
    }

    @Test
    fun `console output reports an empty device list`() {
        assertEquals("No devices reported by the router.", ConsoleRenderer.render(emptyList()))
    }

    @Test
    fun `json output contains no credentials tokens or session fields`() {
        val json = JsonRenderer.render(listOf(device())).lowercase()

        listOf("password", "passwd", "token", "session", "username").forEach { forbidden ->
            assertFalse("JSON must not mention '$forbidden'", json.contains(forbidden))
        }
    }

    @Test
    fun `json output exposes the documented schema`() {
        val root = JSONObject(JsonRenderer.render(listOf(device())))

        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals(1, root.getInt("deviceCount"))
        val first = root.getJSONArray("devices").getJSONObject(0)
        assertEquals("pixel-6", first.getString("name"))
        assertEquals("192.168.1.192", first.getString("ip"))
        assertEquals("aa:bb:cc:dd:ee:ff", first.getString("mac"))
        assertEquals("ACTIVE", first.getString("presence"))
        assertEquals("5GHZ", first.getJSONArray("bands").getString(0))
        assertEquals(-60, first.getJSONObject("signal").getInt("rssiDbm"))
    }

    @Test
    fun `json output keeps a null signal as json null`() {
        val root = JSONObject(JsonRenderer.render(listOf(device(signal = null))))

        assertTrue(root.getJSONArray("devices").getJSONObject(0).isNull("signal"))
    }

    @Test
    fun `json output uses the documented key order`() {
        val output = JsonRenderer.render(listOf(device()))

        val expectedOrder = listOf(
            "\"name\"", "\"ip\"", "\"mac\"", "\"bands\"", "\"signal\"",
            "\"interface\"", "\"leaseExpiry\"", "\"ipv6\"", "\"presence\""
        )
        val positions = expectedOrder.map { output.indexOf(it) }
        assertEquals(expectedOrder, expectedOrder.sortedBy { positions[expectedOrder.indexOf(it)] })
        assertTrue(
            "the device keys must appear in the documented order",
            positions.all { it >= 0 } && positions == positions.sorted()
        )
    }
}
