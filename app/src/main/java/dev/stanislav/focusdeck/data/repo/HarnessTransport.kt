package dev.stanislav.focusdeck.data.repo

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class HarnessResponse(val code: Int, val body: String)

/** Host is fixed to loopback; injectable port is used only for isolated tests. */
internal class HarnessTransport(private val port: Int = 48731) {
    fun health(token: String): HarnessResponse = request("GET", "/v1/health", token, null)
    fun post(token: String, body: String): Int = request("POST", "/v1/events", token, body).code

    private fun request(method: String, path: String, token: String, body: String?): HarnessResponse {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val data = body.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            val response = if (method == "GET" && code == 200) connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 16_384)
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            } else ""
            return HarnessResponse(code, response)
        } finally { connection.disconnect() }
    }
}
