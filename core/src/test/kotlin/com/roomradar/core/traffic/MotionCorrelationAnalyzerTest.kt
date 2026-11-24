package com.roomradar.core.traffic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionCorrelationAnalyzerTest {
    private val analyzer = MotionCorrelationAnalyzer(
        CorrelationConfig(permutationCount = 199, minTrials = 2),
    )

    @Test
    fun repeatedMotionLinkedBitrateIncreaseIsReliable() {
        val windows = windows()
        val points = buildList {
            repeat(60) { second ->
                val t = second * 1_000L
                val moving = windows.any {
                    it.phase == WalkPhase.MOTION && t in it.startMs..it.endMs
                }
                add(RatePoint(t, if (moving) 9_000.0 else 1_000.0, if (moving) 90.0 else 10.0))
            }
        }

        val result = analyzer.analyze("cam", points, windows, zoneId = "zone")

        assertTrue(result.trialCount >= 2)
        assertTrue(result.reliable, result.reasons.joinToString("; "))
        assertNotNull(result.pValue)
        assertTrue(result.pValue <= 0.05)
        assertTrue(result.consistency == 1.0)
        assertTrue(result.score >= 35)
    }

    @Test
    fun flatTrafficIsNotReliable() {
        val windows = windows()
        val points = (0 until 60).map { second ->
            RatePoint(second * 1_000L, 2_000.0, 20.0)
        }

        val result = analyzer.analyze("idle-device", points, windows, "zone")

        assertFalse(result.reliable)
        assertTrue(result.score < 35)
    }

    @Test
    fun onlyOneTrialIsInsufficient() {
        val windows = windows().filter { it.trialId == 0 }
        val points = (0 until 25).map { second ->
            val t = second * 1_000L
            RatePoint(t, if (t in 10_000..20_000) 9_000.0 else 1_000.0)
        }

        val result = analyzer.analyze("one-trial", points, windows, "zone")

        assertFalse(result.reliable)
        assertTrue(result.reasons.any { it.contains("有效重复次数不足") })
    }

    private fun windows(): List<PhaseWindow> = listOf(
        PhaseWindow(0, WalkPhase.BASELINE, 0, 9_000, "zone"),
        PhaseWindow(0, WalkPhase.MOTION, 10_000, 19_000, "zone"),
        PhaseWindow(1, WalkPhase.BASELINE, 30_000, 39_000, "zone"),
        PhaseWindow(1, WalkPhase.MOTION, 40_000, 49_000, "zone"),
    )
}
