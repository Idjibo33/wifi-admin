package dev.wifiadmin.app.data.speedtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random as KotlinRandom

/**
 * Measured window of a download phase.
 *
 * [totalBytes] is everything counted on the wire (warm-up included):
 * it feeds the cap check and the displayed consumption. [measuredBytes]
 * and [windowNanos] describe the stabilised window used for the
 * bitrate; when the transfer is smaller than the warm-up, the whole
 * phase is the window (fallback, never a zero rate).
 */
data class DownloadStats(
    val totalBytes: Long,
    val measuredBytes: Long,
    val windowNanos: Long,
)

/**
 * Low-level HTTP for the LibreSpeed protocol.
 *
 * Only this module opens Internet sockets. It never calls `getIP.php`
 * (that would leak the public IP to the server logs for no measure) and
 * never calls `telemetry.php` (that would send anything at all). The
 * `empty.php` endpoint serves both ping (GET) and upload (POST);
 * `garbage.php` serves the download body.
 *
 * One instance is one run: the engine builds a fresh client per run so
 * a [cancel] can never poison a successor. Calls are blocking OkHttp
 * executions run on the repository's IO dispatcher. Coroutine
 * cancellation is honoured by [cancel] (which closes every in-flight
 * call) plus [ensureActive] between buffers.
 */
class LibreSpeedClient(
    private val httpClient: OkHttpClient,
    private val endpoint: SpeedTestEndpoint,
) {
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false

    /** Idempotent, thread-safe: closes every in-flight call. */
    fun cancel() {
        cancelled = true
        activeCalls.forEach { it.cancel() }
    }

    /** True once [cancel] has been called, including mid-run. */
    fun isCancelled(): Boolean = cancelled

    private suspend fun requireActive() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw CancellationException("speed test cancelled")
    }

    private fun newCall(request: Request): Call {
        val call = httpClient.newCall(request)
        activeCalls.add(call)
        if (cancelled) call.cancel()
        return call
    }

    private fun release(call: Call) {
        activeCalls.remove(call)
    }

    /** One HTTP ping: GET on `empty.php`, returns the round-trip time. */
    suspend fun pingMillis(): Long {
        requireActive()
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/empty.php?r=${KotlinRandom.nextInt(1_000_000)}")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val call = newCall(request)
        return try {
            val started = System.nanoTime()
            call.execute().use { response ->
                if (response.code != 200) {
                    throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                }
                // The response body is closed by use{}. The body is
                // empty by protocol; no need to drain it.
            }
            maxOf(1L, (System.nanoTime() - started) / 1_000_000L)
        } finally {
            release(call)
        }
    }

    /**
     * Streams `garbage.php` responses until [maxBytes] is reached or
     * [maxDurationMillis] expires, whichever comes first. Real bytes only.
     *
     * Each request asks for up to 20 MiB (`ckSize`); the loop stops
     * reading as soon as the byte cap or the time budget is exhausted,
     * so a slow link runs for the full duration instead of timing out
     * and a fast link stops at the safety cap.
     *
     * The first [WARMUP_BYTES] are stabilisation warm-up: counted in
     * [DownloadStats.totalBytes] (cap and consumption) but excluded
     * from the measurement window. An empty 200 body is not a zero rate:
     * it is [SpeedTestFailure.Incomplete].
     */
    suspend fun download(
        maxBytes: Long,
        maxDurationMillis: Long,
        onProgress: suspend (downloadedBytes: Long) -> Unit,
    ): DownloadStats {
        requireActive()
        var counted = 0L
        var windowStartNanos: Long? = null
        val phaseStart = System.nanoTime()
        val deadlineNanos = phaseStart + maxDurationMillis * 1_000_000L
        var emptyResponses = 0
        try {
            while (counted < maxBytes && System.nanoTime() < deadlineNanos && emptyResponses < 2) {
                requireActive()
                val remaining = maxBytes - counted
                val ckSize = ((remaining + (1L shl 20) - 1) / (1L shl 20)).toInt().coerceIn(1, 20)
                val request = Request.Builder()
                    .url("${endpoint.baseUrl}/garbage.php?ckSize=$ckSize&r=${KotlinRandom.nextInt(1_000_000)}")
                    .header("Accept-Encoding", "identity")
                    .get()
                    .build()
                val call = newCall(request)
                try {
                    call.execute().use { response ->
                        if (response.code != 200) {
                            throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                        }
                        val source = response.body?.source()
                            ?: throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                        val buffer = ByteArray(65_536)
                        var requestBytes = 0L
                        while (counted < maxBytes && System.nanoTime() < deadlineNanos) {
                            requireActive()
                            val wanted = minOf(buffer.size.toLong(), maxBytes - counted).toInt()
                            val read = source.read(buffer, 0, wanted)
                            if (read == -1) break
                            counted += read
                            requestBytes += read
                            if (counted > WARMUP_BYTES && windowStartNanos == null) {
                                windowStartNanos = System.nanoTime()
                            }
                            onProgress(counted)
                        }
                        if (requestBytes == 0L) emptyResponses++ else emptyResponses = 0
                    }
                } finally {
                    release(call)
                }
            }
        } catch (e: IOException) {
            if (cancelled) throw CancellationException("speed test cancelled")
            throw e
        }
        if (counted == 0L) {
            throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
        }
        val windowNanos = (windowStartNanos ?: phaseStart).let { start ->
            maxOf(1L, System.nanoTime() - start)
        }
        val measured = if (counted > WARMUP_BYTES) counted - WARMUP_BYTES else counted
        return DownloadStats(
            totalBytes = counted,
            measuredBytes = measured,
            windowNanos = windowNanos,
        )
    }

    /**
     * Uploads until [maxBytes] is reached or [maxDurationMillis] expires,
     * whichever comes first. Uses chunked transfer so the actual volume
     * can stop at the duration boundary without a protocol mismatch.
     *
     * Returns the count of bytes really written to the application stream.
     */
    suspend fun upload(
        maxBytes: Long,
        maxDurationMillis: Long,
        onProgress: suspend (uploadedBytes: Long) -> Unit,
    ): Long {
        requireActive()
        val written = AtomicLong(0)
        val random = Random(UPLOAD_SEED)
        val phaseStart = System.nanoTime()
        val deadlineNanos = phaseStart + maxDurationMillis * 1_000_000L
        val body = object : RequestBody() {
            override fun contentLength(): Long = -1  // chunked
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(65_536)
                var remaining = maxBytes
                while (remaining > 0 && System.nanoTime() < deadlineNanos) {
                    if (cancelled) throw IOException("cancelled")
                    random.nextBytes(chunk)
                    val n = minOf(chunk.size.toLong(), remaining).toInt()
                    sink.write(chunk, 0, n)
                    written.addAndGet(n.toLong())
                    remaining -= n
                }
                sink.flush()
            }
        }
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/empty.php?r=${KotlinRandom.nextInt(1_000_000)}")
            .header("Accept-Encoding", "identity")
            .header("Content-Encoding", "identity")
            .post(body)
            .build()
        val call = newCall(request)
        try {
            call.execute().use { response ->
                if (response.code != 200) {
                    throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                }
            }
        } catch (e: IOException) {
            if (cancelled) throw CancellationException("speed test cancelled")
            throw e
        } finally {
            release(call)
        }
        val total = written.get()
        if (total == 0L) {
            throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
        }
        onProgress(total)
        return total
    }

    companion object {
        /** Warm-up bytes stabilising the TCP window before measuring. */
        internal const val WARMUP_BYTES = 256L * 1024

        /** Deterministic upload seed: reproducible, incompressible. */
        internal const val UPLOAD_SEED = 0x5EEDL
    }
}
