package com.roomradar.core.traffic

/** Cumulative per-device counters from a user-controlled router or paired travel-router agent. */
data class TrafficCounters(
    val deviceId: String,
    val epochMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long? = null,
    val txPackets: Long? = null,
)

/** One rate bin produced by differencing two cumulative counter samples. */
data class RatePoint(
    val epochMs: Long,
    val bytesPerSecond: Double,
    val packetsPerSecond: Double? = null,
)

enum class WalkPhase { BASELINE, MOTION, RECOVERY }

/**
 * A labelled segment of the guided walk.
 *
 * [zoneId] lets the app correlate one physical area (door, bed, bathroom, smoke detector wall)
 * with each device separately.
 */
data class PhaseWindow(
    val trialId: Int,
    val phase: WalkPhase,
    val startMs: Long,
    val endMs: Long,
    val zoneId: String? = null,
) {
    init {
        require(endMs > startMs) { "window endMs must be greater than startMs" }
    }
}

data class CorrelationConfig(
    val minBinsPerPhase: Int = 3,
    val minTrials: Int = 2,
    val maxLagMs: Long = 3_000L,
    val lagStepMs: Long = 500L,
    val permutationCount: Int = 999,
    val randomSeed: Long = 15816L,
    /** Natural-log effect; ln(1.25) means a 25% median increase. */
    val minLogEffect: Double = 0.22314355131420976,
    val minAbsoluteBytesPerSecond: Double = 1_024.0,
    val maxPValue: Double = 0.05,
    val minConsistency: Double = 2.0 / 3.0,
)

data class CorrelationResult(
    val deviceId: String,
    val zoneId: String?,
    val reliable: Boolean,
    val trialCount: Int,
    val usedPointCount: Int,
    val baselineBytesPerSecond: Double?,
    val motionBytesPerSecond: Double?,
    val medianLogRatio: Double?,
    val percentChange: Double?,
    val pValue: Double?,
    /** Benjamini-Hochberg adjusted value filled by an orchestrator when testing many devices. */
    val adjustedPValue: Double? = null,
    /** Fraction of valid trials whose effect exceeds the practical-effect threshold. */
    val consistency: Double?,
    val bestLagMs: Long?,
    /** 0..100. This is evidence strength, not a probability that the device is a camera. */
    val score: Int,
    val reasons: List<String>,
)
