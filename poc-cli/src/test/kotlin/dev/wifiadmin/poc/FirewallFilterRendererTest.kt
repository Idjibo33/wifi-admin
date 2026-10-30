package dev.wifiadmin.poc

import dev.wifiadmin.router.RouterJsonType
import dev.wifiadmin.router.RouterMessageCategory
import dev.wifiadmin.router.RouterReplyObservation
import dev.wifiadmin.router.firewall.FirewallAnomaly
import dev.wifiadmin.router.firewall.FirewallAnomalyKind
import dev.wifiadmin.router.firewall.FirewallDefaultRule
import dev.wifiadmin.router.firewall.FirewallDefaults
import dev.wifiadmin.router.firewall.FirewallInvalidReason
import dev.wifiadmin.router.firewall.FirewallIpProtocol
import dev.wifiadmin.router.firewall.FirewallMacRule
import dev.wifiadmin.router.firewall.FirewallMacRules
import dev.wifiadmin.router.firewall.FirewallMacVerdict
import dev.wifiadmin.router.firewall.FirewallReadOutcome
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallFilterRendererTest {

    // ---- Anonymity ----

    @Test
    fun `the report never prints a mac address`() {
        val report = renderRules(dataOutcome())

        assertFalse(report.contains("AA:BB:CC:DD:EE:01"))
        assertFalse(report.contains("AA:BB:CC:DD:EE:02"))
    }

    @Test
    fun `the report never prints a remark`() {
        val report = renderRules(dataOutcome())

        assertFalse(report.contains("Pixel de Camille"))
        assertFalse(report.contains("Camille"))
    }

    @Test
    fun `the report never prints the raw router message`() {
        val observation = observation(
            message = "user=admin password=hunter2",
            messageCategory = RouterMessageCategory.UNKNOWN,
        )
        val report = renderRules(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_MISSING, observation)
        )

        assertFalse(report.contains("hunter2"))
        assertFalse(report.contains("user=admin"))
        assertTrue(report.contains("valeur non reconnue"))
    }

    @Test
    fun `the report never prints a key that looks like a secret`() {
        val observation = RouterReplyObservation.of(
            JSONObject().put("success", true).put("sessionId", "ROUTER-SESSION")
        )
        val report = renderRules(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_MISSING, observation)
        )

        assertFalse(report.contains("sessionId"))
    }

    // ---- States ----

    @Test
    fun `a confirmed empty list says so`() {
        val report = renderRules(
            FirewallReadOutcome.Data(FirewallMacRules(emptyList(), listOf("datas", "success")), observation())
        )

        assertTrue(report.contains("DONNÉES REÇUES"))
        assertTrue(report.contains("règles lues              0"))
        assertTrue(report.contains("VIDE (confirmée)"))
        assertFalse(report.contains("RÉPONSE INVALIDE"))
    }

    @Test
    fun `a missing payload is an invalid response, not an empty list`() {
        val report = renderRules(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_MISSING, observation())
        )

        assertTrue(report.contains("RÉPONSE INVALIDE"))
        assertTrue(report.contains("datas absent"))
        assertFalse(
            "no rule count may be printed when there is no collection",
            report.contains("règles lues"),
        )
        assertFalse(report.contains("VIDE (confirmée)"))
    }

    @Test
    fun `a null payload is an invalid response`() {
        val report = renderRules(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_NULL, observation())
        )

        assertTrue(report.contains("RÉPONSE INVALIDE"))
        assertTrue(report.contains("datas null"))
    }

    @Test
    fun `an unavailable feature is reported as such`() {
        val report = renderRules(FirewallReadOutcome.Unavailable("firewall", observation()))

        assertTrue(report.contains("FONCTION INDISPONIBLE"))
    }

    @Test
    fun `an indeterminate read is reported as such`() {
        val report = renderRules(
            FirewallReadOutcome.Indeterminate(
                dev.wifiadmin.router.firewall.FirewallIndeterminateReason.NETWORK,
                observation(),
            )
        )

        assertTrue(report.contains("INDÉTERMINÉ"))
    }

    @Test
    fun `the report prints the counts and the observed structure`() {
        val report = renderRules(dataOutcome())

        assertTrue(report.contains("règles lues              2"))
        assertTrue(report.contains("liste noire              1"))
        assertTrue(report.contains("liste blanche            1"))
        assertTrue(report.contains("clés de la réponse       cmd, datas, message, success"))
        assertTrue(report.contains("limite documentée        32"))
    }

    @Test
    fun `the report names the anomalies without their content`() {
        val report = renderRules(dataOutcome())

        assertTrue(report.contains("INVALID_MAC=1"))
        assertFalse(report.contains("not-a-mac"))
    }

    @Test
    fun `the report shows the type of the collection`() {
        val report = renderRules(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_MISSING, observation())
        )

        assertTrue(report.contains("collection datas         absente"))
    }

    @Test
    fun `the report shows the success flag`() {
        val report = renderRules(dataOutcome())

        assertTrue(report.contains("success                  true"))
    }

    // ---- Defaults ----

    @Test
    fun `the default policy is reported per protocol`() {
        val report = FirewallFilterRenderer.renderDefaults(
            FirewallReadOutcome.Data(defaults(), observation())
        )

        assertTrue(report.contains("IPv4"))
        assertTrue(report.contains("accepte tout (liste noire)"))
        assertTrue(report.contains("refuse tout sauf exceptions (liste blanche)"))
    }

    @Test
    fun `an unreadable default policy is an invalid response`() {
        val report = FirewallFilterRenderer.renderDefaults(
            FirewallReadOutcome.Invalid(FirewallInvalidReason.DATAS_MISSING, observation())
        )

        assertTrue(report.contains("RÉPONSE INVALIDE"))
        assertFalse(report.contains("accepte tout"))
    }

    // ---- Helpers ----

    private fun renderRules(outcome: FirewallReadOutcome<FirewallMacRules>): String =
        FirewallFilterRenderer.renderRules(outcome)

    private fun observation(
        message: String = "success",
        messageCategory: RouterMessageCategory = RouterMessageCategory.SUCCESS,
    ): RouterReplyObservation = RouterReplyObservation.of(
        JSONObject().put("cmd", 23).put("message", message).put("success", true)
    )

    private fun observationWithDatas(): RouterReplyObservation = RouterReplyObservation.of(
        JSONObject()
            .put("cmd", 23)
            .put("message", "success")
            .put("success", true)
            .put("datas", org.json.JSONArray())
    )

    private fun dataOutcome(): FirewallReadOutcome.Data<FirewallMacRules> =
        FirewallReadOutcome.Data(
            FirewallMacRules(
                rules = listOf(
                    FirewallMacRule(
                        mac = "AA:BB:CC:DD:EE:01",
                        macIsValid = true,
                        enabled = true,
                        verdict = FirewallMacVerdict.BLACKLIST,
                        ipProtocol = FirewallIpProtocol.IPV4,
                        ipProtocolRaw = "IPV4",
                        remark = "Pixel de Camille",
                        observedFields = setOf("mac", "ippro", "enableRule", "enableLink", "remark"),
                    ),
                    FirewallMacRule(
                        mac = "not-a-mac",
                        macIsValid = false,
                        enabled = true,
                        verdict = FirewallMacVerdict.WHITELIST,
                        ipProtocol = FirewallIpProtocol.IPV4V6,
                        ipProtocolRaw = "IPV4V6",
                        remark = "",
                        observedFields = setOf("mac", "ippro"),
                    ),
                ),
                observedKeys = listOf("cmd", "datas", "success"),
                anomalies = listOf(FirewallAnomaly(FirewallAnomalyKind.INVALID_MAC, 1)),
            ),
            observationWithDatas(),
        )

    private fun defaults(): FirewallDefaults = FirewallDefaults(
        ipv4 = FirewallDefaultRule(
            enabled = true,
            acceptAll = true,
            ipProtocol = FirewallIpProtocol.IPV4,
            ipProtocolRaw = "IPV4",
            observedFields = setOf("enableRule", "acceptAll", "ippro"),
        ),
        ipv6 = FirewallDefaultRule(
            enabled = true,
            acceptAll = false,
            ipProtocol = FirewallIpProtocol.IPV6,
            ipProtocolRaw = "IPV6",
            observedFields = setOf("enableRule", "acceptAll", "ippro"),
        ),
        observedKeys = listOf("cmd", "datas", "success"),
    )
}
