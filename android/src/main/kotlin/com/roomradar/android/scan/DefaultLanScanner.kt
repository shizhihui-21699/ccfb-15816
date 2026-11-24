package com.roomradar.android.scan

import android.content.Context
import com.roomradar.android.net.AndroidNetworkInfoProvider
import com.roomradar.android.net.NeighborTableReader
import com.roomradar.android.net.NsdServiceScanner
import com.roomradar.android.net.TcpPortScanner
import com.roomradar.android.net.UdpDiscoveryClient
import com.roomradar.core.model.DiscoverySource
import com.roomradar.core.model.LanDevice
import com.roomradar.core.model.NetworkSnapshot
import com.roomradar.core.model.PortProbe
import com.roomradar.core.model.ServiceFingerprint
import com.roomradar.core.net.IPv4Cidr
import com.roomradar.core.net.parseIPv4OrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class ScanProgress(
    val stage: String,
    val completed: Int = 0,
    val total: Int = 0,
    val discoveredDevices: Int = 0,
)

data class ScanOutcome(
    val network: NetworkSnapshot?,
    val devices: List<LanDevice>,
    val warnings: List<String>,
)

class DefaultLanScanner(
    context: Context,
    private val networkProvider: AndroidNetworkInfoProvider = AndroidNetworkInfoProvider(context),
    private val tcpScanner: TcpPortScanner = TcpPortScanner(),
    private val nsdScanner: NsdServiceScanner = NsdServiceScanner(context),
    private val udpClient: UdpDiscoveryClient = UdpDiscoveryClient(),
    private val neighborReader: NeighborTableReader = NeighborTableReader(),
) {
    suspend fun scan(
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanOutcome {
        val warnings = mutableListOf<String>()
        val table = DeviceTable()

        onProgress(ScanProgress("读取当前 Wi-Fi/子网信息"))
        val network = networkProvider.current()
        if (network?.ownIpv4 == null) {
            warnings += "未能读取当前 IPv4 地址；请确认已连接 Wi-Fi。已使用 192.168.0.0/24 演示范围。"
        }
        if (network?.identityVisible == false) {
            warnings += "未获得精确位置/附近 Wi-Fi 授权，SSID/BSSID 可能不可见；不影响基于 IP 的扫描。"
        }

        val cidr = network?.ownIpv4
            ?.takeIf { parseIPv4OrNull(it) != null }
            ?.let { IPv4Cidr.local24(it) }
            ?: IPv4Cidr.local24("192.168.0.1")
        val hosts = cidr.hosts().toList()

        withContext(Dispatchers.IO) { neighborReader.read() }.forEach { entry ->
            if (parseIPv4OrNull(entry.ip) != null) {
                table.putMac(entry.ip, entry.mac)
                table.addSource(entry.ip, DiscoverySource.NEIGHBOR_TABLE)
            }
        }

        onProgress(ScanProgress("启动 mDNS/NSD 服务发现", total = hosts.size))
        nsdScanner.start { found ->
            val ip = found.ip?.substringBefore('%')
            if (ip != null && parseIPv4OrNull(ip) != null) {
                table.addService(ip, found.fingerprint)
                table.addSource(ip, DiscoverySource.MDNS)
                table.addSource(ip, DiscoverySource.NSD)
            }
        }
        delay(NSD_WINDOW_MS)
        nsdScanner.stop()

        onProgress(ScanProgress("发送 SSDP/ONVIF 组播探测"))
        udpClient.discoverAll().forEach { result ->
            table.addSource(result.ip, result.source)
            when (result.source) {
                DiscoverySource.ONVIF -> table.addService(
                    result.ip,
                    ServiceFingerprint(
                        type = result.serviceType ?: "_onvif._tcp",
                        name = result.name,
                        manufacturer = result.server,
                        endpoint = result.endpoint,
                    ),
                )

                else -> table.addService(
                    result.ip,
                    ServiceFingerprint(
                        type = result.serviceType ?: "ssdp:device",
                        name = result.name,
                        endpoint = result.location,
                        manufacturer = result.server,
                    ),
                )
            }
            result.server?.takeIf { it.isNotBlank() }?.let {
                table.putMetadata(result.ip, "SERVER", it.take(160))
            }
        }

        onProgress(ScanProgress("TCP 常见摄像头端口探测", completed = 0, total = hosts.size))
        tcpScanner.scanHosts(
            hosts = hosts,
            ports = TcpPortScanner.DISCOVERY_PORTS,
        ) { ip, probes ->
            table.addPorts(ip, probes)
            table.addSource(ip, DiscoverySource.TCP_PORT_SCAN)
            onProgress(
                ScanProgress(
                    stage = "TCP 常见摄像头端口探测",
                    discoveredDevices = table.ipCount(),
                ),
            )
        }

        val liveHosts = table.ips().filter { it in hosts }.ifEmpty {
            warnings += "未发现任何可响应设备。酒店 AP 隔离、禁 ping/主动连接或跨 VLAN 都可能导致此结果。"
            emptyList()
        }

        if (liveHosts.isNotEmpty()) {
            onProgress(ScanProgress("对 ${liveHosts.size} 台在线设备做扩展端口指纹"))
            tcpScanner.scanHosts(
                hosts = liveHosts,
                ports = TcpPortScanner.EXTENDED_CAMERA_PORTS,
            ) { ip, probes ->
                table.addPorts(ip, probes)
                table.addSource(ip, DiscoverySource.TCP_PORT_SCAN)
            }
        }

        val devices = table.build()
        if (devices.size <= 1) {
            warnings += "当前网络几乎没有可见设备。最可靠的复测方式是让摄像头和手机都接入你自己的旅行路由器。"
        }

        onProgress(ScanProgress("评分与误报抑制", discoveredDevices = devices.size))
        return ScanOutcome(network = network, devices = devices, warnings = warnings.distinct())
    }

    private class DeviceTable {
        private val ips = ConcurrentHashMap.newKeySet<String>()
        private val macByIp = ConcurrentHashMap<String, String>()
        private val nameByIp = ConcurrentHashMap<String, String>()
        private val sources = ConcurrentHashMap<String, MutableSet<DiscoverySource>>()
        private val ports = ConcurrentHashMap<String, ConcurrentHashMap<Int, PortProbe>>()
        private val services = ConcurrentHashMap<String, MutableList<ServiceFingerprint>>()
        private val metadata = ConcurrentHashMap<String, MutableMap<String, String>>()

        fun putMac(ip: String, mac: String) {
            addIp(ip)
            macByIp[ip] = mac.uppercase()
        }

        fun addSource(ip: String, source: DiscoverySource) {
            addIp(ip)
            sources.computeIfAbsent(ip) { ConcurrentHashMap.newKeySet() }.add(source)
        }

        fun addPorts(ip: String, probes: List<PortProbe>) {
            addIp(ip)
            val byPort = ports.computeIfAbsent(ip) { ConcurrentHashMap() }
            probes.forEach { byPort[it.port] = it }
        }

        fun addService(ip: String, service: ServiceFingerprint) {
            addIp(ip)
            services.computeIfAbsent(ip) { Collections.synchronizedList(mutableListOf()) }.add(service)
            service.name?.let { nameByIp.putIfAbsent(ip, it) }
            service.manufacturer?.let { putMetadata(ip, "MANUFACTURER", it) }
            service.model?.let { putMetadata(ip, "MODEL", it) }
        }

        fun putMetadata(ip: String, key: String, value: String) {
            addIp(ip)
            metadata.computeIfAbsent(ip) { ConcurrentHashMap() }[key] = value
        }

        fun ips(): List<String> = ips.toList()
        fun ipCount(): Int = ips.size

        private fun addIp(ip: String) {
            ips.add(ip)
        }

        fun build(): List<LanDevice> = ips.map { ip ->
            val mac = macByIp[ip]
            LanDevice(
                id = mac?.let(::normalizeMac) ?: ip,
                ip = ip,
                mac = mac,
                name = nameByIp[ip],
                vendor = metadata[ip]?.get("MANUFACTURER"),
                sources = sources[ip]?.toSet().orEmpty(),
                ports = ports[ip]?.values?.sortedBy { it.port }.orEmpty(),
                services = services[ip]?.toList().orEmpty(),
                metadata = metadata[ip]?.toMap().orEmpty(),
            )
        }

        private fun normalizeMac(mac: String): String =
            mac.uppercase().replace('-', ':')
        }

    companion object {
        private const val NSD_WINDOW_MS = 6_000L
    }
}
