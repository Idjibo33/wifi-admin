package dev.wifiadmin.router

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport must never follow a redirection.
 *
 * The login request carries the password hash and the session id. A `307`
 * preserves the body, so following it would hand those secrets to whatever
 * host the reply names — a host the router, or anyone impersonating it on
 * the local network, chooses.
 */
class OkHttpRouterRedirectTest {

    @Test
    fun `a redirect is not followed and the body is not replayed`() {
        val target = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val targetPort = target.localPort
        val received = AtomicInteger(0)

        val targetServer = Thread {
            try {
                val socket = target.accept()
                socket.getInputStream().readBytes()
                received.incrementAndGet()
                socket.close()
            } catch (e: Exception) {
                // The test only needs the attempt to be recorded.
            }
        }
        targetServer.start()

        // The first server answers with a 307 pointing at the second one.
        val redirector = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val redirectorPort = redirector.localPort
        val redirectorThread = Thread {
            try {
                val socket = redirector.accept()
                // Read the request headers and the body, so the client sees a
                // complete exchange.
                val input = socket.getInputStream()
                val reader = input.bufferedReader()
                val headers = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    headers.append(line).append("\n")
                    if (line.isEmpty()) break
                }
                val length = headers.toString()
                    .lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull() ?: 0
                if (length > 0) {
                    val body = CharArray(length)
                    reader.read(body, 0, length)
                }
                val response = "HTTP/1.1 307 Temporary Redirect\r\n" +
                    "Location: http://127.0.0.1:$targetPort/cgi-bin/http.cgi\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                val out = socket.getOutputStream()
                out.write(response.toByteArray())
                out.flush()
                socket.close()
            } catch (e: Exception) {
                // The assertion inside matters more than the exception.
            }
        }
        redirectorThread.start()

        try {
            val transport = OkHttpRouterTransport()
            val body = transport.post(
                "http://127.0.0.1:$redirectorPort/cgi-bin/http.cgi",
                """{"cmd":100,"method":"POST","passwd":"a1b2c3d4e5f60718"}""",
                4,
            )

            // The redirection must not be followed: the reply is a failure,
            // not the JSON the target would have sent.
            assertTrue(
                "the redirection must be reported as an HTTP error, got: $body",
                body.contains("HTTP 307"),
            )
            assertEquals(
                "the body must not be replayed to the redirect target",
                0,
                received.get(),
            )
        } finally {
            redirector.close()
            target.close()
        }
    }
}
