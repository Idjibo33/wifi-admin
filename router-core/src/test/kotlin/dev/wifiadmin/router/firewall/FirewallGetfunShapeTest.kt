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
 * The shape of the `getfun` flag.
 *
 * This firmware encodes its flags as **strings**: `acceptAll: "true"`,
 * `isAutoUpgrade: "0"`, `subcmd: '1'`. A JSON boolean has never been seen by
 * it.
 *
 * The router's own web UI is documented to send `getfun: true` — but the
 * transcript is a JavaScript literal, and JavaScript booleans become JSON
 * booleans. The firmware may only compare against the string form.
 */
class FirewallGetfunShapeTest {

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
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }
    }

    /**
     * The flag must be a string, like every other flag this firmware knows.
     */
    @Test
    fun `the getfun flag is sent as a string`() {
        FirewallMacFilterClient(client).readRules(getfun = true)

        val request = router.sent(23, "GET").single().json

        assertTrue("the flag must be present", request.has("getfun"))
        assertFalse(
            "a JSON boolean has never been seen by this firmware",
            request.opt("getfun") is Boolean,
        )
        assertEquals("true", request.optString("getfun"))
    }

    @Test
    fun `the default read sends no getfun flag`() {
        FirewallMacFilterClient(client).readRules()

        assertFalse(
            "the default request must stay exactly as it was",
            router.sent(23, "GET").single().json.has("getfun"),
        )
    }
}
