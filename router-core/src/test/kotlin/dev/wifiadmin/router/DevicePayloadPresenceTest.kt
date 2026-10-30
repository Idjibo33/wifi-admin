package dev.wifiadmin.router

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device lists must not claim "no device" when the router delivered no
 * payload at all.
 *
 * This is the same confusion the firewall diagnostic had: an absent
 * collection is not an empty collection.
 */
class DevicePayloadPresenceTest {

    @Test
    fun `a reply without the dhcp key reports an absent payload`() {
        val router = FakeRouter()
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "S") }
        router.onJson(223) { JSONObject().put("success", true).put("cmd", 223) }
        router.onJson(224) { JSONObject().put("success", true).put("cmd", 224) }
        router.onJson(225) { JSONObject().put("success", true).put("cmd", 225) }

        val snapshot = ZltRouterClient(settings, transport = router).fetchDevices()

        assertTrue(
            "the snapshot must say the payload was absent",
            snapshot.payloadAbsent,
        )
    }

    @Test
    fun `a reply with the payload reports a present payload`() {
        val router = FakeRouter()
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "S") }
        router.onJson(223) {
            JSONObject().put("success", true).put(
                "dhcp_list_info",
                org.json.JSONArray().put(
                    JSONObject().put("mac", "AA:BB:CC:DD:EE:01").put("ip", "192.168.1.10")
                )
            )
        }
        router.onJson(224) { JSONObject().put("success", true).put("wlan24g_wifi_info", org.json.JSONArray()) }
        router.onJson(225) { JSONObject().put("success", true).put("wlan5g_wifi_info", org.json.JSONArray()) }

        val snapshot = ZltRouterClient(settings, transport = router).fetchDevices()

        assertFalse(snapshot.payloadAbsent)
    }

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }
}
