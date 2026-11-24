package com.roomradar.android.net

import com.roomradar.core.model.DiscoverySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

data class UdpDiscoveryResult(
    val ip: String,
    val source: DiscoverySource,
    val serviceType: String?,
    val location: String? = null,
    val server: String? = null,
    val name: String? = null,
    val endpoint: String? = null,
)

class UdpDiscoveryClient {

    suspend fun discoverAll(timeoutMs: Long = 3_500): List<UdpDiscoveryResult> {
        val ssdp = runCatching { ssdpSearch(timeoutMs) }.getOrDefault(emptyList())
        val onvif = runCatching { onvifProbe(timeoutMs) }.getOrDefault(emptyList())
        return ssdp + onvif
    }

    suspend fun ssdpSearch(totalTimeoutMs: Long = 2_500): List<UdpDiscoveryResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<UdpDiscoveryResult>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val address = InetAddress.getByName(SSDP_HOST)
                val request = """
                    M-SEARCH * HTTP/1.1
                    HOST: $SSDP_HOST:$SSDP_PORT
                    MAN: "ssdp:discover"
                    MX: 2
                    ST: ssdp:all
                    USER-AGENT: RoomRadar/0.1 Android

                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                send(socket, request, address, SSDP_PORT)

                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + totalTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val headers = parseHeaders(text)
                    results += UdpDiscoveryResult(
                        ip = packet.address.hostAddress ?: continue,
                        source = DiscoverySource.SSDP,
                        serviceType = headers["ST"] ?: headers["NT"],
                        location = headers["LOCATION"],
                        server = headers["SERVER"],
                        name = headers["USN"],
                    )
                }
            }
            results.distinctBy { it.ip + it.location + it.serviceType }
        }

    suspend fun onvifProbe(totalTimeoutMs: Long = 2_000): List<UdpDiscoveryResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<UdpDiscoveryResult>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val address = InetAddress.getByName(ONVIF_HOST)
                val messageId = "uuid:${UUID.randomUUID()}"
                val probe = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                                xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                                xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                                xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
                      <s:Header>
                        <a:MessageID>$messageId</a:MessageID>
                        <a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
                        <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
                        <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
                      </s:Header>
                      <s:Body>
                        <d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()
                send(socket, probe, address, ONVIF_PORT)

                val buffer = ByteArray(8192)
                val deadline = System.currentTimeMillis() + totalTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (!text.contains("ProbeMatches", ignoreCase = true)) continue
                    results += UdpDiscoveryResult(
                        ip = packet.address.hostAddress ?: continue,
                        source = DiscoverySource.ONVIF,
                        serviceType = "_onvif._tcp",
                        endpoint = extractXmlTag(text, "XAddrs"),
                        name = extractXmlTag(text, "Name"),
                        server = extractXmlTag(text, "Hardware"),
                    )
                }
            }
            results.distinctBy { it.ip + it.endpoint }
        }

    private fun send(
        socket: DatagramSocket,
        text: String,
        address: InetAddress,
        port: Int,
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    private fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { ':' in it }
            .associate { line ->
                val index = line.indexOf(':')
                line.substring(0, index).trim().uppercase() to
                    line.substring(index + 1).trim()
            }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = Regex("<(?:[A-Za-z0-9_]+:)?$tag[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.take(240)
    }

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val ONVIF_HOST = "239.255.255.250"
        private const val ONVIF_PORT = 3702
    }
}
