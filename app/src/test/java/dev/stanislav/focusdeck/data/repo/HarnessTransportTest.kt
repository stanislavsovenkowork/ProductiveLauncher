package dev.stanislav.focusdeck.data.repo

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTransportTest {
    private fun capture(response: Int, action: (HarnessTransport) -> Int): String {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val received = ArrayBlockingQueue<String>(1)
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0)
                        header.append(next.toChar())
                    }
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = input.readNBytes(length).toString(StandardCharsets.UTF_8)
                    received.put(header.toString() + body)
                    socket.getOutputStream().write("HTTP/1.1 $response Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            assertEquals(response, action(HarnessTransport(server.localPort)))
            val request = requireNotNull(received.poll(5, TimeUnit.SECONDS))
            worker.join(5000)
            return request
        }
    }

    @Test fun sendsAuthenticatedJsonToLoopbackAndSurfacesConflict() {
        val body = "{\"schemaVersion\":1,\"events\":[]}"
        val request = capture(202) { it.post("test-token", body) }
        assertTrue(request.startsWith("POST /v1/events HTTP/1.1"))
        assertTrue(request.contains("Authorization: Bearer test-token", ignoreCase = true))
        assertTrue(request.contains("Content-Type: application/json", ignoreCase = true))
        assertTrue(request.endsWith(body))
        capture(409) { it.post("test-token", body) }
    }

    @Test fun redirectsNeverForwardTheToken() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { destination ->
            destination.soTimeout = 600
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { source ->
                val worker = thread(isDaemon = true) {
                    source.accept().use { socket ->
                        val input = socket.getInputStream(); var suffix = ""
                        while (!suffix.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); suffix = (suffix + b.toChar()).takeLast(4) }
                        val response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${destination.localPort}/leak\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
                assertEquals(302, HarnessTransport(source.localPort).health("test-token").code)
                val reached = try { destination.accept().close(); true } catch (_: java.net.SocketTimeoutException) { false }
                assertTrue(!reached)
                worker.join(5000)
            }
        }
    }

    @Test fun healthRequestIsAuthenticated() {
        val request = capture(200) { it.health("test-token").code }
        assertTrue(request.startsWith("GET /v1/health HTTP/1.1"))
        assertTrue(request.contains("Authorization: Bearer test-token", ignoreCase = true))
    }
}
