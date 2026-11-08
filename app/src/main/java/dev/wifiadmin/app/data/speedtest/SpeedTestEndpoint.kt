package dev.wifiadmin.app.data.speedtest

import java.net.URI

/**
 * An endpoint LibreSpeed validated before any byte leaves the phone.
 *
 * The raw configured string never crosses this seam again after
 * [SpeedTestEndpointValidator.parse]: only [baseUrl] (no credentials, no
 * query, no fragment) and [hostLabel] (host only, for display) survive.
 * No secret is logged: a rejected string is simply dropped.
 */
data class SpeedTestEndpoint(
    val baseUrl: String,
    val hostLabel: String,
)

object SpeedTestEndpointValidator {

    private val MAX_LENGTH = 512
    private const val MAX_PATH_LENGTH = 256
    private val SECRET_HINTS = listOf("password=", "token=", "apikey=", "api_key=", "authorization", "bearer")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]", "localhost")

    /**
     * Pure JVM, no Android, no network. Returns null when the endpoint
     * is absent or invalid; the caller then disables the engine.
     */
    fun parse(raw: String): SpeedTestEndpoint? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        if (value.any { it <= ' ' || it == '@' }) return null
        val lower = value.lowercase()
        if (SECRET_HINTS.any { lower.contains(it) }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.userInfo != null) return null
        if (uri.fragment != null) return null
        if (!uri.query.isNullOrEmpty()) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val isLoopback = host.lowercase() in LOOPBACK_HOSTS
        if (scheme == "http" && !isLoopback) return null
        val path = uri.path ?: ""
        if (path.length > MAX_PATH_LENGTH || path.contains("..")) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        // Re-parse check: the host must survive its own normalisation,
        // which defeats tricks with embedded credentials in the authority.
        val authority = if (port != -1) "$host:$port" else host
        val baseUrl = "$scheme://$authority" + path.trimEnd('/')
        val reparsed = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (!host.equals(reparsed.host, ignoreCase = true)) return null
        return SpeedTestEndpoint(baseUrl = baseUrl, hostLabel = host)
    }
}
