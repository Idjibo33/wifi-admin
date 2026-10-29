package dev.wifiadmin.poc

import dev.wifiadmin.router.FakeRouter
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.wifi.WifiBandFilter
import dev.wifiadmin.router.wifi.WifiMacFilterClient
import dev.wifiadmin.router.wifi.WifiMacFilterState
import dev.wifiadmin.router.wifi.WifiMacMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the read-only boundary of the POC.
 *
 * The POC is allowed to read the Wi-Fi MAC filter (`GET 278`) but must never
 * write it: a `POST 278` would change the router's Wi-Fi access rules.
 */
class WifiFilterReadOnlyBoundaryTest {

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter

    /** The filter state the fake router holds, per band. */
    private val bandState = mutableMapOf(
        "1" to JSONObject().put("macfilter", "deny")
            .put("maclist", JSONArray().put(JSONObject().put("mac", "AA:BB:CC:DD:EE:01").put("remarks", "x"))),
        "0" to JSONObject().put("macfilter", "close").put("maclist", JSONArray())
    )

    @Before
    fun setUp() {
        router = FakeRouter()
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "SESSION") }
        router.onJson(233) { JSONObject().put("success", true).put("token", "WRITE-TOKEN") }
        router.onJson(417) { JSONObject().put("success", true).put("wifiStatus", "1") }
        router.onSubcmd(132, "GET", "0") {
            JSONObject().put("success", true).put("wifiOpen", "1").put("wlan2g_wps_switch", "0")
        }
        router.onSubcmd(132, "GET", "1") {
            JSONObject().put("success", true).put("wifiOpen", "1").put("wlan5g_wps_switch", "0")
        }
        router.onSubcmd(278, "GET", "1") {
            JSONObject().put("success", true).put("datas", bandState.getValue("1"))
        }
        router.onSubcmd(278, "GET", "0") {
            JSONObject().put("success", true).put("datas", bandState.getValue("0"))
        }
        // The write handler is registered so the restore path can be tested;
        // the read-only tests above assert that it is never reached.
        router.onSubcmd(278, "POST", "1") { request ->
            bandState["1"] = request.getJSONObject("datas")
            JSONObject().put("success", true)
        }
        router.onSubcmd(278, "POST", "0") { request ->
            bandState["0"] = request.getJSONObject("datas")
            JSONObject().put("success", true)
        }
    }

    @Test
    fun `reading the filter never sends a POST to command 278`() {
        val client = WifiMacFilterClient(settings, transport = router)

        client.read(WifiBandFilter.BAND_2_4_GHZ)
        client.read(WifiBandFilter.BAND_5_GHZ)

        assertTrue(
            "the POC must never write the Wi-Fi filter",
            router.sent(278, "POST").isEmpty()
        )
    }

    @Test
    fun `reading the filter with the structure never sends a POST to command 278`() {
        val client = WifiMacFilterClient(settings, transport = router)

        client.readWithStructure(WifiBandFilter.BAND_2_4_GHZ)
        client.readWithStructure(WifiBandFilter.BAND_5_GHZ)

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `the read commands are limited to the documented allowlist`() {
        val client = WifiMacFilterClient(settings, transport = router)
        client.read(WifiBandFilter.BAND_2_4_GHZ)

        val allowed = setOf(232, 100, 233, 278)
        assertTrue(
            "unexpected command sent: " + router.requests.map { it.cmd },
            router.requests.all { it.cmd in allowed }
        )
    }

    @Test
    fun `the write methods do exist but are not reachable from the read path`() {
        // The write methods are part of router-core's API and are exercised by
        // WifiMacFilterClientTest against the fake router. This test documents
        // that they are *not* used by the read path.
        val client = WifiMacFilterClient(settings, transport = router)
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertEquals(WifiMacMode.DENY, current.mode)
        assertEquals(1, current.entries.size)

        // No write happened.
        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `the restore path is available for the recovery procedure`() {
        // The restore method must exist and work against the fake router: it
        // is the safety net used when a device gets locked out.
        val client = WifiMacFilterClient(settings, transport = router)
        val saved = WifiMacFilterState(
            WifiBandFilter.BAND_2_4_GHZ,
            WifiMacMode.DENY,
            listOf(dev.wifiadmin.router.wifi.WifiMacEntry("AA:BB:CC:DD:EE:01"))
        )

        client.restore(saved)

        assertEquals(1, router.sent(278, "POST").size)
    }
}
