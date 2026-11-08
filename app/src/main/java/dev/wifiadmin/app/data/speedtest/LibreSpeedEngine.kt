package dev.wifiadmin.app.data.speedtest

import dev.wifiadmin.app.data.AppClock
import dev.wifiadmin.app.data.SystemAppClock
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The real Internet measurement engine (LibreSpeed protocol).
 *
 * Latency (median of [SpeedTestPolicy.latencyProbes] HTTP pings) →
 * download → upload, with real bytes counted in stream and honest
 * [SpeedTestResult.capped] semantics: a cap is a floor, never a precise
 * bitrate. The bitrate of each phase is computed over its own duration,
 * so the latency never dilutes the throughput.
 *
 * No public server is ever contacted by default: this engine only exists
 * when a validated [SpeedTestEndpoint] was configured (see
 * [SpeedTestEndpointValidator]); otherwise [DisabledSpeedTestEngine] is
 * the adapter and no byte leaves the phone.
 */
class LibreSpeedEngine(
    private val endpoint: SpeedTestEndpoint,
    private val client: LibreSpeedClient,
    private val policy: SpeedTestPolicy = SpeedTestPolicy(),
    private val clock: AppClock = SystemAppClock(),
) : SpeedTestEngine {

    override val providerLabel: String = "LibreSpeed — vers ${endpoint.hostLabel}"

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
            if (client.isCancelled()) throw CancellationException("speed test cancelled")
            throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
        } catch (t: Throwable) {
            throw SpeedTestFailureException(SpeedTestFailure.Unexpected)
        }
    }

    private suspend fun runMeasurement(
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

        // Download, measured over its own window.
        onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD))
        val dlStart = System.nanoTime()
        val downloadBytes = client.download(policy.maxDownloadBytes) { counted ->
            onProgress(SpeedTestState.Running(SpeedTestPhase.DOWNLOAD, downloadBytes = counted))
        }
        val dlMillis = maxOf(1L, (System.nanoTime() - dlStart) / 1_000_000L)
        val downloadMbps = SpeedTestCalculator.mbps(downloadBytes, dlMillis)

        // Upload, measured over its own window.
        onProgress(SpeedTestState.Running(SpeedTestPhase.UPLOAD, downloadMbps = downloadMbps, downloadBytes = downloadBytes))
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
        )
    }

    override fun cancel() {
        client.cancel()
    }
}
