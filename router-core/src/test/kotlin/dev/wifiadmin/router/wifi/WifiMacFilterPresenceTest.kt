package dev.wifiadmin.router.wifi

import dev.wifiadmin.router.FakeRouter
import dev.wifiadmin.router.RouterSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Wi-Fi filter must not claim "the filter is disabled" when the router
 * simply delivered no payload.
 *
 * This is the same confusion the firewall diagnostic had: an absent
 * collection is not an empty collection.
 */
class WifiMacFilterPresenceTest {

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private lateinit var client: WifiMacFilterClient

    @Before
    fun setUp() {
        router = FakeRouter()
        client = WifiMacFilterClient(settings, transport = router)
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "SESSION") }
    }

    @Test
    fun `an empty mac list is a confirmed empty filter`() {
        router.onSubcmd(278, "GET", "1") {
            JSONObject().put("success", true).put(
                "datas",
                JSONObject().put("macfilter", "close").put("maclist", org.json.JSONArray()),
            )
        }

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertEquals(WifiMacMode.CLOSE, state.mode)
        assertTrue(state.entries.isEmpty())
    }

    /**
     * A reply without `datas` proves nothing about the filter.
     *
     * Reading it as "disabled" would tell the user the Wi-Fi filter is
     * inactive when the router merely answered nothing.
     */
    @Test
    fun `a missing datas does not claim the filter is disabled`() {
        router.onSubcmd(278, "GET", "1") {
            JSONObject().put("success", true).put("cmd", 278)
        }

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertNull(
            "no filter state may be invented when the payload is missing",
            state.mode,
        )
    }

    @Test
    fun `a null datas does not claim the filter is disabled`() {
        router.onSubcmd(278, "GET", "1") {
            JSONObject().put("success", true).put("datas", JSONObject.NULL)
        }

        val state = client.read(WifiBandFilter.BAND_2_4_GHZ)

        assertNull("no filter state may be invented when the payload is null", state.mode)
    }
}
