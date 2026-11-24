package com.roomradar.android.traffic

import com.roomradar.core.model.DiscoverySource
import com.roomradar.core.model.LanDevice
import com.roomradar.core.traffic.DeviceTrafficGateway
import com.roomradar.core.traffic.TrafficCounters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the small LAN counter agent described in /router-agent.
 *
 * Production should put this behind QR pairing and HTTPS certificate/public-key pinning.
 * The bearer token only prevents trivial reads from other hotel-network guests.
 */
class RouterTrafficClient(
    private val baseUrl: String,
    private val token: String,
) : DeviceTrafficGateway {

    override val sourceName: String = "配对旅行路由器代理"

    override suspend fun listDevices(): List<LanDevice> = withContext(Dispatchers.IO) {
        val array = getJsonArray("/api/v1/devices")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val mac = item.optString("mac").ifBlank { null }
                val ip = item.optString("ip").ifBlank { null }
                val id = item.optString("id").ifBlank { mac ?: ip } ?: continue
                add(
                    LanDevice(
                        id = id,
                        ip = ip,
                        mac = mac?.uppercase(),
                        name = item.optString("hostname").ifBlank { null },
                        sources = setOf(DiscoverySource.ROUTER_API),
                    ),
                )
            }
        }
    }

    override fun counters(pollIntervalMs: Long): Flow<List<TrafficCounters>> = flow {
        while (true) {
            emit(counterList())
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun counterList(): List<TrafficCounters> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val array = getJsonArray("/api/v1/counters")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("deviceId")
                    .ifBlank { item.optString("id") }
                    .ifBlank { item.optString("mac") }
                if (id.isBlank()) continue
                add(
                    TrafficCounters(
                        deviceId = id.uppercase().replace('-', ':'),
                        epochMs = item.optLong("timestampMs", now),
                        rxBytes = item.optLong("rxBytes", 0L),
                        txBytes = item.optLong("txBytes", 0L),
                        rxPackets = item.optLong("rxPackets").takeIf { item.has("rxPackets") },
                        txPackets = item.optLong("txPackets").takeIf { item.has("txPackets") },
                    ),
                )
            }
        }
    }

    private fun getJsonArray(path: String): JSONArray {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_500
                readTimeout = 2_500
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw RouterApiException("路由器代理返回 HTTP $code: ${body.take(120)}")
            }
            JSONArray(body)
        } finally {
            connection?.disconnect()
        }
    }

    class RouterApiException(message: String) : RuntimeException(message)
}
