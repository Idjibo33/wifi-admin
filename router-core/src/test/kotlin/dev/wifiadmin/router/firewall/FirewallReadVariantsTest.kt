package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.FakeRouter
import dev.wifiadmin.router.Fixtures
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.ZltRouterClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The alternative read variants.
 *
 * `GET 23` and `GET 30` returned no payload on the target firmware. Two
 * other shapes are documented by the router's own web UI and are worth
 * trying, **one at a time**:
 *
 * - `getfun: true`, which the analysis attributes to `GET 23`;
 * - `GET 28` (`DEFAULT_FILTER`), the older single-entry variant of the
 *   default policy.
 *
 * Both stay **opt-in**: the default path must keep sending exactly what it
 * sent before, so a result can be attributed to a single change.
 */
class FirewallReadVariantsTest {

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private lateinit var client: ZltRouterClient

    @Before
    fun setUp() {
        router = FakeRouter()
        client = ZltRouterClient(settings, transport = router)
        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) { JSONObject().put("success", true).put("sessionId", "SESSION") }
    }

    @Test
    fun `the default read sends no getfun flag`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }

        FirewallMacFilterClient(client).readRules()

        assertFalse(
            "the default request must stay exactly as it was",
            router.sent(23, "GET").single().json.has("getfun"),
        )
    }

    @Test
    fun `the getfun variant carries the documented flag`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }

        FirewallMacFilterClient(client).readRules(getfun = true)

        val request = router.sent(23, "GET").single().json
        assertEquals(true, request.optBoolean("getfun"))
        assertEquals("GET", request.getString("method"))
    }

    @Test
    fun `the default policy variant reads command twenty eight`() {
        router.onJson(28, "GET") {
            JSONObject().put("success", true).put("cmd", 28).put(
                "datas",
                org.json.JSONArray().put(
                    JSONObject().put("enableRule", true)
                        .put("acceptAll", "true")
                        .put("ippro", "IPV4")
                )
            )
        }

        val outcome = FirewallMacFilterClient(client).readDefaults(command = 28)

        assertEquals(1, router.sent(28, "GET").size)
        assertTrue(router.sent(30, "GET").isEmpty())
        val data = outcome as FirewallReadOutcome.Data
        assertEquals(true, data.value.ipv4?.acceptAll)
    }

    @Test
    fun `the default policy still reads command thirty by default`() {
        router.onJson(30, "GET") { Fixtures.firewallDefaultsReply() }

        FirewallMacFilterClient(client).readDefaults()

        assertEquals(1, router.sent(30, "GET").size)
        assertTrue(router.sent(28, "GET").isEmpty())
    }

    /**
     * No variant may ever turn into a write.
     *
     * The guarantee is structural — the read gate fixes the method to `GET`
     * — but it is asserted for every command of the firewall block.
     */
    @Test
    fun `no firewall command is ever written`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }
        router.onJson(28, "GET") {
            JSONObject().put("success", true).put("cmd", 28).put(
                "datas",
                org.json.JSONArray().put(
                    JSONObject().put("enableRule", true)
                        .put("acceptAll", "true").put("ippro", "IPV4")
                )
            )
        }
        router.onJson(30, "GET") { Fixtures.firewallDefaultsReply() }

        val firewall = FirewallMacFilterClient(client)
        firewall.readRules(getfun = true)
        firewall.readDefaults(command = 28)
        firewall.readDefaults(command = 30)

        assertTrue("cmd 23 must never be written", router.sent(23, "POST").isEmpty())
        assertTrue("cmd 28 must never be written", router.sent(28, "POST").isEmpty())
        assertTrue("cmd 30 must never be written", router.sent(30, "POST").isEmpty())
        assertTrue("cmd 20 must never be sent", router.sent(20).isEmpty())
        assertTrue("the write token must never be requested", router.sent(233).isEmpty())
    }
}
