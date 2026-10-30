package dev.wifiadmin.router

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [RouterTransport] implementation backed by OkHttp.
 *
 * The transport never logs request bodies: they carry the password hash, the
 * challenge token and the session id.
 */
class OkHttpRouterTransport(
    private val defaultTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : RouterTransport {

    private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
        // Off: OkHttp would silently repeat a POST after a connection failure.
        // The router may already have acted on it.
        .retryOnConnectionFailure(false)
        // Off: a redirection would hand the request body — which carries the
        // password hash, the challenge token and the session id — to
        // whatever host the reply names. The router's protocol has no
        // legitimate use for a redirection, so any of them is a failure.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", USER_AGENT)
            .build()

        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Turn the HTTP status into a structured failure the caller can read.
                return JSONObject()
                    .put("success", false)
                    .put("error", "HTTP ${response.code}")
                    .toString()
            }
            return response.body?.string() ?: "{}"
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 4L

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZLT-WifiAdmin/1.0"
    }
}
