package br.com.timasters.app.data.routers.config

import br.com.timasters.app.core.network.HostValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Where and how to reach the router's management API on the local network. */
data class RouterEndpoint(val host: String, val https: Boolean = false, val port: Int = if (https) 443 else 80)

class LanHttpException(val status: Int, message: String) : IOException(message)

/**
 * Minimal HTTP/1.1 client for router APIs (MikroTik REST, OpenWrt ubus) on private addresses only.
 * Plain sockets keep clear-text HTTP disabled for the rest of the app. HTTPS accepts the
 * self-signed certificates routers ship with, which is only acceptable because the host must be a
 * private LAN address.
 */
object LanHttpClient {

    /** [complete] is false when the connection ended before the declared Content-Length / last chunk. */
    data class Response(val status: Int, val body: String, val complete: Boolean = true)

    suspend fun request(
        endpoint: RouterEndpoint,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        binary: ByteArray? = null,
        contentType: String = "application/json"
    ): Response {
        val bytes = binary ?: body?.toByteArray(Charsets.UTF_8)
        val first = send(endpoint, method, path, headers, bytes, contentType)
        // Reads are safe to repeat when a response arrives cut short.
        return if (!first.complete && method == "GET") send(endpoint, method, path, headers, bytes, contentType) else first
    }

    private suspend fun send(
        endpoint: RouterEndpoint,
        method: String,
        path: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        contentType: String
    ): Response = withContext(Dispatchers.IO) {
        require(HostValidator.isPrivateIpv4(endpoint.host)) { "Only private LAN addresses are allowed" }
        openSocket(endpoint).use { socket ->
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${endpoint.host}\r\n")
                append("User-Agent: TIMasters-Android\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                if (bodyBytes != null) {
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                bodyBytes?.let { write(it) }
                flush()
            }
            val raw = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            var closedByServer = false
            try {
                // Stop as soon as the response is complete instead of waiting for the router to close.
                while (raw.size() < MAX_BYTES && parse(raw.toByteArray())?.complete != true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        closedByServer = true
                        break
                    }
                    raw.write(buffer, 0, read)
                }
            } catch (_: SocketTimeoutException) {
                // Use what arrived; the parser rejects incomplete heads.
            }
            val response = parse(raw.toByteArray()) ?: throw IOException("Invalid HTTP response")
            // Without Content-Length or chunks, the end of the connection is the end of the body.
            if (closedByServer && !response.complete && !hasDeclaredLength(raw.toByteArray())) response.copy(complete = true) else response
        }
    }

    private fun hasDeclaredLength(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.ISO_8859_1)
        val head = text.substring(0, text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.length).lowercase()
        return "content-length:" in head || "chunked" in head
    }

    /** Status line + headers + body, decoding chunked transfer encoding. */
    fun parse(bytes: ByteArray): Response? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: return null
        val head = text.substring(0, split).split("\r\n")
        val status = head.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = head.drop(1)
        val chunked = headers.any { it.startsWith("transfer-encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }
        val length = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull()
        val rawBody = bytes.copyOfRange(split + 4, bytes.size)
        val complete = when {
            chunked -> String(rawBody, Charsets.ISO_8859_1).let { it.startsWith("0\r\n") || it.contains("\r\n0\r\n") }
            length != null -> rawBody.size >= length
            else -> status == 204 || status == 304
        }
        val bodyBytes = if (chunked) dechunk(rawBody) else if (length != null && rawBody.size > length) rawBody.copyOf(length) else rawBody
        return Response(status, String(bodyBytes, Charsets.UTF_8), complete)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < data.size) {
            val lineEnd = indexOf(data, index) ?: break
            val size = String(data, index, lineEnd - index, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            val end = minOf(start + size, data.size)
            out.write(data, start, end - start)
            index = end + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, from: Int): Int? {
        for (i in from until data.size - 1) if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte()) return i
        return null
    }

    private fun openSocket(endpoint: RouterEndpoint): Socket {
        val plain = Socket().apply {
            connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS)
            soTimeout = TIMEOUT_MS
        }
        if (!endpoint.https) return plain
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(LanTrustManager), SecureRandom()) }
        return (context.socketFactory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket).apply {
            soTimeout = TIMEOUT_MS
            startHandshake()
        }
    }

    /** Routers use self-signed certificates; only used for private LAN hosts (see [request]). */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object LanTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 2 * 1024 * 1024
}
