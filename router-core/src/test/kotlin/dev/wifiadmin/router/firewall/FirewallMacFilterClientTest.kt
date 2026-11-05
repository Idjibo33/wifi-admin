package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.FakeRouter
import dev.wifiadmin.router.Fixtures
import dev.wifiadmin.router.RouterAuthenticationException
import dev.wifiadmin.router.RouterFeatureUnavailableException
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.RouterTransport
import dev.wifiadmin.router.ZltRouterClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for the **read-only** firewall MAC filter client.
 *
 * Every test runs behind [ReadOnlyTripwire], which fails the moment a write
 * is attempted, before the request reaches the fake router. The fake router
 * also has trap handlers on the write paths, so a regression cannot pass
 * silently.
 */
class FirewallMacFilterClientTest {

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private lateinit var tripwire: ReadOnlyTripwire
    private lateinit var client: ZltRouterClient
    private lateinit var firewall: FirewallMacFilterClient

    @Before
    fun setUp() {
        router = FakeRouter()
        tripwire = ReadOnlyTripwire(router)
        client = ZltRouterClient(settings, transport = tripwire)
        firewall = FirewallMacFilterClient(client)

        // Belt and braces: the tripwire should stop these before they arrive,
        // but a regression must fail with an explicit message.
        router.onJson(20) { throw AssertionError("cmd 20 (apply) must never be sent") }
        router.onJson(23, "POST") { throw AssertionError("cmd 23 must never be written") }
        router.onJson(28, "POST") { throw AssertionError("cmd 28 must never be written") }
        router.onJson(30, "POST") { throw AssertionError("cmd 30 must never be written") }
        router.onJson(233) { throw AssertionError("the write token must never be requested") }

        router.onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
        router.onJson(100) {
            JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION")
        }
    }

    // ---- Request shape ----

    @Test
    fun `the rules read request has the documented envelope`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }

        firewall.readRules()

        val request = router.sent(23, "GET").single().json
        assertEquals(23, request.getInt("cmd"))
        assertEquals("GET", request.getString("method"))
        assertEquals("ROUTER-SESSION", request.getString("sessionId"))
        assertFalse("a read must not carry a write token", request.has("token"))
        assertFalse("a read must not carry a rule list", request.has("datas"))
        assertFalse("command 23 is global, it takes no subcmd", request.has("subcmd"))
        assertReadOnly()
    }

    @Test
    fun `the defaults read uses command thirty with a get`() {
        router.onJson(30, "GET") { Fixtures.firewallDefaultsReply() }

        firewall.readDefaults()

        val request = router.sent(30, "GET").single().json
        assertEquals(30, request.getInt("cmd"))
        assertEquals("GET", request.getString("method"))
        assertFalse(request.has("token"))
        assertFalse(request.has("datas"))
        assertReadOnly()
    }

    /**
     * The router's own web UI always sends `language`
     * (`main.js`: `if (!json.language) json.language = Page.language`).
     *
     * Sending it removes one protocol difference with the router's client.
     */
    @Test
    fun `every request carries the language the router expects`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }
        router.onJson(30, "GET") { Fixtures.firewallDefaultsReply() }

        firewall.readState()

        router.requests.forEach { request ->
            assertTrue(
                "cmd ${request.cmd} must carry a language",
                request.json.has("language"),
            )
        }
        assertReadOnly()
    }

    @Test
    fun `reading the state sends one get for the rules and one for the defaults`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }
        router.onJson(30, "GET") { Fixtures.firewallDefaultsReply() }

        firewall.readState()

        assertEquals(
            listOf(232 to "GET", 100 to "POST", 23 to "GET", 30 to "GET"),
            router.requests.map { it.cmd to it.method },
        )
        assertReadOnly()
    }

    // ---- The observed real-world reply ----

    /**
     * The reply the real firmware sent on 30/10/2026: `success` is true,
     * `message` is present and **empty**, `datas` is absent.
     *
     * It must be reported as an **invalid response**, never as an empty rule
     * list: "no rule" and "no answer" mean the opposite in white-list mode.
     */
    @Test
    fun `the reply observed on the real router is an invalid response, not an empty list`() {
        router.onJson(23, "GET") {
            JSONObject().put("cmd", 23).put("message", "").put("success", true)
        }

        val outcome = firewall.readRules()

        val invalid = outcome as FirewallReadOutcome.Invalid
        assertEquals(FirewallInvalidReason.DATAS_MISSING, invalid.reason)
        assertEquals(FirewallReadState.RESPONSE_INVALID, outcome.state)
        assertReadOnly()
    }

    @Test
    fun `the empty message of the real reply is reported as empty, not absent`() {
        router.onJson(23, "GET") {
            JSONObject().put("cmd", 23).put("message", "").put("success", true)
        }

        val observation = (firewall.readRules() as FirewallReadOutcome.Invalid).observation

        assertNotNull(observation)
        assertTrue(observation!!.messagePresent)
        assertEquals(
            dev.wifiadmin.router.RouterMessageCategory.EMPTY,
            observation.messageCategory,
        )
    }

    /** A confirmed empty collection, with the same empty message. */
    @Test
    fun `an empty collection with an empty message is a confirmed empty list`() {
        router.onJson(23, "GET") {
            JSONObject().put("cmd", 23).put("message", "").put("success", true)
                .put("datas", org.json.JSONArray())
        }

        val outcome = firewall.readRules()

        val data = outcome as FirewallReadOutcome.Data
        assertEquals(FirewallReadState.DATA_RECEIVED, outcome.state)
        assertTrue(data.value.rules.isEmpty())
        assertTrue(data.value.anomalies.isEmpty())
    }

    @Test
    fun `a null datas payload is not an empty list`() {
        router.onJson(23, "GET") {
            JSONObject().put("cmd", 23).put("success", true).put("datas", JSONObject.NULL)
        }

        val outcome = firewall.readRules()

        assertEquals(
            FirewallInvalidReason.DATAS_NULL,
            (outcome as FirewallReadOutcome.Invalid).reason,
        )
    }

    @Test
    fun `an empty datas array is a confirmed empty list`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }

        val outcome = firewall.readRules()

        val data = outcome as FirewallReadOutcome.Data
        assertEquals(FirewallReadState.DATA_RECEIVED, outcome.state)
        assertTrue(data.value.rules.isEmpty())
    }

    @Test
    fun `the observation records what the reply actually carried`() {
        router.onJson(23, "GET") {
            JSONObject().put("cmd", 23).put("message", "success").put("success", true)
        }

        val observation = (firewall.readRules() as FirewallReadOutcome.Invalid).observation

        assertNotNull(observation)
        assertEquals(true, observation!!.success)
        assertTrue(observation.successPresent)
        assertFalse(observation.datasPresent)
        assertNull(observation.datasType)
        assertFalse(observation.hasErrorField)
        assertEquals(listOf("cmd", "message", "success"), observation.topLevelKeys)
        assertEquals(23, observation.echoedCmd)
    }

    @Test
    fun `the observation never keeps the raw message`() {
        router.onJson(23, "GET") {
            JSONObject()
                .put("cmd", 23)
                .put("message", "a secret text that must never be shown")
                .put("success", true)
        }

        val observation = (firewall.readRules() as FirewallReadOutcome.Invalid).observation

        assertNotNull(observation)
        assertFalse(
            "the raw message must not be kept",
            observation.toString().contains("secret text"),
        )
        assertEquals("<message>", observation!!.messageDisplayable)
    }

    // ---- Parsing is delegated, but the wiring is checked here ----

    @Test
    fun `a rule list is returned with the router order preserved`() {
        router.onJson(23, "GET") {
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01"),
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:02"),
                )
            )
        }

        val rules = (firewall.readRules() as FirewallReadOutcome.Data).value.rules

        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), rules.map { it.mac })
        assertReadOnly()
    }

    @Test
    fun `the default policy is read for both protocols`() {
        router.onJson(30, "GET") {
            Fixtures.firewallDefaultsReply(ipv4AcceptAll = "true", ipv6AcceptAll = "false")
        }

        val defaults = (firewall.readDefaults() as FirewallReadOutcome.Data).value

        assertEquals(true, defaults.ipv4?.acceptAll)
        assertEquals(false, defaults.ipv6?.acceptAll)
        assertReadOnly()
    }

    @Test
    fun `one failing read does not cancel the other`() {
        router.onJson(23, "GET") { Fixtures.firewallRulesReply(emptyList()) }
        router.onJson(30, "GET") {
            JSONObject().put("cmd", 30).put("message", "success").put("success", true)
        }

        val state = firewall.readState()

        assertTrue(state.rules is FirewallReadOutcome.Data)
        assertTrue(state.defaults is FirewallReadOutcome.Invalid)
        assertReadOnly()
    }

    // ---- Errors ----

    @Test
    fun `a limited access reply is reported as an unavailable feature`() {
        router.onJson(23, "GET") {
            JSONObject().put("success", false).put("message", "LIMITED_ACCESS")
        }

        try {
            firewall.readRules()
            fail("LIMITED_ACCESS must not be reported as a rule list")
        } catch (e: RouterFeatureUnavailableException) {
            assertEquals("firewall", e.feature)
            assertTrue(e.routerMessage.contains("LIMITED_ACCESS"))
        }
        assertReadOnly()
    }

    /**
     * A refusal is a refusal even when the router also claims success.
     *
     * This case used to slip through: the feature check only ran on the
     * failure branch, so a `LIMITED_ACCESS` reply with `success: true` was
     * presented as an empty rule list.
     */
    @Test
    fun `a limited access reply is a refusal even when success is true`() {
        router.onJson(23, "GET") {
            JSONObject().put("success", true).put("message", "LIMITED_ACCESS")
        }

        try {
            firewall.readRules()
            fail("LIMITED_ACCESS must be a refusal whatever success says")
        } catch (e: RouterFeatureUnavailableException) {
            assertEquals("firewall", e.feature)
        }
        assertReadOnly()
    }

    @Test
    fun `a failed reply raises a protocol exception`() {
        router.onJson(23, "GET") {
            JSONObject().put("success", false).put("message", "SOMETHING_ELSE")
        }

        try {
            firewall.readRules()
            fail("a failed reply must not be reported as a rule list")
        } catch (e: RouterProtocolException) {
            assertTrue(e !is RouterFeatureUnavailableException)
        }
        assertReadOnly()
    }

    @Test
    fun `an unknown router message is never reproduced in the exception`() {
        router.onJson(23, "GET") {
            JSONObject()
                .put("success", false)
                .put("message", "user=admin password=hunter2")
        }

        try {
            firewall.readRules()
            fail("a failed reply must not be reported as a rule list")
        } catch (e: RouterProtocolException) {
            assertFalse("the raw message must not leak", e.message!!.contains("hunter2"))
            assertFalse(e.message!!.contains("admin"))
        }
        assertReadOnly()
    }

    @Test
    fun `malformed json raises a protocol exception`() {
        router.onRaw(23, "GET") { "not json at all" }

        try {
            firewall.readRules()
            fail("malformed JSON must not be reported as a rule list")
        } catch (e: RouterProtocolException) {
            // Expected.
        }
        assertReadOnly()
    }

    @Test
    fun `an expired session triggers one re-login and one repeated read`() {
        var calls = 0
        router.onJson(23, "GET") {
            if (calls++ == 0) {
                JSONObject().put("success", false).put("message", "NO_AUTH")
            } else {
                Fixtures.firewallRulesReply(listOf(Fixtures.firewallRule("AA:BB:CC:DD:EE:01")))
            }
        }

        val rules = (firewall.readRules() as FirewallReadOutcome.Data).value.rules

        assertEquals(1, rules.size)
        assertEquals(2, router.sent(23, "GET").size)
        assertEquals(2, router.sent(100, "POST").size)
        assertReadOnly()
    }

    @Test
    fun `a session that stays expired raises an authentication exception`() {
        router.onJson(23, "GET") {
            JSONObject().put("success", false).put("message", "LOGIN_TIMEOUT")
        }

        try {
            firewall.readRules()
            fail("a session that cannot be renewed must not be reported as a rule list")
        } catch (e: RouterAuthenticationException) {
            // Expected.
        }
        assertReadOnly()
    }

    @Test
    fun `a network failure is retried once then reported`() {
        router.onNetworkFailure(23, "GET")

        try {
            firewall.readRules()
            fail("an unreachable router must not be reported as a rule list")
        } catch (e: RouterNetworkException) {
            // Expected.
        }
        assertEquals(2, router.sent(23, "GET").size)
        assertReadOnly()
    }

    // ---- Read-only boundary ----

    @Test
    fun `the read gate rejects the apply command`() {
        try {
            client.readOnly(20)
            fail("cmd 20 must be rejected by the read gate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("20"))
        }
        assertTrue(router.sent(20).isEmpty())
        assertReadOnly()
    }

    @Test
    fun `the read gate rejects the write token command`() {
        try {
            client.readOnly(233)
            fail("cmd 233 must be rejected by the read gate")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        assertTrue(router.sent(233).isEmpty())
        assertReadOnly()
    }

    /**
     * Exhaustive boundary: every command number outside the allowlist is
     * rejected, and no request is built for any of them.
     *
     * The scan is exhaustive on purpose: a hand-written list of forbidden
     * commands goes stale as soon as a new one is added.
     */
    @Test
    fun `every command outside the read allowlist is rejected without any request`() {
        val allowed = setOf(23, 28, 30, 223, 224, 225)

        for (cmd in 0..400) {
            if (cmd in allowed) continue
            try {
                client.readOnly(cmd)
                fail("cmd $cmd must be rejected by the read gate")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }

        assertTrue("no request may be built for a rejected command", router.requests.isEmpty())
    }

    /**
     * Meta-test: if a write is injected anywhere in the transport chain, the
     * tripwire must fail **before** the request is sent. This proves the
     * tripwire is not silently passing everything.
     */
    @Test
    fun `the read-only guard itself fails when a write is injected`() {
        try {
            tripwire.post(
                url = "http://192.168.1.1/cgi-bin/http.cgi",
                body = JSONObject()
                    .put("cmd", 23)
                    .put("method", "POST")
                    .put("sessionId", "ROUTER-SESSION")
                    .put("datas", Fixtures.firewallRule("AA:BB:CC:DD:EE:01"))
                    .toString(),
                timeoutSeconds = 4,
            )
            fail("the tripwire must reject a POST to command 23")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains("23"))
        }
        assertTrue("the write must not reach the fake router", router.sent(23, "POST").isEmpty())
    }

    /** No request outside the read-only allowlist may ever be emitted. */
    private fun assertReadOnly() {
        assertTrue("forbidden writes: ${tripwire.forbidden}", tripwire.forbidden.isEmpty())
        val allowed = router.requests.all { request ->
            when {
                request.cmd == 232 -> request.method == "GET"
                request.cmd == 100 -> request.method == "POST"
                request.cmd == 23 || request.cmd == 28 || request.cmd == 30 ->
                    request.method == "GET"
                else -> false
            }
        }
        assertTrue(
            "unexpected commands: ${router.requests.map { "${it.method} ${it.cmd}" }}",
            allowed,
        )
    }
}

/**
 * A transport that fails on any write **before** delegating.
 *
 * `AssertionError` is used on purpose: `ZltRouterClient` converts
 * `RuntimeException` into a network error, but never an `Error`, so a
 * regression fails the test instead of being reported as an unreachable
 * router.
 */
private class ReadOnlyTripwire(
    private val delegate: RouterTransport,
) : RouterTransport {

    val forbidden = mutableListOf<String>()

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        val request = JSONObject(body)
        val cmd = request.optInt("cmd", -1)
        val method = request.optString("method")

        val isWrite = cmd == 20 ||
            cmd == 233 ||
            (cmd == 23 && method == "POST") ||
            (cmd == 30 && method == "POST") ||
            (method == "POST" && cmd != 100)

        if (isWrite) {
            forbidden += "$method $cmd"
            throw AssertionError("forbidden write attempt: $method $cmd")
        }
        return delegate.post(url, body, timeoutSeconds)
    }
}
