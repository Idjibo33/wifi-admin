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
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Low-level HTTP for the LibreSpeed protocol.
 *
 * Only this module opens Internet sockets. It never calls `getIP.php`
 * (that would leak the public IP to the server logs for no measure) and
 * never calls `telemetry.php` (that would send anything at all). The
 * `empty.php` endpoint serves both ping (GET) and upload (POST);
 * `garbage.php` serves the download body.
 *
 * Calls are blocking OkHttp executions run on the repository's IO
 * dispatcher. Coroutine cancellation is honoured by [cancel] (which
 * closes every in-flight call) plus [ensureActive] between requests.
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
            .url("${endpoint.baseUrl}/empty.php?r=${Random.nextInt(1_000_000)}")
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
     * Streams the download body, counting real bytes (never trusting a
     * `Content-Length`). Stops at [maxBytes] and closes the stream.
     * One `garbage.php` response is the whole download phase.
     */
    suspend fun download(
        maxBytes: Long,
        onProgress: suspend (downloadedBytes: Long) -> Unit,
    ): Long {
        requireActive()
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/garbage.php?ckSize=1&r=${Random.nextInt(1_000_000)}")
            .get()
            .build()
        val call = newCall(request)
        var counted = 0L
        try {
            call.execute().use { response ->
                if (response.code != 200) {
                    throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                }
                // NOTE: source() is a blocking BufferedSource. Calling
                // .buffer() would return the raw in-memory buffer and
                // read 0 bytes without ever hitting the socket.
                val source = response.body?.source()
                    ?: throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                val buffer = ByteArray(65_536)
                while (counted < maxBytes) {
                    requireActive()
                    val wanted = minOf(buffer.size.toLong(), maxBytes - counted).toInt()
                    val read = source.read(buffer, 0, wanted)
                    if (read == -1) break
                    counted += read
                    onProgress(counted)
                }
            }
        } finally {
            release(call)
        }
        return counted
    }

    /**
     * Streams [maxBytes] of zeros as the upload body and returns the
     * count once the server answered 200. The body length is exactly
     * the cap, so no byte beyond the policy can leave the phone.
     */
    suspend fun upload(
        maxBytes: Long,
        onProgress: suspend (uploadedBytes: Long) -> Unit,
    ): Long {
        requireActive()
        val body = object : RequestBody() {
            override fun contentLength(): Long = maxBytes
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(65_536)
                var remaining = maxBytes
                while (remaining > 0) {
                    if (cancelled) throw java.io.IOException("cancelled")
                    val n = minOf(chunk.size.toLong(), remaining).toInt()
                    sink.write(chunk, 0, n)
                    remaining -= n
                }
                sink.flush()
            }
        }
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/empty.php?r=${Random.nextInt(1_000_000)}")
            .post(body)
            .build()
        val call = newCall(request)
        try {
            call.execute().use { response ->
                if (response.code != 200) {
                    throw SpeedTestFailureException(SpeedTestFailure.ServerUnavailable)
                }
            }
        } finally {
            release(call)
        }
        onProgress(maxBytes)
        return maxBytes
    }
}
