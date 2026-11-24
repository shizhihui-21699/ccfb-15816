package com.roomradar.core.traffic

import com.roomradar.core.model.LanDevice

/**
 * Abstraction over a router or paired travel-router agent that can expose per-device counters.
 *
 * A normal Android VpnService cannot implement this for other LAN devices: it only sees traffic
 * routed through the phone. Likewise, ordinary apps cannot inspect unicast frames of other
 * associated Wi-Fi clients. The supported production paths are:
 *
 * 1. a partner/home/travel router API;
 * 2. a companion agent on a router the traveler controls;
 * 3. optional cloud/NBI integration for router OEM partners.
 *
 * ARP spoofing, unauthorized MITM and stealth packet interception are intentionally not provided.
 */
interface DeviceTrafficGateway {
    suspend fun listDevices(): List<LanDevice>

    /**
     * Starts counter polling. Implementations should emit one cumulative sample per poll.
     * The app's [RateAccumulator] is responsible for converting samples to rates.
     */
    fun counters(pollIntervalMs: Long = 1_000L): kotlinx.coroutines.flow.Flow<List<TrafficCounters>>

    /** User-facing gateway type for settings/debug UI. */
    val sourceName: String
}
