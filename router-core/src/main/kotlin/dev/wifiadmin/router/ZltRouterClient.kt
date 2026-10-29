package dev.wifiadmin.router

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Read-only client for the ZLT router web API.
 *
 * The client only ever sends the commands 232 (challenge token), 100 (login)
 * and the three read commands 223, 224 and 225. It never changes anything on
 * the router.
 *
 * The session id, the challenge token and the password are never logged and
 * never appear in an exception message.
 */
class ZltRouterClient(
    private val settings: RouterSettings,
    private val transport: RouterTransport = OkHttpRouterTransport(),
    private val timeoutSeconds: Long = OkHttpRouterTransport.DEFAULT_TIMEOUT_SECONDS,
) {

    private val lock = Any()

    /** The current session id, or null when there is no usable session. */
    private var sessionId: String? = null

    /**
     * The challenge token of the last login attempt.
     *
     * It is kept only so that [redact] can strip it if the router echoes it
     * back in an error message. The protocol does not constrain its format, so
     * it cannot be recognised by a pattern.
     */
    private var challengeToken: String? = null

    private val endpoint: String
        get() = "http://${settings.routerHost.trim()}$ENDPOINT_PATH"

    /**
     * Authenticates against the router.
     *
     * Sends cmd 232 to get a challenge token, hashes it together with the
     * password and sends cmd 100 with the hash.
     *
     * @return true when the router accepted the credentials.
     * @throws RouterNetworkException when the router could not be reached.
     * @throws RouterProtocolException when the router answered with unusable data.
     */
    fun login(): Boolean = synchronized(lock) {
        val tokenResponse = send(CMD_CHALLENGE, method = "GET", sessionId = "")

        // After a failed login the router refuses every attempt for 180 s.
        // Sending one anyway would only extend the penalty, so stop here.
        val cooldown = lockoutSecondsRemaining(tokenResponse)
        if (cooldown > 0) {
            sessionId = null
            challengeToken = null
            throw RouterCooldownException(cooldown)
        }

        val token = tokenResponse.optString("token", "").trim()
        if (token.isEmpty()) {
            sessionId = null
            challengeToken = null
            return false
        }
        challengeToken = token

        val generatedSessionId = newSessionId()
        val response = send(
            CMD_LOGIN,
            method = "POST",
            sessionId = generatedSessionId,
            extra = mapOf(
                "username" to settings.username,
                "passwd" to sha256Hex(token + settings.password),
                "isAutoUpgrade" to "0",
                "subcmd" to 0,
            ),
        )

        if (isExplicitLoginFailure(response)) {
            sessionId = null
            challengeToken = null
            return false
        }

        if (!hasPositiveLoginSignal(response)) {
            // No failure flag, but no session either. The router is refusing
            // logins (a hard lock) without saying for how long. It is *not* a
            // wrong password, so it must not be reported as one.
            sessionId = null
            challengeToken = null
            throw RouterCooldownException(retryAfterSeconds = null)
        }

        // Some routers answer without a session id: the client generated one then
        // has to stay in use.
        val routerSessionId = response.optString("sessionId", "").trim()
        sessionId = routerSessionId.ifEmpty { generatedSessionId }
        true
    }

    /**
     * The seconds left in the router's penalty window, or 0 when there is none.
     *
     * cmd 232 answers `buffer: "0"` while the router is in the 180 s penalty
     * that follows a failed login, and `netx_login_time` **counts the seconds
     * elapsed** since that failure. The wait is therefore `180 - netx_login_time`,
     * which is exactly how the router's own web UI computes it.
     */
    private fun lockoutSecondsRemaining(challengeReply: JSONObject): Int {
        if (challengeReply.optString("buffer", "") != "0") return 0
        val elapsed = challengeReply.optInt("netx_login_time", 0)
        return (LOGIN_PENALTY_SECONDS - elapsed).coerceAtLeast(0)
    }

    /**
     * Whether the login reply carries a signal that proves the credentials
     * were accepted.
     *
     * The `success` flag alone is **not** enough: this firmware answers
     * `success: true` for a wrong password (with `login_fail: "fail"`) and
     * even for a bare reply sent while it is refusing logins. The router's own
     * web UI only looks at `login_fail` and then uses `sessionId`.
     */
    private fun hasPositiveLoginSignal(response: JSONObject): Boolean =
        response.optString("sessionId", "").trim().isNotEmpty() ||
            response.optString("message", "").trim().equals("success", ignoreCase = true)

    /**
     * Whether the reply carries the firmware's explicit failure flag.
     *
     * `login_fail` is a string: `"fail"` on a wrong password. A success value
     * (`"success"`, `"0"`, `"false"`, `"ok"`) is not a failure.
     */
    private fun isExplicitLoginFailure(response: JSONObject): Boolean {
        // A plain `success: false` is an explicit refusal.
        if (response.has("success") && !response.optBoolean("success", true)) return true

        val value = response.optString("login_fail", "").trim()
        if (value.isEmpty()) return false
        if (value.lowercase() in LOGIN_FAILURE_FALSE_VALUES) return false
        return true
    }

    /**
     * Reads the DHCP lease list and both Wi-Fi association lists and merges them.
     *
     * Logs in when there is no session yet. When the router reports an expired
     * session (`NO_AUTH` or `LOGIN_TIMEOUT`) the client logs in again and
     * repeats the read exactly once.
     *
     * @throws RouterAuthenticationException when the credentials are refused or
     *   the session stays expired after a re-login.
     * @throws RouterNetworkException when the router could not be reached.
     * @throws RouterProtocolException when the router answered with unusable data.
     */
    fun fetchDevices(): DeviceSnapshot = synchronized(lock) {
        ensureSession()

        val dhcpReply = read(RouterPayloadParser.CMD_DHCP_LIST)
        val wifi24Reply = read(RouterPayloadParser.CMD_WIFI_2_4)
        val wifi5Reply = read(RouterPayloadParser.CMD_WIFI_5)

        DeviceMerger.merge(
            leases = RouterPayloadParser.parseDhcpLeases(dhcpReply),
            wifi24 = RouterPayloadParser.parseWifi24Associations(wifi24Reply),
            wifi5 = RouterPayloadParser.parseWifi5Associations(wifi5Reply),
        )
    }

    /** Drops the session so the next read logs in again. */
    fun invalidateSession() {
        synchronized(lock) {
            sessionId = null
            challengeToken = null
        }
    }

    // ---- Internals ----

    private fun ensureSession() {
        if (sessionId != null) return
        if (!login()) {
            throw RouterAuthenticationException("The router refused the credentials")
        }
    }

    /**
     * Sends one read command.
     *
     * A network failure is retried exactly once: a pooled connection can be
     * stale, and re-reading is harmless. An expired session triggers a
     * re-login and exactly one repeat of the read.
     *
     * Writes are not implemented in this module, so the "never replay a write"
     * rule of the reference client does not apply yet. It must be enforced
     * before any mutating command is added.
     */
    private fun read(cmd: Int): JSONObject {
        var response = sendRead(cmd)
        if (isSessionExpired(response)) {
            if (!login()) {
                throw RouterAuthenticationException(
                    "The router rejected the re-login, the session could not be renewed"
                )
            }
            response = sendRead(cmd)
            if (isSessionExpired(response)) {
                throw RouterAuthenticationException(
                    "The router still reports an expired session after the re-login"
                )
            }
        }
        if (!isSuccess(response)) {
            throw RouterProtocolException(
                "The router rejected command $cmd: ${describeFailure(response)}"
            )
        }
        return response
    }

    /** Sends a read command, retrying once on a network failure. */
    private fun sendRead(cmd: Int): JSONObject {
        try {
            return send(cmd, method = "GET", sessionId = sessionId.orEmpty())
        } catch (e: RouterNetworkException) {
            // A read is safe to repeat: the worst case is one duplicated query.
            return send(cmd, method = "GET", sessionId = sessionId.orEmpty())
        }
    }

    /** Sends one command and parses the reply. */
    private fun send(
        cmd: Int,
        method: String,
        sessionId: String,
        extra: Map<String, Any> = emptyMap(),
    ): JSONObject {
        val payload = JSONObject().apply {
            put("cmd", cmd)
            put("method", method)
            put("sessionId", sessionId)
            extra.forEach { (key, value) -> put(key, value) }
        }

        val body = try {
            transport.post(endpoint, payload.toString(), timeoutSeconds)
        } catch (e: IOException) {
            throw RouterNetworkException("The router could not be reached", e)
        } catch (e: RuntimeException) {
            // Some HTTP stacks surface connection problems as unchecked exceptions.
            // But a misuse of the API (for example calling this from the Android
            // main thread) must not be reported as an unreachable router: the
            // two failures need very different reactions from the caller.
            //
            // The check is done by name so that this module stays a pure JVM
            // library with no dependency on the Android framework.
            if (e.javaClass.simpleName == "NetworkOnMainThreadException") {
                throw RouterProtocolException(
                    "The router was queried from the main thread, which Android forbids",
                    e
                )
            }
            throw RouterNetworkException("The router could not be reached", e)
        }

        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw RouterProtocolException("The router answered with malformed JSON", e)
        }
    }

    private fun isSessionExpired(response: JSONObject): Boolean =
        response.optString("message", "") in SESSION_EXPIRED_MESSAGES

    private fun isSuccess(response: JSONObject): Boolean {
        if (response.has("error")) return false
        return response.optBoolean("success", false)
    }

    /**
     * A short, secret-free description of why a reply was not successful.
     *
     * The router's own message is used, but it is redacted: a router that echoes
     * the session id or a token back must not leak it into a log or a message.
     */
    private fun describeFailure(response: JSONObject): String {
        val message = response.optString("message", "").trim()
        if (message.isNotEmpty() && !message.equals("null", ignoreCase = true)) {
            return redact(message)
        }
        val error = response.optString("error", "").trim()
        if (error.isNotEmpty()) return redact(error)
        return "the reply did not report success"
    }

    /**
     * Removes the session id, the challenge token and any long hex token from a
     * router message.
     *
     * The session id and the challenge token are removed explicitly because the
     * protocol does not constrain their format: they cannot be recognised by a
     * pattern the way a hexadecimal password hash can.
     */
    private fun redact(text: String): String {
        var result = text
        sessionId?.takeIf { it.isNotEmpty() }?.let { result = result.replace(it, "<session>") }
        challengeToken?.takeIf { it.isNotEmpty() }?.let { result = result.replace(it, "<token>") }
        return result.replace(HEX_TOKEN_PATTERN, "<token>")
    }

    private fun newSessionId(): String =
        UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ENDPOINT_PATH = "/cgi-bin/http.cgi"

        private const val CMD_LOGIN = 100
        private const val CMD_CHALLENGE = 232

        private val SESSION_EXPIRED_MESSAGES = setOf("NO_AUTH", "LOGIN_TIMEOUT")

        /**
         * Duration of the penalty the router applies after a failed login.
         * It is the value the router's own web UI uses (`180 - netx_login_time`).
         */
        private const val LOGIN_PENALTY_SECONDS = 180

        /**
         * Values of `login_fail` that mean "no failure". This firmware uses
         * `"fail"` on a wrong password; the other values are accepted in case
         * another firmware reports the flag in the positive form.
         */
        private val LOGIN_FAILURE_FALSE_VALUES = setOf("success", "ok", "0", "false")

        /** A long hex string: a session id, a token or a password hash. */
        private val HEX_TOKEN_PATTERN = Regex("[0-9a-fA-F]{16,}")
    }
}
