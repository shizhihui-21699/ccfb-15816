package com.roomradar.core.traffic

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Tests whether a device's encrypted traffic metadata repeatedly changes during guided motion.
 *
 * It intentionally uses only timing/byte/packet metadata: encrypted payloads do not prevent bitrate
 * changes, but neither does a correlation prove a camera. The result must be combined with LAN
 * fingerprints and suppressed for known TV/speaker devices.
 */
class MotionCorrelationAnalyzer(
    private val config: CorrelationConfig = CorrelationConfig(),
) {
    private data class TrialData(
        val trialId: Int,
        val baseline: List<Double>,
        val motion: List<Double>,
    )

    private data class LagFit(
        val lagMs: Long,
        val trialEffects: List<Double>,
        /** Mean log-rate contrast; less discrete than a median contrast for permutation tests. */
        val trialStatistics: List<Double>,
        val baseline: List<Double>,
        val motion: List<Double>,
        val usedPointCount: Int,
    ) {
        val effect: Double? = trialEffects.takeIf { it.isNotEmpty() }?.let(::median)
        val statistic: Double? = trialStatistics.takeIf { it.isNotEmpty() }?.average()
    }

    fun analyze(
        deviceId: String,
        points: List<RatePoint>,
        windows: List<PhaseWindow>,
        zoneId: String? = null,
    ): CorrelationResult {
        val reasons = mutableListOf<String>()
        val scopedWindows = windows.filter { it.zoneId == zoneId }
        val trialIds = scopedWindows.map { it.trialId }.distinct().sorted()

        val lags = buildLagOptions()
        val fits = lags.mapNotNull { lag -> fitLag(trialIds, scopedWindows, points, lag) }
        val best = fits.filter { (it.effect ?: Double.NEGATIVE_INFINITY) > 0.0 }
            .maxByOrNull { it.effect ?: Double.NEGATIVE_INFINITY }

        if (best == null) {
            return insufficientResult(
                deviceId = deviceId,
                zoneId = zoneId,
                trialCount = 0,
                usedPointCount = points.size,
                reasons = listOf("没有足够的基线/走动配对样本。"),
            )
        }

        val validTrialCount = best.trialEffects.size
        val baselineMedian = median(best.baseline)
        val motionMedian = median(best.motion)
        val effect = best.effect ?: 0.0
        val percentChange = (exp(effect) - 1.0) * 100.0
        val consistency = if (best.trialEffects.isEmpty()) {
            null
        } else {
            best.trialEffects.count { it >= config.minLogEffect }.toDouble() /
                best.trialEffects.size
        }

        val pValue = permutationPValue(fits, trialIds, scopedWindows, points)

        val enoughTrials = validTrialCount >= config.minTrials
        val enoughPoints = best.baseline.size >= config.minBinsPerPhase * validTrialCount &&
            best.motion.size >= config.minBinsPerPhase * validTrialCount
        val enoughEffect = effect >= config.minLogEffect &&
            (motionMedian - baselineMedian) >= config.minAbsoluteBytesPerSecond
        val enoughConsistency = (consistency ?: 0.0) >= config.minConsistency
        val significant = pValue != null && pValue <= config.maxPValue

        if (!enoughTrials) reasons += "有效重复次数不足：$validTrialCount/${config.minTrials}"
        if (!enoughPoints) reasons += "每个阶段的采样点不足，建议延长基线和走动时间。"
        if (!enoughEffect) {
            reasons += "流量上升幅度未达到实用阈值（可能是恒定码率摄像头，也可能与拍摄无关）。"
        }
        if (!enoughConsistency) {
            reasons += "多次测试的变化方向/幅度不一致，更像背景流量或缓冲波动。"
        }
        if (!significant) {
            reasons += if (pValue == null) "无法完成置换显著性检验。"
            else "时序变化未通过显著性检验（p=${formatP(pValue)}）。"
        }

        val reliable = enoughTrials && enoughPoints && enoughEffect &&
            enoughConsistency && significant

        val score = score(
            effect = effect,
            consistency = consistency ?: 0.0,
            pValue = pValue,
            trialCount = validTrialCount,
            reliable = reliable,
        )

        if (reliable) {
            reasons += "多次引导窗口内重复出现流量速率变化，最佳滞后约 ${best.lagMs}ms；" +
                "加密内容仍不可见，该信号只能提高可疑度，不能单独认定针孔摄像头。"
        }

        return CorrelationResult(
            deviceId = deviceId,
            zoneId = zoneId,
            reliable = reliable,
            trialCount = validTrialCount,
            usedPointCount = best.usedPointCount,
            baselineBytesPerSecond = baselineMedian,
            motionBytesPerSecond = motionMedian,
            medianLogRatio = effect,
            percentChange = percentChange,
            pValue = pValue,
            consistency = consistency,
            bestLagMs = best.lagMs,
            score = score,
            reasons = reasons,
        )
    }

    private fun buildLagOptions(): List<Long> {
        val options = mutableListOf(0L)
        var lag = config.lagStepMs
        while (lag <= config.maxLagMs) {
            options += lag
            lag += config.lagStepMs
        }
        return options.distinct()
    }

    private fun fitLag(
        trialIds: List<Int>,
        windows: List<PhaseWindow>,
        points: List<RatePoint>,
        lagMs: Long,
    ): LagFit? {
        val trialData = trialIds.mapNotNull { trialId ->
            val baselineWindow = windows.firstOrNull {
                it.trialId == trialId && it.phase == WalkPhase.BASELINE
            } ?: return@mapNotNull null
            val motionWindow = windows.firstOrNull {
                it.trialId == trialId && it.phase == WalkPhase.MOTION
            } ?: return@mapNotNull null

            val baseline = values(points, baselineWindow.startMs, baselineWindow.endMs)
            val motion = values(
                points,
                motionWindow.startMs + lagMs,
                motionWindow.endMs + lagMs,
            )
            if (baseline.size < config.minBinsPerPhase ||
                motion.size < config.minBinsPerPhase
            ) {
                null
            } else {
                TrialData(trialId, baseline, motion)
            }
        }

        if (trialData.isEmpty()) return null
        val trialEffects = trialData.map { trial ->
            logRateMedian(trial.motion) - logRateMedian(trial.baseline)
        }
        val trialStatistics = trialData.map { trial ->
            meanLogRate(trial.motion) - meanLogRate(trial.baseline)
        }
        val baseline = trialData.flatMap { it.baseline }
        val motion = trialData.flatMap { it.motion }
        return LagFit(
            lagMs = lagMs,
            trialEffects = trialEffects,
            trialStatistics = trialStatistics,
            baseline = baseline,
            motion = motion,
            usedPointCount = baseline.size + motion.size,
        )
    }

    private fun permutationPValue(
        observedFits: List<LagFit>,
        trialIds: List<Int>,
        windows: List<PhaseWindow>,
        points: List<RatePoint>,
    ): Double? {
        if (config.permutationCount <= 0) return null
        val observedMaximum = observedFits
            .mapNotNull { it.statistic }
            .maxOrNull()
            ?: return null
        val random = Random(config.randomSeed)
        var exceed = 0
        var completed = 0

        repeat(config.permutationCount) {
            val nullMaximum = buildLagOptions().mapNotNull { lag ->
                fitRandomizedLag(trialIds, windows, points, lag, random)?.statistic
            }.maxOrNull() ?: return@repeat

            completed++
            if (nullMaximum >= observedMaximum) exceed++
        }

        if (completed == 0) return null
        return (exceed + 1.0) / (completed + 1.0)
    }

    private fun fitRandomizedLag(
        trialIds: List<Int>,
        windows: List<PhaseWindow>,
        points: List<RatePoint>,
        lagMs: Long,
        random: Random,
    ): LagFit? {
        val trialResults = trialIds.mapNotNull { trialId ->
            val baselineWindow = windows.firstOrNull {
                it.trialId == trialId && it.phase == WalkPhase.BASELINE
            } ?: return@mapNotNull null
            val motionWindow = windows.firstOrNull {
                it.trialId == trialId && it.phase == WalkPhase.MOTION
            } ?: return@mapNotNull null

            val baseline = values(points, baselineWindow.startMs, baselineWindow.endMs).toMutableList()
            val motion = values(
                points,
                motionWindow.startMs + lagMs,
                motionWindow.endMs + lagMs,
            ).toMutableList()
            if (baseline.size < config.minBinsPerPhase ||
                motion.size < config.minBinsPerPhase
            ) {
                return@mapNotNull null
            }

            val combined = (baseline + motion).toMutableList()
            combined.shuffle(random)
            val split = baseline.size
            val randomBaseline = combined.subList(0, split)
            val randomMotion = combined.subList(split, combined.size)
            val medianEffect = logRateMedian(randomMotion) - logRateMedian(randomBaseline)
            val meanEffect = meanLogRate(randomMotion) - meanLogRate(randomBaseline)
            medianEffect to meanEffect
        }
        if (trialResults.isEmpty()) return null
        return LagFit(
            lagMs = lagMs,
            trialEffects = trialResults.map { it.first },
            trialStatistics = trialResults.map { it.second },
            baseline = emptyList(),
            motion = emptyList(),
            usedPointCount = 0,
        )
    }

    private fun values(points: List<RatePoint>, startMs: Long, endMs: Long): List<Double> =
        points.asSequence()
            .filter { it.epochMs in startMs..endMs }
            .map { max(0.0, it.bytesPerSecond) }
            .toList()

    private fun logRateMedian(values: List<Double>): Double =
        median(values.map { ln(it + 1.0) })

    private fun meanLogRate(values: List<Double>): Double {
        require(values.isNotEmpty()) { "meanLogRate of empty list" }
        return values.sumOf { ln(it + 1.0) } / values.size
    }

    private fun score(
        effect: Double,
        consistency: Double,
        pValue: Double?,
        trialCount: Int,
        reliable: Boolean,
    ): Int {
        val effectComponent = (effect / ln(2.0)).coerceIn(0.0, 1.0)
        val significanceComponent = when {
            pValue == null -> 0.0
            else -> (1.0 - pValue).coerceIn(0.0, 1.0)
        }
        val qualityComponent = (trialCount / 3.0).coerceIn(0.0, 1.0)
        val raw = 100.0 * (
            0.35 * effectComponent +
                0.25 * consistency.coerceIn(0.0, 1.0) +
                0.25 * significanceComponent +
                0.15 * qualityComponent
            )
        // An unreliable result is retained for debugging/UI explanation but cannot drive an alert.
        return raw.roundToInt().coerceIn(if (reliable) 35 else 0, if (reliable) 100 else 34)
    }

    private fun insufficientResult(
        deviceId: String,
        zoneId: String?,
        trialCount: Int,
        usedPointCount: Int,
        reasons: List<String>,
    ) = CorrelationResult(
        deviceId = deviceId,
        zoneId = zoneId,
        reliable = false,
        trialCount = trialCount,
        usedPointCount = usedPointCount,
        baselineBytesPerSecond = null,
        motionBytesPerSecond = null,
        medianLogRatio = null,
        percentChange = null,
        pValue = null,
        consistency = null,
        bestLagMs = null,
        score = 0,
        reasons = reasons,
    )

    private fun formatP(p: Double): String =
        if (p < 0.001) "<0.001" else "%.3f".format(p)
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median of empty list" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
