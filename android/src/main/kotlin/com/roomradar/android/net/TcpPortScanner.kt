package com.roomradar.android.net

import com.roomradar.core.model.PortProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class TcpPortScanner {

    suspend fun scanHosts(
        hosts: List<String>,
        ports: List<Int>,
        connectTimeoutMs: Int = 650,
        maxConcurrency: Int = 96,
        onHostResult: (ip: String, probes: List<PortProbe>) -> Unit = { _, _ -> },
    ) = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        hosts.map { ip ->
            async {
                semaphore.withPermit {
                    val probes = scanHost(ip, ports, connectTimeoutMs)
                    if (probes.isNotEmpty()) onHostResult(ip, probes)
                }
            }
        }.awaitAll()
    }

    suspend fun scanHost(
        ip: String,
        ports: List<Int>,
        connectTimeoutMs: Int = 650,
    ): List<PortProbe> = coroutineScope {
        ports.distinct().map { port ->
            async(Dispatchers.IO) { probePort(ip, port, connectTimeoutMs) }
        }.awaitAll().filterNotNull()
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): PortProbe? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.soTimeout = READ_TIMEOUT_MS
                val response = readOptionalResponse(socket, ip, port)
                PortProbe(
                    port = port,
                    open = true,
                    protocol = detectProtocol(port, response),
                    banner = sanitize(response),
                )
            }
        } catch (_: Exception) {
            // Connect refused/timeout/unreachable are normal for most hosts/ports.
            null
        }
    }

    private fun readOptionalResponse(socket: Socket, ip: String, port: Int): String? {
        val payload = probePayload(ip, port)
        return try {
            val output = socket.getOutputStream()
            if (payload != null) {
                output.write(payload.toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            val read = input.read(buffer)
            if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else null
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun probePayload(ip: String, port: Int): String? = when (port) {
        554, 8554 -> buildString {
            append("OPTIONS rtsp://").append(ip).append(':').append(port).append("/ RTSP/1.0\r\n")
            append("CSeq: 1\r\n")
            append("User-Agent: RoomRadar/0.1\r\n")
            append("\r\n")
        }

        in HTTP_PORTS -> buildString {
            append("GET / HTTP/1.0\r\n")
            append("Host: ").append(ip).append("\r\n")
            append("User-Agent: RoomRadar/0.1 (safety scan; non-intrusive)\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        else -> null
    }

    private fun detectProtocol(port: Int, response: String?): String? {
        if (response != null) {
            val firstLine = response.lineSequence().firstOrNull().orEmpty()
            when {
                firstLine.startsWith("RTSP/", ignoreCase = true) -> return "RTSP"
                firstLine.startsWith("HTTP/", ignoreCase = true) &&
                    response.contains("RTSP", ignoreCase = true) -> return "RTSP"
            }
        }
        return when (port) {
            554, 8554 -> "RTSP"
            1935 -> "RTMP"
            37777 -> "DAHUA_SDK"
            34567 -> "XMEYE"
            8000 -> "HIKVISION_SDK"
            in HTTP_PORTS -> "HTTP"
            else -> null
        }
    }

    private fun sanitize(response: String?): String? {
        if (response.isNullOrBlank()) return null
        return response
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
            .ifBlank { null }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 450

        val HTTP_PORTS = setOf(80, 81, 82, 443, 800, 3000, 5000, 8080, 8081, 8082, 8443, 8888)

        /** Fast first stage: common live-host and camera-service ports. */
        val DISCOVERY_PORTS = listOf(
            80, 443, 554, 1935, 8000, 8080, 8443, 8554,
            8899, 37777, 34567,
        )

        /** Second stage for hosts already known to be online. */
        val EXTENDED_CAMERA_PORTS = listOf(
            81, 82, 800, 3000, 5000, 8081, 8082, 8888,
            37778, 37779, 34599, 8800, 8899, 8101, 8200,
        )
    }
}
