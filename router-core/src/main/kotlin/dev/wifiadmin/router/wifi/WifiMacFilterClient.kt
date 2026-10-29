package dev.wifiadmin.router.wifi

import dev.wifiadmin.router.OkHttpRouterTransport
import dev.wifiadmin.router.RouterAuthenticationException
import dev.wifiadmin.router.RouterNetworkException
import dev.wifiadmin.router.RouterProtocolException
import dev.wifiadmin.router.RouterSettings
import dev.wifiadmin.router.RouterTransport
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Read/write client for the router's **Wi-Fi MAC filter** (command `278`).
 *
 * This filter is **not** the firewall MAC filter (`cmd 23`). It controls
 * whether a device may **associate** with a Wi-Fi radio, per band:
 *
 * - `CLOSE` — the filter is disabled;
 * - `ALLOW` — only the listed MACs may associate (a white list);
 * - `DENY`  — the listed MACs may not associate (a black list).
 *
 * The two bands are independent: each has its own mode and its own list, and
 * a write to one band never touches the other.
 *
 * ## Safety
 *
 * A write **replaces the whole list** of the band it targets, exactly like
 * the router's web UI does. The client therefore:
 *
 * - refuses to write `ALLOW` with an empty list, which would lock every
 *   wireless device out of the band;
 * - refuses to write when the state read from the router no longer matches
 *   the state the caller meant to modify, so a concurrent change made in the
 *   web UI is never silently overwritten;
 * - checks that the Wi-Fi is ready (`cmd 417`) and that WPS is off
 *   (`cmd 132`) before writing, because the router ignores the filter while
 *   WPS is enabled;
 * - reads the state back after writing and never reports success on the
 *   strength of the write reply alone;
 * - serialises writes so two concurrent calls cannot interleave and lose
 *   rules.
 *
 * Nothing here modifies WPS: the client only *reads* its state.
 */
class WifiMacFilterClient(
    private val settings: RouterSettings,
    private val transport: RouterTransport = OkHttpRouterTransport(),
    private val timeoutSeconds: Long = OkHttpRouterTransport.DEFAULT_TIMEOUT_SECONDS,
) {

    /** Guards the session and serialises the read-modify-write cycles. */
    private val lock = Any()

    private var sessionId: String? = null
    private var writeToken: String? = null

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads the filter state of one band.
     *
     * This is a **read-only** operation: it sends `GET 278` with the band's
     * `subcmd` and never modifies anything on the router.
     *
     * @throws WifiMacAuthenticationException when the credentials are refused.
     * @throws WifiMacCommunicationException when the router cannot be reached.
     * @throws WifiMacCompatibilityException when the reply cannot be parsed.
     */
    fun read(band: WifiBandFilter): WifiMacFilterState = synchronized(lock) {
        val response = sendRead(CMD_WIRELESS_MACFILTER, mapOf("subcmd" to band.subcmd))
        parseState(band, response)
    }

    /**
     * Reads one band and reports the JSON keys that were actually present.
     *
     * Used to document the real response structure without disclosing any
     * device data.
     */
    fun readWithStructure(band: WifiBandFilter): ObservedFilter = synchronized(lock) {
        val response = sendRead(CMD_WIRELESS_MACFILTER, mapOf("subcmd" to band.subcmd))
        val state = parseState(band, response)
        val datas = response.optJSONObject("datas")
        ObservedFilter(
            band = band,
            state = state,
            topLevelKeys = response.keys().asSequence().toList(),
            datasKeys = datas?.keys()?.asSequence()?.toList().orEmpty(),
            entryKeys = datas?.optJSONArray("maclist")
                ?.optJSONObject(0)
                ?.keys()
                ?.asSequence()
                ?.toList()
                .orEmpty()
        )
    }

    /** What a `GET 278` reply contained, without any device data. */
    data class ObservedFilter(
        val band: WifiBandFilter,
        val state: WifiMacFilterState,
        val topLevelKeys: List<String>,
        val datasKeys: List<String>,
        val entryKeys: List<String>,
    ) {
        /** The observed structure, as a flat list of key names. */
        val structure: List<String>
            get() = buildList {
                addAll(topLevelKeys)
                datasKeys.forEach { add("datas.$it") }
                entryKeys.forEach { add("datas.maclist[].$it") }
            }
    }

    /**
     * Reads both bands.
     *
     * A failure on one band does not prevent the other from being read: the
     * result carries the error per band so a partial answer is still usable.
     */
    fun readAll(): Map<WifiBandFilter, Result<WifiMacFilterState>> = synchronized(lock) {
        WifiBandFilter.entries.associateWith { band ->
            runCatching { read(band) }
        }
    }

    // ------------------------------------------------------------------
    // Preconditions
    // ------------------------------------------------------------------

    /**
     * Checks that a write would be accepted by the router.
     *
     * Reads the Wi-Fi initialisation state (`cmd 417`) and the WPS state
     * (`cmd 132`). Nothing is written.
     *
     * @return the WPS state of the band, or a failure explaining why the band
     *   is not writable.
     */
    fun checkWritable(band: WifiBandFilter): Result<WpsState> = synchronized(lock) {
        ensureSession()

        val wifiStatus = try {
            sendRead(CMD_WIFI_INIT_STATUS, emptyMap()).optString("wifiStatus", "")
        } catch (e: WifiMacFilterException) {
            return Result.failure(e)
        }
        if (wifiStatus != "1") {
            return Result.failure(
                WifiMacCompatibilityException.CommandRefused(
                    "The Wi-Fi is not initialised (wifiStatus='$wifiStatus'); " +
                        "the router refuses MAC filter writes in this state"
                )
            )
        }

        val wps = try {
            readWpsState(band)
        } catch (e: WifiMacFilterException) {
            return Result.failure(e)
        }
        if (wps.enabled) {
            return Result.failure(
                WifiMacCompatibilityException.CommandRefused(
                    "WPS is enabled on ${band.wireLabel}; the router ignores the " +
                        "MAC filter while WPS is on. Disable WPS first " +
                        "(this client never modifies WPS)."
                )
            )
        }
        Result.success(wps)
    }

    /** The WPS state of one band, as read from `cmd 132`. */
    data class WpsState(
        val band: WifiBandFilter,
        val enabled: Boolean,
        val wifiOpen: Boolean,
    )

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Writes the filter state of one band.
     *
     * The whole list of the band is replaced, exactly as the router's web UI
     * does.
     *
     * @param expected the state that was read from the router and that the
     *   caller intends to modify. The write is refused if the router's state
     *   has changed since.
     * @param newState the state to write.
     *
     * @throws WifiMacValidationException when the write is refused before
     *   anything is sent (lock-out, stale state, invalid MAC).
     * @throws WifiMacCompatibilityException when the router refuses the write.
     * @throws WifiMacAuthenticationException when the session cannot be renewed.
     * @throws WifiMacCommunicationException when the router cannot be reached.
     */
    fun write(expected: WifiMacFilterState, newState: WifiMacFilterState): Unit = synchronized(lock) {
        require(expected.band == newState.band) {
            "The expected state and the new state must target the same band"
        }

        // --- Validation, before any request ---------------------------
        if (newState.locksOutEveryDevice) {
            throw WifiMacValidationException.WouldLockOutEveryDevice(newState.band)
        }
        validateEntries(newState)

        // --- Optimistic concurrency check -----------------------------
        // The write replaces the whole list, so a stale snapshot would erase
        // rules added in the meantime.
        val current = read(newState.band)
        if (current != expected) {
            throw WifiMacValidationException.StaleState(newState.band, current)
        }

        // --- Router preconditions -------------------------------------
        checkWritable(newState.band).getOrThrow()

        // --- Write ------------------------------------------------------
        sendWrite(
            CMD_WIRELESS_MACFILTER,
            mapOf(
                "subcmd" to newState.band.subcmd,
                "datas" to JSONObject().apply {
                    put("macfilter", newState.mode.wire)
                    put("maclist", JSONArray().apply {
                        newState.entries.forEach { entry ->
                            put(
                                JSONObject()
                                    .put("mac", entry.mac)
                                    .put("remarks", entry.remark)
                            )
                        }
                    })
                }
            )
        )

        // --- Verification read ------------------------------------------
        // The write reply alone is never trusted: the state is read back.
        val after = read(newState.band)
        if (after != newState) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The write of the ${newState.band.wireLabel} filter was not confirmed " +
                    "by a verification read"
            )
        }
    }

    /**
     * Restores a previously saved state.
     *
     * The restore is **not** subject to the optimistic concurrency check: its
     * whole purpose is to put back a known state, even if the router has
     * drifted since the snapshot was taken. All the other guards still apply.
     */
    fun restore(saved: WifiMacFilterState): Unit = synchronized(lock) {
        if (saved.locksOutEveryDevice) {
            throw WifiMacValidationException.WouldLockOutEveryDevice(saved.band)
        }
        validateEntries(saved)
        checkWritable(saved.band).getOrThrow()

        sendWrite(
            CMD_WIRELESS_MACFILTER,
            mapOf(
                "subcmd" to saved.band.subcmd,
                "datas" to JSONObject().apply {
                    put("macfilter", saved.mode.wire)
                    put("maclist", JSONArray().apply {
                        saved.entries.forEach { entry ->
                            put(
                                JSONObject()
                                    .put("mac", entry.mac)
                                    .put("remarks", entry.remark)
                            )
                        }
                    })
                }
            )
        )

        val after = read(saved.band)
        if (after != saved) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The restore of the ${saved.band.wireLabel} filter was not confirmed " +
                    "by a verification read"
            )
        }
    }

    /**
     * Disables the filter of one band.
     *
     * This is the lightest possible way to unblock a device: the mode becomes
     * `CLOSE` and **the list is preserved**, so nothing is lost and the filter
     * can be re-enabled later.
     */
    fun disable(band: WifiBandFilter): Unit = synchronized(lock) {
        val current = read(band)
        val target = current.copy(mode = WifiMacMode.CLOSE)
        write(current, target)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun validateEntries(state: WifiMacFilterState) {
        if (state.entries.size > MAX_ENTRIES) {
            throw WifiMacValidationException.TooManyEntries(state.band, state.entries.size)
        }
        val seen = mutableSetOf<String>()
        state.entries.forEach { entry ->
            val normalized = WifiMacFilterState.validateMac(entry.mac)
            if (!seen.add(normalized)) {
                throw WifiMacValidationException.DuplicateMac(normalized)
            }
        }
    }

    /** Parses the `datas` object of a `cmd 278` reply. */
    internal fun parseState(band: WifiBandFilter, response: JSONObject): WifiMacFilterState {
        if (!response.has("datas") || response.isNull("datas")) {
            // The web UI treats a missing `datas` as "no filter configured".
            return WifiMacFilterState(band, WifiMacMode.CLOSE, emptyList())
        }
        val datas = response.optJSONObject("datas")
            ?: throw WifiMacCompatibilityException.MissingDatas(band)

        val mode = WifiMacMode.fromWire(datas.optString("macfilter", ""))
            ?: throw WifiMacCompatibilityException.UnknownMode(
                band,
                datas.optString("macfilter", "")
            )

        val list = datas.optJSONArray("maclist")
        val entries = if (list == null) {
            throw WifiMacCompatibilityException.MalformedList(band)
        } else {
            parseEntries(band, list)
        }

        return WifiMacFilterState(band, mode, entries)
    }

    private fun parseEntries(band: WifiBandFilter, list: JSONArray): List<WifiMacEntry> {
        val entries = mutableListOf<WifiMacEntry>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index)
                ?: throw WifiMacCompatibilityException.MalformedList(band)
            val rawMac = item.optString("mac", "").trim()
            if (rawMac.isEmpty()) {
                throw WifiMacCompatibilityException.MalformedList(band)
            }
            // The router may send the MAC in any case, with or without
            // separators: it is normalised on the way in.
            val normalized = WifiMacFilterState.normalizeMac(rawMac)
            if (normalized.isEmpty()) {
                throw WifiMacCompatibilityException.MalformedList(band)
            }
            entries += WifiMacEntry(normalized, item.optString("remarks", ""))
        }
        return entries
    }

    private fun readWpsState(band: WifiBandFilter): WpsState {
        // The WPS subcmd convention is the opposite of the MAC filter one:
        // 0 for the 2.4 GHz band, 1 for the 5 GHz band.
        val wpsSubcmd = if (band == WifiBandFilter.BAND_2_4_GHZ) "0" else "1"
        val response = sendRead(CMD_WPS_CONFIG, mapOf("subcmd" to wpsSubcmd))
        val switchField = if (band == WifiBandFilter.BAND_2_4_GHZ) {
            "wlan2g_wps_switch"
        } else {
            "wlan5g_wps_switch"
        }
        val wifiOpen = response.optString("wifiOpen", "")
        val enabled = wifiOpen == "1" && response.optString(switchField, "") == "1"
        return WpsState(band, enabled, wifiOpen == "1")
    }

    /** Sends a read command, retrying once on a network failure. */
    private fun sendRead(cmd: Int, extra: Map<String, Any>): JSONObject {
        try {
            return send(cmd, "GET", extra)
        } catch (e: WifiMacCommunicationException) {
            // A read is safe to repeat: the worst case is one duplicated query.
            return send(cmd, "GET", extra)
        }
    }

    private fun sendWrite(cmd: Int, extra: Map<String, Any>): JSONObject {
        // A write needs the CSRF token the router issues for the session.
        val token = fetchWriteToken()
        return send(cmd, "POST", extra + ("token" to token))
    }

    private fun fetchWriteToken(): String {
        writeToken?.let { return it }
        val response = send(CMD_RESET_LOGIN_TIME, "GET", emptyMap())
        val token = response.optString("token", "").trim()
        if (token.isEmpty()) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The router did not return a write token"
            )
        }
        writeToken = token
        return token
    }

    private fun send(cmd: Int, method: String, extra: Map<String, Any>): JSONObject {
        ensureSession()

        val payload = JSONObject().apply {
            put("cmd", cmd)
            put("method", method)
            put("sessionId", sessionId.orEmpty())
            extra.forEach { (key, value) -> put(key, value) }
        }

        val body = try {
            transport.post(
                "http://${settings.routerHost.trim()}$ENDPOINT_PATH",
                payload.toString(),
                timeoutSeconds
            )
        } catch (e: IOException) {
            throw WifiMacCommunicationException("The router could not be reached", e)
        } catch (e: RuntimeException) {
            throw WifiMacCommunicationException("The router could not be reached", e)
        }

        val response = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The router answered with malformed JSON"
            )
        }

        val message = response.optString("message", "")
        if (message in SESSION_EXPIRED_MESSAGES) {
            // The session expired: renew it and try once more.
            sessionId = null
            writeToken = null
            ensureSession()
            return try {
                transport.post(
                    "http://${settings.routerHost.trim()}$ENDPOINT_PATH",
                    payload.toString(),
                    timeoutSeconds
                ).let { JSONObject(it) }
            } catch (e: IOException) {
                throw WifiMacCommunicationException("The router could not be reached", e)
            } catch (e: JSONException) {
                throw WifiMacCompatibilityException.CommandRefused(
                    "The router answered with malformed JSON"
                )
            }
        }

        if (response.has("error") || (response.has("success") && !response.optBoolean("success", true))) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The router refused command $cmd: ${response.optString("message", "unknown error")}"
            )
        }

        return response
    }

    /** Opens a session if there is none. */
    private fun ensureSession() {
        if (sessionId != null) return

        val tokenResponse = try {
            transport.post(
                "http://${settings.routerHost.trim()}$ENDPOINT_PATH",
                JSONObject()
                    .put("cmd", CMD_GET_NEXT_LOGIN_TIME)
                    .put("method", "GET")
                    .put("sessionId", "")
                    .toString(),
                timeoutSeconds
            )
        } catch (e: IOException) {
            throw WifiMacCommunicationException("The router could not be reached", e)
        }

        val challenge = try {
            JSONObject(tokenResponse).optString("token", "").trim()
        } catch (e: JSONException) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The router answered with malformed JSON"
            )
        }
        if (challenge.isEmpty()) {
            throw WifiMacAuthenticationException("The router did not return a challenge token")
        }

        val generatedSessionId = newSessionId()
        val loginBody = try {
            transport.post(
                "http://${settings.routerHost.trim()}$ENDPOINT_PATH",
                JSONObject().apply {
                    put("cmd", CMD_LOGIN)
                    put("method", "POST")
                    put("sessionId", generatedSessionId)
                    put("username", settings.username)
                    put("passwd", sha256Hex(challenge + settings.password))
                    put("isAutoUpgrade", "0")
                    put("subcmd", 0)
                }.toString(),
                timeoutSeconds
            )
        } catch (e: IOException) {
            throw WifiMacCommunicationException("The router could not be reached", e)
        }

        val loginResponse = try {
            JSONObject(loginBody)
        } catch (e: JSONException) {
            throw WifiMacCompatibilityException.CommandRefused(
                "The router answered with malformed JSON"
            )
        }

        val accepted = loginResponse.optBoolean("success", false) ||
            loginResponse.optString("message", "") == "success" ||
            loginResponse.has("sessionId")
        if (!accepted) {
            throw WifiMacAuthenticationException("The router refused the credentials")
        }
        sessionId = loginResponse.optString("sessionId", "").trim().ifEmpty { generatedSessionId }
    }

    private fun newSessionId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "") +
            java.util.UUID.randomUUID().toString().replace("-", "")

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val ENDPOINT_PATH = "/cgi-bin/http.cgi"

        private const val CMD_LOGIN = 100
        private const val CMD_GET_NEXT_LOGIN_TIME = 232
        private const val CMD_RESET_LOGIN_TIME = 233
        private const val CMD_WPS_CONFIG = 132
        private const val CMD_WIFI_INIT_STATUS = 417
        private const val CMD_WIRELESS_MACFILTER = 278

        private const val MAX_ENTRIES = 1024

        private val SESSION_EXPIRED_MESSAGES = setOf("NO_AUTH", "LOGIN_TIMEOUT")
    }
}

/** Human-readable label of a band, used in messages. */
internal val WifiBandFilter.wireLabel: String
    get() = when (this) {
        WifiBandFilter.BAND_2_4_GHZ -> "2.4 GHz"
        WifiBandFilter.BAND_5_GHZ -> "5 GHz"
    }
