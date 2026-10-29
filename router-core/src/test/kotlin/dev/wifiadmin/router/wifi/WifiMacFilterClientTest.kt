package dev.wifiadmin.router.wifi

import dev.wifiadmin.router.FakeRouter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WifiMacFilterClientTest {

    private val settings = object : dev.wifiadmin.router.RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private lateinit var client: WifiMacFilterClient

    /** The filter state the fake router reports for a band. */
    private val band24 = mutableMapOf<String, Any?>(
        "macfilter" to "close",
        "maclist" to emptyList<Map<String, String>>()
    )
    private val band5 = mutableMapOf<String, Any?>(
        "macfilter" to "close",
        "maclist" to emptyList<Map<String, String>>()
    )

    @Before
    fun setUp() {
        router = FakeRouter()
        client = WifiMacFilterClient(settings, transport = router)
        stubAuth()
        stubBands()
        stubWps(enabled = false)
        stubWifiReady()
    }

    // ---- Fixtures ----------------------------------------------------

    private fun stubAuth() {
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "SESSION") }
        router.onJson(233) { JSONObject().put("success", true).put("token", "WRITE-TOKEN") }
    }

    private fun stubWps(enabled: Boolean) {
        router.onSubcmd(132, "GET", "0") {
            JSONObject()
                .put("success", true)
                .put("wifiOpen", "1")
                .put("wlan2g_wps_switch", if (enabled) "1" else "0")
        }
        router.onSubcmd(132, "GET", "1") {
            JSONObject()
                .put("success", true)
                .put("wifiOpen", "1")
                .put("wlan5g_wps_switch", if (enabled) "1" else "0")
        }
    }

    private fun stubWifiReady(status: String = "1") {
        router.onJson(417) { JSONObject().put("success", true).put("wifiStatus", status) }
    }

    private fun stubBands() {
        router.onSubcmd(278, "GET", "1") { bandReply(band24) }
        router.onSubcmd(278, "GET", "0") { bandReply(band5) }
        router.onSubcmd(278, "POST", "1") {
            applyBand(band24, it)
        }
        router.onSubcmd(278, "POST", "0") {
            applyBand(band5, it)
        }
    }

    private fun bandReply(state: Map<String, Any?>): JSONObject {
        val list = JSONArray()
        @Suppress("UNCHECKED_CAST")
        (state["maclist"] as List<Map<String, String>>).forEach { entry ->
            list.put(
                JSONObject()
                    .put("mac", entry["mac"])
                    .put("remarks", entry["remarks"] ?: "")
            )
        }
        return JSONObject()
            .put("success", true)
            .put(
                "datas",
                JSONObject()
                    .put("macfilter", state["macfilter"])
                    .put("maclist", list)
            )
    }

    /** Applies a write to the fake band state, like the router would. */
    private fun applyBand(state: MutableMap<String, Any?>, request: JSONObject): JSONObject {
        val datas = request.getJSONObject("datas")
        state["macfilter"] = datas.getString("macfilter")
        val list = datas.getJSONArray("maclist")
        val entries = mutableListOf<Map<String, String>>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            entries += mapOf(
                "mac" to item.getString("mac"),
                "remarks" to item.optString("remarks", "")
            )
        }
        state["maclist"] = entries
        return JSONObject().put("success", true)
    }

    private fun entry(mac: String, remark: String = "") = mapOf("mac" to mac, "remarks" to remark)

    // ---- W1 : parsing the reply ---------------------------------------

    @Test
    fun `read parses the macfilter mode and the maclist`() {
        band24["macfilter"] = "deny"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01", "phone"))

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertEquals(WifiMacMode.DENY, state.mode)
        assertEquals(1, state.entries.size)
        assertEquals("AA:BB:CC:DD:EE:01", state.entries[0].mac)
        assertEquals("phone", state.entries[0].remark)
    }

    @Test
    fun `a missing datas key yields the close mode and an empty list`() {
        router.onSubcmd(278, "GET", "1") { JSONObject().put("success", true) }

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertEquals(WifiMacMode.CLOSE, state.mode)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `mac addresses are normalised to upper case colon form`() {
        band24["maclist"] = listOf(entry("aabbccddeeff"))

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertEquals("AA:BB:CC:DD:EE:FF", state.entries[0].mac)
    }

    @Test
    fun `an unknown mode raises a compatibility exception`() {
        router.onSubcmd(278, "GET", "1") {
            JSONObject().put("success", true).put(
                "datas",
                JSONObject().put("macfilter", "unknown-mode").put("maclist", JSONArray())
            )
        }

        try {
            client.read(WifiBandFilter.BAND_2_4_GHZ)
            fail("expected a compatibility exception")
        } catch (expected: WifiMacCompatibilityException.UnknownMode) {
            assertEquals("unknown-mode", expected.value)
        }
    }

    // ---- W3 : subcmd per band ----------------------------------------

    @Test
    fun `the read uses the right subcmd for each band`() {
        client.read(WifiBandFilter.BAND_2_4_GHZ)
        client.read(WifiBandFilter.BAND_5_GHZ)

        val subcmds = router.sent(278, "GET").map { it.json.optString("subcmd") }
        assertEquals(listOf("1", "0"), subcmds)
    }

    @Test
    fun `the two bands are read independently`() {
        band24["macfilter"] = "deny"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01"))
        band5["macfilter"] = "allow"
        band5["maclist"] = listOf(entry("AA:BB:CC:DD:EE:02"))

        val state24 = client.read(WifiBandFilter.BAND_2_4_GHZ)
        val state5 = client.read(WifiBandFilter.BAND_5_GHZ)

        assertEquals(WifiMacMode.DENY, state24.mode)
        assertEquals("AA:BB:CC:DD:EE:01", state24.entries[0].mac)
        assertEquals(WifiMacMode.ALLOW, state5.mode)
        assertEquals("AA:BB:CC:DD:EE:02", state5.entries[0].mac)
    }

    // ---- W4 : the read never writes -----------------------------------

    @Test
    fun `the read never sends a write command`() {
        client.read(WifiBandFilter.BAND_2_4_GHZ)
        client.read(WifiBandFilter.BAND_5_GHZ)

        assertTrue(router.sent(278, "POST").isEmpty())
        val allowed = setOf(232, 100, 233, 278)
        assertTrue(router.requests.all { it.cmd in allowed })
    }

    // ---- W6 / W7 : the write payload ----------------------------------

    @Test
    fun `the write posts the datas object with the mode and the list`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)
        val target = current.copy(
            mode = WifiMacMode.DENY,
            entries = listOf(WifiMacEntry("AA:BB:CC:DD:EE:01", "phone"))
        )

        client.write(current, target)

        val write = router.sent(278, "POST").single().json
        val datas = write.getJSONObject("datas")
        assertEquals("deny", datas.getString("macfilter"))
        assertEquals(1, datas.getJSONArray("maclist").length())
        assertEquals("AA:BB:CC:DD:EE:01", datas.getJSONArray("maclist").getJSONObject(0).getString("mac"))
    }

    @Test
    fun `the write carries a fresh token`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)
        client.write(current, current.copy(mode = WifiMacMode.DENY))

        val write = router.sent(278, "POST").single().json
        assertEquals("WRITE-TOKEN", write.getString("token"))
        assertEquals(1, router.sent(233, "GET").size)
    }

    @Test
    fun `the write targets only the band it was given`() {
        val current = client.read(WifiBandFilter.BAND_5_GHZ)
        client.write(current, current.copy(mode = WifiMacMode.DENY))

        val write = router.sent(278, "POST").single().json
        assertEquals("0", write.getString("subcmd"))
    }

    // ---- W8 : the lock-out guard --------------------------------------

    @Test
    fun `writing allow with an empty list is refused`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(current, current.copy(mode = WifiMacMode.ALLOW))
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.WouldLockOutEveryDevice) {
            assertEquals(WifiBandFilter.BAND_2_4_GHZ, expected.band)
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `restoring a state that locks every device out is refused`() {
        val saved = WifiMacFilterState(WifiBandFilter.BAND_2_4_GHZ, WifiMacMode.ALLOW, emptyList())

        try {
            client.restore(saved)
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.WouldLockOutEveryDevice) {
            assertEquals(WifiBandFilter.BAND_2_4_GHZ, expected.band)
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    // ---- W9 : the wifi-ready precondition ----------------------------

    @Test
    fun `writing while the wifi is not ready is refused`() {
        stubWifiReady(status = "0")
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(current, current.copy(mode = WifiMacMode.DENY))
            fail("expected a compatibility exception")
        } catch (expected: WifiMacCompatibilityException.CommandRefused) {
            assertTrue(expected.message.orEmpty().contains("not initialised"))
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    // ---- W10 : the WPS guard ------------------------------------------

    @Test
    fun `writing while wps is active is refused`() {
        stubWps(enabled = true)
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(current, current.copy(mode = WifiMacMode.DENY))
            fail("expected a compatibility exception")
        } catch (expected: WifiMacCompatibilityException.CommandRefused) {
            assertTrue(expected.message.orEmpty().contains("WPS"))
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `checkWritable reports the wps state without writing anything`() {
        val result = client.checkWritable(WifiBandFilter.BAND_2_4_GHZ)

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(false, result.getOrNull()!!.enabled)
        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `the wps subcmd uses the opposite convention of the mac filter`() {
        client.checkWritable(WifiBandFilter.BAND_2_4_GHZ)
        client.checkWritable(WifiBandFilter.BAND_5_GHZ)

        val subcmds = router.sent(132, "GET").map { it.json.optString("subcmd") }
        assertEquals(listOf("0", "1"), subcmds)
    }

    // ---- W11 : the stale-state guard ----------------------------------

    @Test
    fun `a stale expected state refuses the write`() {
        val stale = client.read(WifiBandFilter.BAND_2_4_GHZ)
        // Somebody changes the filter in the web UI in the meantime.
        band24["macfilter"] = "allow"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:09"))

        try {
            client.write(stale, stale.copy(mode = WifiMacMode.DENY))
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.StaleState) {
            assertEquals(WifiBandFilter.BAND_2_4_GHZ, expected.band)
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    // ---- Preservation of the existing rules ---------------------------

    @Test
    fun `writing preserves the rules that are kept in the new state`() {
        band24["macfilter"] = "deny"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01", "one"), entry("AA:BB:CC:DD:EE:02", "two"))

        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)
        val target = current.withEntry(WifiMacEntry("AA:BB:CC:DD:EE:03", "three"))

        client.write(current, target)

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)
        assertEquals(3, state.entries.size)
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"),
            state.entries.map { it.mac }
        )
    }

    @Test
    fun `removing an entry from the list removes it on the router`() {
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01"), entry("AA:BB:CC:DD:EE:02"))

        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)
        val target = current.withoutMac("AA:BB:CC:DD:EE:01")

        client.write(current, target)

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)
        assertEquals(1, state.entries.size)
        assertEquals("AA:BB:CC:DD:EE:02", state.entries[0].mac)
    }

    // ---- W12 : the verification read ----------------------------------

    @Test
    fun `the state is read back after the write`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)
        client.write(current, current.copy(mode = WifiMacMode.DENY))

        val readsBefore = router.sent(278, "GET").size
        assertTrue("a verification read must follow the write", readsBefore >= 2)
    }

    @Test
    fun `a write that the router does not apply raises an exception`() {
        // The router accepts the write but does not change anything.
        router.onSubcmd(278, "POST", "1") { JSONObject().put("success", true) }
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(current, current.copy(mode = WifiMacMode.DENY))
            fail("expected a compatibility exception")
        } catch (expected: WifiMacCompatibilityException.CommandRefused) {
            assertTrue(expected.message.orEmpty().contains("not confirmed"))
        }
    }

    // ---- Restore -------------------------------------------------------

    @Test
    fun `restore puts back a previously saved state`() {
        band24["macfilter"] = "deny"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01", "saved"))
        val saved = client.read(WifiBandFilter.BAND_2_4_GHZ)

        // The filter drifts.
        band24["macfilter"] = "close"
        band24["maclist"] = emptyList<Map<String, String>>()

        client.restore(saved)

        val after = client.read(WifiBandFilter.BAND_2_4_GHZ)
        assertEquals(WifiMacMode.DENY, after.mode)
        assertEquals(1, after.entries.size)
        assertEquals("AA:BB:CC:DD:EE:01", after.entries[0].mac)
        assertEquals("saved", after.entries[0].remark)
    }

    @Test
    fun `restore ignores the stale state check`() {
        val saved = WifiMacFilterState(
            WifiBandFilter.BAND_2_4_GHZ,
            WifiMacMode.DENY,
            listOf(WifiMacEntry("AA:BB:CC:DD:EE:01"))
        )

        client.restore(saved)

        val after = client.read(WifiBandFilter.BAND_2_4_GHZ)
        assertEquals(WifiMacMode.DENY, after.mode)
    }

    @Test
    fun `disable switches the mode to close and keeps the list`() {
        band24["macfilter"] = "deny"
        band24["maclist"] = listOf(entry("AA:BB:CC:DD:EE:01", "phone"))

        client.disable(WifiBandFilter.BAND_2_4_GHZ)

        val after = client.read(WifiBandFilter.BAND_2_4_GHZ)
        assertEquals(WifiMacMode.CLOSE, after.mode)
        assertEquals(1, after.entries.size)
        assertEquals("AA:BB:CC:DD:EE:01", after.entries[0].mac)
    }

    // ---- Error categories ---------------------------------------------

    @Test
    fun `a network failure raises a communication exception`() {
        router.onNetworkFailure(278, "GET")

        try {
            client.read(WifiBandFilter.BAND_2_4_GHZ)
            fail("expected a communication exception")
        } catch (expected: WifiMacCommunicationException) {
            // expected
        }
    }

    @Test
    fun `a refused authentication raises an authentication exception`() {
        router.onJson(100) { JSONObject().put("success", false).put("message", "passwd error") }

        try {
            client.read(WifiBandFilter.BAND_2_4_GHZ)
            fail("expected an authentication exception")
        } catch (expected: WifiMacAuthenticationException) {
            // expected
        }
    }

    @Test
    fun `a malformed reply raises a compatibility exception`() {
        router.onRaw(278, "GET") { "not json at all" }

        try {
            client.read(WifiBandFilter.BAND_2_4_GHZ)
            fail("expected a compatibility exception")
        } catch (expected: WifiMacCompatibilityException) {
            // expected
        }
    }

    @Test
    fun `an invalid mac is refused before anything is sent`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(current, current.copy(entries = listOf(WifiMacEntry("ZZ:ZZ:ZZ:ZZ:ZZ:ZZ"))))
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.InvalidMac) {
            // expected
        }

        assertTrue(router.sent(278, "POST").isEmpty())
    }

    @Test
    fun `a multicast mac is refused`() {
        try {
            WifiMacFilterState.validateMac("01:00:5E:00:00:00")
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.ForbiddenMac) {
            // expected
        }
    }

    @Test
    fun `a duplicate mac is refused`() {
        val current = client.read(WifiBandFilter.BAND_2_4_GHZ)

        try {
            client.write(
                current,
                current.copy(
                    entries = listOf(
                        WifiMacEntry("AA:BB:CC:DD:EE:01"),
                        WifiMacEntry("aa:bb:cc:dd:ee:01")
                    )
                )
            )
            fail("expected a validation exception")
        } catch (expected: WifiMacValidationException.DuplicateMac) {
            // expected
        }
    }
}
