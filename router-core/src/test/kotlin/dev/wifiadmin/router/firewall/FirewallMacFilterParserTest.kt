package dev.wifiadmin.router.firewall

import dev.wifiadmin.router.Fixtures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirewallMacFilterParserTest {

    /**
     * Unwraps a read that delivered data.
     *
     * A test that expects data must fail loudly if the reply was invalid:
     * silently accepting an empty list would hide exactly the regression
     * this suite exists to catch.
     */
    private fun <T> dataOf(outcome: FirewallReadOutcome<T>): T = when (outcome) {
        is FirewallReadOutcome.Data -> outcome.value
        is FirewallReadOutcome.Invalid -> noData("no usable payload (${outcome.reason})")
        is FirewallReadOutcome.Unavailable -> noData("the feature was unavailable")
        is FirewallReadOutcome.Indeterminate -> noData("the read was indeterminate")
    }

    /** Fails the test; `Nothing` lets the compiler know the call never returns. */
    private fun noData(reason: String): Nothing {
        fail("the reply carried $reason")
        throw AssertionError("unreachable")
    }

    // ---- Rule list shape ----

    @Test
    fun `an empty rule list yields an empty state`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(Fixtures.firewallRulesReply(emptyList())))

        assertTrue(state.rules.isEmpty())
        assertTrue(state.anomalies.isEmpty())
        assertTrue(state.observedKeys.contains("datas"))
    }

    @Test
    fun `a missing datas key is an invalid response, not an empty list`() {
        val outcome = FirewallMacFilterParser.parseRules(JSONObject().put("success", true))

        val invalid = outcome as FirewallReadOutcome.Invalid
        assertEquals(FirewallInvalidReason.DATAS_MISSING, invalid.reason)
        assertEquals(FirewallReadState.RESPONSE_INVALID, outcome.state)
    }

    @Test
    fun `an explicit null datas key is not an empty list`() {
        val outcome = FirewallMacFilterParser.parseRules(
            JSONObject().put("success", true).put("datas", JSONObject.NULL)
        )

        val invalid = outcome as FirewallReadOutcome.Invalid
        assertEquals(
            "a null payload must not be presented as an empty list",
            FirewallInvalidReason.DATAS_NULL,
            invalid.reason,
        )
    }

    @Test
    fun `an empty datas array is a confirmed empty list`() {
        val outcome = FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(emptyList())
        )

        val data = outcome as FirewallReadOutcome.Data
        assertEquals(FirewallReadState.DATA_RECEIVED, outcome.state)
        assertTrue(data.value.rules.isEmpty())
        assertTrue(data.value.anomalies.isEmpty())
    }

    @Test
    fun `a datas value that is not an array is reported`() {
        val outcome = FirewallMacFilterParser.parseRules(
            JSONObject().put("success", true).put("datas", JSONObject().put("mac", "x"))
        )

        val invalid = outcome as FirewallReadOutcome.Invalid
        assertEquals(FirewallInvalidReason.DATAS_NOT_ARRAY, invalid.reason)
    }

    @Test
    fun `a single rule is parsed into all five fields`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule(
                        mac = "AA:BB:CC:DD:EE:FF",
                        ippro = "IPV4V6",
                        enableLink = false,
                        enableRule = true,
                        remark = "phone",
                    )
                )
            )
        ))

        val rule = state.rules.single()
        assertEquals("AA:BB:CC:DD:EE:FF", rule.mac)
        assertTrue(rule.macIsValid)
        assertEquals(true, rule.enabled)
        assertEquals(FirewallMacVerdict.BLACKLIST, rule.verdict)
        assertEquals(FirewallIpProtocol.IPV4V6, rule.ipProtocol)
        assertEquals("IPV4V6", rule.ipProtocolRaw)
        assertEquals("phone", rule.remark)
        assertEquals(
            setOf("enableRule", "enableLink", "ippro", "mac", "remark"),
            rule.observedFields,
        )
        assertTrue(state.anomalies.isEmpty())
    }

    @Test
    fun `several rules keep the router order because the index is the priority`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:03"),
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01"),
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:02"),
                )
            )
        ))

        assertEquals(
            listOf("AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            state.rules.map { it.mac },
        )
    }

    @Test
    fun `unknown fields in a rule are ignored but recorded`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01")
                        .put("priority", 7)
                        .put("unexpected", "value")
                )
            )
        ))

        val rule = state.rules.single()
        assertEquals("AA:BB:CC:DD:EE:01", rule.mac)
        assertTrue(rule.observedFields.contains("priority"))
        assertTrue(rule.observedFields.contains("unexpected"))
    }

    // ---- Field types and absences ----

    @Test
    fun `a rule without a remark yields an empty remark`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(JSONObject().put("enableRule", true).put("enableLink", false)
                    .put("ippro", "IPV4").put("mac", "AA:BB:CC:DD:EE:01"))
            )
        ))

        assertEquals("", state.rules.single().remark)
    }

    @Test
    fun `a missing mac drops the rule and reports it`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    JSONObject().put("enableRule", true).put("enableLink", false)
                        .put("ippro", "IPV4"),
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:02"),
                )
            )
        ))

        assertEquals(listOf("AA:BB:CC:DD:EE:02"), state.rules.map { it.mac })
        assertEquals(FirewallAnomalyKind.MISSING_MAC, state.anomalies.single().kind)
        assertEquals(0, state.anomalies.single().ruleIndex)
    }

    @Test
    fun `an invalid mac is kept as-is and reported, never repaired`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(Fixtures.firewallRule("not-a-mac"))
            )
        ))

        val rule = state.rules.single()
        assertEquals("not-a-mac", rule.mac)
        assertFalse(rule.macIsValid)
        assertEquals(FirewallAnomalyKind.INVALID_MAC, state.anomalies.single().kind)
    }

    @Test
    fun `a lowercase mac without separators is normalised to colon form`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(Fixtures.firewallRule("aabbccddeeff"))
            )
        ))

        val rule = state.rules.single()
        assertEquals("AA:BB:CC:DD:EE:FF", rule.mac)
        assertTrue(rule.macIsValid)
    }

    @Test
    fun `enableRule accepts booleans and one zero strings and numbers`() {
        val values = listOf<Any>(true, false, "1", "0", 1, 0)
        val expected = listOf(true, false, true, false, true, false)

        values.forEachIndexed { index, value ->
            val state = dataOf(FirewallMacFilterParser.parseRules(
                Fixtures.firewallRulesReply(
                    listOf(
                        Fixtures.firewallRule("AA:BB:CC:DD:EE:0$index")
                            .put("enableRule", value)
                    )
                )
            ))
            assertEquals("value $value", expected[index], state.rules.single().enabled)
        }
    }

    @Test
    fun `enableLink selects the black list or the white list`() {
        val black = dataOf(
            FirewallMacFilterParser.parseRules(
                Fixtures.firewallRulesReply(
                    listOf(Fixtures.firewallRule("AA:BB:CC:DD:EE:01", enableLink = false))
                )
            )
        ).rules.single()
        val white = dataOf(
            FirewallMacFilterParser.parseRules(
                Fixtures.firewallRulesReply(
                    listOf(Fixtures.firewallRule("AA:BB:CC:DD:EE:02", enableLink = true))
                )
            )
        ).rules.single()

        assertEquals(FirewallMacVerdict.BLACKLIST, black.verdict)
        assertEquals(FirewallMacVerdict.WHITELIST, white.verdict)
    }

    @Test
    fun `an unreadable enableLink is null and reported, never guessed`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01")
                        .put("enableLink", "maybe")
                )
            )
        ))

        assertNull(state.rules.single().verdict)
        assertEquals(
            FirewallAnomalyKind.UNREADABLE_ENABLE_LINK,
            state.anomalies.single().kind,
        )
    }

    @Test
    fun `a lowercase ippro is accepted and normalised`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(Fixtures.firewallRule("AA:BB:CC:DD:EE:01", ippro = "ipv6"))
            )
        ))

        assertEquals(FirewallIpProtocol.IPV6, state.rules.single().ipProtocol)
        assertTrue(state.anomalies.isEmpty())
    }

    @Test
    fun `an unknown ippro is not guessed and is reported`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(Fixtures.firewallRule("AA:BB:CC:DD:EE:01", ippro = "IPX"))
            )
        ))

        val rule = state.rules.single()
        assertEquals(FirewallIpProtocol.UNRECOGNIZED, rule.ipProtocol)
        assertEquals("IPX", rule.ipProtocolRaw)
        assertEquals(
            FirewallAnomalyKind.UNKNOWN_IP_PROTOCOL,
            state.anomalies.single().kind,
        )
    }

    @Test
    fun `a rule that is not a json object is skipped without hiding the others`() {
        val datas = JSONArray()
            .put("not an object")
            .put(Fixtures.firewallRule("AA:BB:CC:DD:EE:01"))

        val state = dataOf(FirewallMacFilterParser.parseRules(
            JSONObject().put("success", true).put("datas", datas)
        ))

        assertEquals(listOf("AA:BB:CC:DD:EE:01"), state.rules.map { it.mac })
        assertEquals(FirewallAnomalyKind.RULE_NOT_OBJECT, state.anomalies.single().kind)
    }

    @Test
    fun `duplicate macs are reported as-is and never merged`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                listOf(
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01"),
                    Fixtures.firewallRule("AA:BB:CC:DD:EE:01"),
                )
            )
        ))

        assertEquals(2, state.rules.size)
    }

    // ---- Size ----

    @Test
    fun `exactly thirty two rules are at the documented limit`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                (1..32).map { Fixtures.firewallRule(mac(it)) }
            )
        ))

        assertEquals(32, state.rules.size)
        assertTrue(state.isAtDocumentedLimit)
        assertFalse(state.exceedsDocumentedLimit)
    }

    @Test
    fun `a list above thirty two rules is not silently truncated`() {
        val state = dataOf(FirewallMacFilterParser.parseRules(
            Fixtures.firewallRulesReply(
                (1..33).map { Fixtures.firewallRule(mac(it)) }
            )
        ))

        assertEquals(33, state.rules.size)
        assertTrue(state.exceedsDocumentedLimit)
    }

    // ---- Default policy (cmd 30) ----

    @Test
    fun `the default policy reads both protocols`() {
        val defaults = dataOf(FirewallMacFilterParser.parseDefaults(
            Fixtures.firewallDefaultsReply(ipv4AcceptAll = "true", ipv6AcceptAll = "false")
        ))

        assertEquals(FirewallIpProtocol.IPV4, defaults.ipv4?.ipProtocol)
        assertEquals(true, defaults.ipv4?.acceptAll)
        assertEquals(FirewallIpProtocol.IPV6, defaults.ipv6?.ipProtocol)
        assertEquals(false, defaults.ipv6?.acceptAll)
        assertTrue(defaults.anomalies.isEmpty())
    }

    @Test
    fun `the default policy accepts booleans as well as strings`() {
        val defaults = dataOf(FirewallMacFilterParser.parseDefaults(
            Fixtures.firewallDefaultsReply(ipv4AcceptAll = true, ipv6AcceptAll = false)
        ))

        assertEquals(true, defaults.ipv4?.acceptAll)
        assertEquals(false, defaults.ipv6?.acceptAll)
    }

    @Test
    fun `a missing default entry stays null`() {
        val reply = JSONObject().put("success", true).put(
            "datas",
            JSONArray(
                listOf(
                    JSONObject().put("enableRule", true)
                        .put("acceptAll", "true").put("ippro", "IPV4")
                )
            )
        )

        val defaults = dataOf(FirewallMacFilterParser.parseDefaults(reply))

        assertEquals(true, defaults.ipv4?.acceptAll)
        assertNull(defaults.ipv6)
    }

    @Test
    fun `an unreadable acceptAll is null and reported`() {
        val defaults = dataOf(FirewallMacFilterParser.parseDefaults(
            Fixtures.firewallDefaultsReply(ipv4AcceptAll = "sometimes", ipv6AcceptAll = "true")
        ))

        assertNull(defaults.ipv4?.acceptAll)
        assertEquals(
            FirewallAnomalyKind.UNREADABLE_ACCEPT_ALL,
            defaults.anomalies.single().kind,
        )
    }

    private fun mac(index: Int): String =
        "AA:BB:CC:DD:%02X:%02X".format((index / 256) % 256, index % 256)
}
