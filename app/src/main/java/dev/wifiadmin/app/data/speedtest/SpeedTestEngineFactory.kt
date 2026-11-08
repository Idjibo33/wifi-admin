package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.SystemAppClock
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Chooses the speed test adapter from the configured endpoint.
 *
 * A blank or invalid endpoint means [DisabledSpeedTestEngine]: the
 * production application never contacts a server it was not
 * explicitly configured with. Pure JVM so it is unit-testable.
 */
object SpeedTestEngineFactory {

    fun create(
        rawBaseUrl: String,
        policy: SpeedTestPolicy = SpeedTestPolicy(),
        clock: AppClock = SystemAppClock(),
    ): SpeedTestEngine {
        val endpoint = SpeedTestEndpointValidator.parse(rawBaseUrl)
            ?: return DisabledSpeedTestEngine()
        val httpClient = OkHttpClient.Builder()
            .callTimeout(policy.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(policy.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(policy.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(policy.writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return LibreSpeedEngine(
            endpoint = endpoint,
            client = LibreSpeedClient(httpClient, endpoint),
            policy = policy,
            clock = clock,
        )
    }
}
