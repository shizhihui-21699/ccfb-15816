package com.roomradar.core.traffic

/**
 * Converts monotonic cumulative router counters into per-second rates.
 *
 * Counter resets (router reboot, agent restart, integer wrap) produce no rate for that interval.
 * This class is not thread-safe; confine it to a single collector coroutine/thread.
 */
class RateAccumulator {
    private data class LastCounter(
        val epochMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
        val rxPackets: Long?,
        val txPackets: Long?,
    )

    private val last = mutableMapOf<String, LastCounter>()

    fun update(counters: TrafficCounters): RatePoint? {
        val previous = last[counters.deviceId]
        val current = LastCounter(
            epochMs = counters.epochMs,
            rxBytes = counters.rxBytes,
            txBytes = counters.txBytes,
            rxPackets = counters.rxPackets,
            txPackets = counters.txPackets,
        )
        last[counters.deviceId] = current

        if (previous == null || counters.epochMs <= previous.epochMs) return null

        val deltaRx = counters.rxBytes - previous.rxBytes
        val deltaTx = counters.txBytes - previous.txBytes
        if (deltaRx < 0 || deltaTx < 0) return null

        val elapsedSeconds = (counters.epochMs - previous.epochMs) / 1000.0
        if (elapsedSeconds <= 0.0) return null

        val byteRate = (deltaRx + deltaTx) / elapsedSeconds
        val packetRate = if (
            counters.rxPackets != null && counters.txPackets != null &&
            previous.rxPackets != null && previous.txPackets != null
        ) {
            val rxPackets = counters.rxPackets - previous.rxPackets
            val txPackets = counters.txPackets - previous.txPackets
            if (rxPackets < 0 || txPackets < 0) {
                null
            } else {
                (rxPackets + txPackets) / elapsedSeconds
            }
        } else {
            null
        }

        return RatePoint(counters.epochMs, byteRate, packetRate)
    }

    fun reset(deviceId: String? = null) {
        if (deviceId == null) last.clear() else last.remove(deviceId)
    }
}
