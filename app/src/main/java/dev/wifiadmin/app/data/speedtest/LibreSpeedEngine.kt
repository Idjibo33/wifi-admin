package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.SystemAppClock
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient

/**
 * The real Internet measurement engine (LibreSpeed protocol).
 *
 * Latency (median of [SpeedTestPolicy.latencyProbes] HTTP pings) →
 * download → upload, with real bytes counted in stream and honest
 * [SpeedTestResult.capped] semantics: a cap is a floor, never a precise
 * bitrate. Each phase bitrate is computed over its own stabilised
 * window, so the latency never dilutes the throughput.
 *
 * The network route is resolved **per run** through [routeProvider]
 * (Wi-Fi sockets + DNS, no mobile fallback). A fresh
 * [LibreSpeedClient] is built for every run, so [cancel] can never
 * poison a successor: cleanup after a run closes only that run's
 * calls. No public server is ever contacted by default: this engine
 * only exists when a validated [SpeedTestEndpoint] was configured
 * (see [SpeedTestEndpointValidator]); otherwise
 * [DisabledSpeedTestEngine] is the adapter and no byte leaves the
 * phone.
 */
class LibreSpeedEngine(
    private val endpoint: SpeedTestEndpoint,
    private val baseHttpClient: OkHttpClient,
    private val policy: SpeedTestPolicy = SpeedTestPolicy(),
    private val clock: AppClock = SystemAppClock(),
    private val routeProvider: WifiNetworkRouteProvider? = null,
) : SpeedTestEngine {

    override val providerLabel: String = "LibreSpeed — vers ${endpoint.hostLabel}"

    @Volatile private var cancelled = false
    private val currentClient = AtomicReference<LibreSpeedClient?>(null)

    override suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult {
        try {
            return runMeasurement(onProgress)
        } catch (e: SpeedTestFailureException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SpeedTestFailureException(SpeedTestFailure.Timeout)
        } catch (e: IOException) {
            // OkHttp surfaces a cancelled call as an IOException: when
            // cancel() was the cause, this is a cancellation, not a
            // network failure.
            if (cancelled) throw CancellationException("speed test cancelled")
            throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
        } catch (t: Throwable) {
            throw SpeedTestFailureException(SpeedTestFailure.Unexpected)
        }
    }

    private suspend fun runMeasurement(
        onProgress: suspend (SpeedTestState.Running) -> Unit,
    ): SpeedTestResult {
        // Single-flight is guaranteed by the repository slot: clearing
        // here can only race a cancel() of THIS run, which must land
        // after this line. A stale cancel() of a previous run was
        // already guarded by the repository generation check.
        cancelled = false
        val route = routeProvider?.resolveForRun()
        if (routeProvider != null && route == null) {
            // No Wi-Fi route: fail before any byte is sent.
            throw SpeedTestFailureException(SpeedTestFailure.NoWiFi)
        }
        val runHttpClient = if (route != null) {
            baseHttpClient.newBuilder()
                .socketFactory(route.socketFactory)
                .dns(route.dns)
                .build()
        } else {
            baseHttpClient
        }
        val client = LibreSpeedClient(runHttpClient, endpoint)
        currentClient.set(client)
        if (cancelled) {
            client.cancel()
            throw CancellationException("speed test cancelled")
        }
        try {
            return measure(client, onProgress)
        } finally {
            currentClient.compareAndSet(client, null)
        }
    }

    private suspend fun measure(
        client: LibreSpeedClient,
        onProgress: suspend (SpeedTestState.Running) -> Unit,
    ): SpeedTestResult {
        val startNanos = System.nanoTime()

        // Latency: median of the probes (never min, never max).
        onProgress(SpeedTestState.Running(SpeedTestPhase.LATENCY))
        val probes = mutableListOf<Long>()
        repeat(policy.latencyProbes) {
            currentCoroutineContext().ensureActive()
            probes.add(client.pingMillis())
        }
        val latency = SpeedTestCalculator.medianLatencyMillis(probes)
        val jitter = SpeedTestCalculator.jitterMillis(probes)

        // Download, measured over its own stabilised window.
        onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
        val stats = client.download(policy.maxDownloadBytes) { counted ->
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = counted))
        }
        val downloadBytes = stats.totalBytes
        val downloadMbps = SpeedTestCalculator.mbps(
            stats.measuredBytes,
            maxOf(1L, stats.windowNanos / 1_000_000L),
        )

        // Upload, measured over its own window.
        onProgress(
            SpeedTestState.Running(
                SpeedTestPhase.UPLOAD,
                downloadMbps = downloadMbps,
                downloadBytes = downloadBytes,
            ),
        )
        val ulStart = System.nanoTime()
        val uploadBytes = client.upload(policy.maxUploadBytes) { counted ->
            onProgress(
                SpeedTestState.Running(
                    SpeedTestPhase.UPLOAD,
                    downloadMbps = downloadMbps,
                    downloadBytes = downloadBytes,
                    uploadBytes = counted,
                ),
            )
        }
        val ulMillis = maxOf(1L, (System.nanoTime() - ulStart) / 1_000_000L)
        val uploadMbps = SpeedTestCalculator.mbps(uploadBytes, ulMillis)

        val durationMillis = maxOf(1L, (System.nanoTime() - startNanos) / 1_000_000L)
        return SpeedTestResult(
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            latencyMillis = latency,
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            durationMillis = durationMillis,
            capped = SpeedTestCalculator.isCapped(downloadBytes, uploadBytes, policy),
            providerLabel = providerLabel,
            networkKey = "",
            wifiLink = null,
            capturedAtWallMillis = clock.wallClockMillis(),
            jitterMillis = jitter,
        )
    }

    override fun cancel() {
        cancelled = true
        currentClient.get()?.cancel()
    }
}
