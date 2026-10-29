package dev.wifiadmin.router

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Exercises [OkHttpRouterTransport] against a local loopback server.
 *
 * No external network is used: the server socket is opened on 127.0.0.1 with an
 * ephemeral port.
 */
class OkHttpRouterTransportTest {

    @Test
    fun `posts a json body and returns the reply`() {
        val server = LoopbackServer { body ->
            assertEquals("{\"cmd\":232}", body)
            "{\"success\":true,\"token\":\"T\"}"
        }
        server.start()

        val transport = OkHttpRouterTransport()
        val reply = transport.post(server.url(), "{\"cmd\":232}", 4)

        assertEquals("{\"success\":true,\"token\":\"T\"}", reply)
        server.stop()
    }

    @Test
    fun `turns a non 2xx status into a structured error`() {
        val server = LoopbackServer(status = 500) { "not json" }
        server.start()

        val transport = OkHttpRouterTransport()
        val reply = transport.post(server.url(), "{}", 4)

        assertEquals(true, reply.contains("\"error\""))
        assertEquals(true, reply.contains("HTTP 500"))
        server.stop()
    }

    @Test
    fun `a refused connection raises an io exception`() {
        val transport = OkHttpRouterTransport()
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()

        try {
            transport.post("http://127.0.0.1:$port/cgi-bin/http.cgi", "{}", 2)
            throw AssertionError("an IOException was expected")
        } catch (e: IOException) {
            // expected
        }
    }

    /** A one-shot HTTP server on the loopback interface. */
    private class LoopbackServer(
        private val status: Int = 200,
        private val handler: (String) -> String
    ) {
        private lateinit var serverSocket: ServerSocket
        private val executor = Executors.newSingleThreadExecutor()

        fun start() {
            serverSocket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            executor.submit {
                try {
                    val socket: Socket = serverSocket.accept()
                    socket.getInputStream().bufferedReader().let { reader ->
                        var line = reader.readLine()
                        var contentLength = 0
                        while (line != null && line.isNotEmpty()) {
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toInt()
                            }
                            line = reader.readLine()
                        }
                        val body = CharArray(contentLength)
                        reader.read(body, 0, contentLength)
                        val reply = handler(String(body))
                        val response = reply.toByteArray()
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            write(response)
                            flush()
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    // the test asserts on the client side
                }
            }
        }

        fun url(): String = "http://127.0.0.1:${serverSocket.localPort}/cgi-bin/http.cgi"

        fun stop() {
            try {
                serverSocket.close()
            } catch (e: IOException) {
                // ignored
            }
            executor.shutdownNow()
        }
    }
}
