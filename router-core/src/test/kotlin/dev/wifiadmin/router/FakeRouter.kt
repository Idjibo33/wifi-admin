package dev.wifiadmin.router

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * One request the fake router received.
 */
data class RecordedRequest(
    val url: String,
    val body: String,
    val timeoutSeconds: Long,
) {
    val json: JSONObject get() = JSONObject(body)
    val cmd: Int get() = json.getInt("cmd")
    val method: String get() = json.optString("method")
}

/**
 * Stands in for the router CGI. It records every request and answers by (cmd, method).
 *
 * A method specific handler ("223:GET") wins over a cmd wide one ("223").
 */
class FakeRouter : RouterTransport {

    val requests = mutableListOf<RecordedRequest>()

    private val jsonHandlers = mutableMapOf<String, (JSONObject) -> JSONObject>()
    private val rawHandlers = mutableMapOf<String, (JSONObject) -> String>()
    private val subcmdHandlers = mutableMapOf<String, (JSONObject) -> JSONObject>()

    /** JSON reply for a cmd (and optionally only for one method). */
    fun onJson(cmd: Int, method: String? = null, reply: (JSONObject) -> JSONObject) {
        jsonHandlers[key(cmd, method)] = reply
    }

    /** Raw reply body for a cmd; use it to return malformed JSON or to throw. */
    fun onRaw(cmd: Int, method: String? = null, reply: (JSONObject) -> String) {
        rawHandlers[key(cmd, method)] = reply
    }

    /** Makes a request fail like a broken connection would. */
    fun onNetworkFailure(cmd: Int, method: String? = null) {
        onRaw(cmd, method) { throw IOException("connection reset") }
    }

    /**
     * Registers a handler for one `subcmd` of a command.
     *
     * The key is `cmd:method:subcmd`, which is more precise than [onJson] and
     * lets a test tell the 2.4 GHz band from the 5 GHz one.
     */
    fun onSubcmd(cmd: Int, method: String, subcmd: String, reply: (JSONObject) -> JSONObject) {
        subcmdHandlers["$cmd:$method:$subcmd"] = reply
    }

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        val request = JSONObject(body)
        requests += RecordedRequest(url, body, timeoutSeconds)
        val cmd = request.getInt("cmd")
        val method = request.optString("method")
        val subcmd = request.optString("subcmd")
        // A handler that matches only the cmd (or cmd+method) is more general
        // than a subcmd-specific one, so it takes precedence: a test that
        // stubs a whole command must still be able to simulate a failure.
        rawHandlers["$cmd:$method"]?.let { return it(request) }
        rawHandlers["$cmd"]?.let { return it(request) }
        if (subcmd.isNotEmpty()) {
            subcmdHandlers["$cmd:$method:$subcmd"]?.let { return it(request).toString() }
        }
        jsonHandlers["$cmd:$method"]?.let { return it(request).toString() }
        jsonHandlers["$cmd"]?.let { return it(request).toString() }
        return JSONObject().put("success", true).toString()
    }

    /** Every request sent for a cmd (and optionally only for one method). */
    fun sent(cmd: Int, method: String? = null): List<RecordedRequest> =
        requests.filter {
            it.cmd == cmd && (method == null || it.method == method)
        }

    private fun key(cmd: Int, method: String?): String =
        if (method == null) "$cmd" else "$cmd:$method"
}

/**
 * Builders for router replies shaped like the real ones.
 */
object Fixtures {

    fun dhcpReply(entries: List<JSONObject>): JSONObject =
        JSONObject().put("success", true).put("dhcp_list_info", JSONArray(entries))

    fun dhcpEntry(
        mac: String,
        ip: String,
        hostname: String? = null,
        interfaceName: String? = null,
        expires: Any? = null,
        ipv6: String? = null,
    ): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("ip", ip)
        if (hostname != null) put("hostname", hostname)
        if (interfaceName != null) put("interface", interfaceName)
        if (expires != null) put("expires", expires)
        if (ipv6 != null) put("ipv6", ipv6)
    }

    fun wifi24Reply(entries: List<JSONObject>): JSONObject =
        JSONObject().put("success", true).put("wlan24g_wifi_info", JSONArray(entries))

    fun wifi5gReply(entries: List<JSONObject>): JSONObject =
        JSONObject().put("success", true).put("wlan5g_wifi_info", JSONArray(entries))

    fun wifiEntry(
        mac: String,
        ip: String? = null,
        rssi: Any? = null,
        txrate: String? = null,
        rxrate: String? = null,
        ssid: String? = null,
    ): JSONObject = JSONObject().apply {
        put("mac", mac)
        if (ip != null) put("ip", ip)
        if (rssi != null) put("rssi", rssi)
        if (txrate != null) put("txrate", txrate)
        if (rxrate != null) put("rxrate", rxrate)
        if (ssid != null) put("ssid", ssid)
    }

    // ---- Firewall MAC filter (cmd 23 / 30) ----

    fun firewallRulesReply(rules: List<JSONObject>): JSONObject =
        JSONObject().put("success", true).put("cmd", 23).put("datas", JSONArray(rules))

    fun firewallRule(
        mac: String,
        ippro: String = "IPV4",
        enableLink: Boolean = false,
        enableRule: Boolean = true,
        remark: String = "",
    ): JSONObject = JSONObject().apply {
        put("enableRule", enableRule)
        put("enableLink", enableLink)
        put("ippro", ippro)
        put("mac", mac)
        put("remark", remark)
    }

    fun firewallDefaultsReply(
        ipv4AcceptAll: Any = "true",
        ipv6AcceptAll: Any = "true",
    ): JSONObject = JSONObject().apply {
        put("success", true)
        put("cmd", 30)
        put(
            "datas",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("enableRule", true)
                        .put("acceptAll", ipv4AcceptAll)
                        .put("ippro", "IPV4"),
                    JSONObject()
                        .put("enableRule", true)
                        .put("acceptAll", ipv6AcceptAll)
                        .put("ippro", "IPV6"),
                )
            )
        )
    }
}
