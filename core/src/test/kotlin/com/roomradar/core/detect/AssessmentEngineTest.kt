package com.roomradar.core.detect

import com.roomradar.core.model.DiscoverySource
import com.roomradar.core.model.LanDevice
import com.roomradar.core.model.PortProbe
import com.roomradar.core.model.ServiceFingerprint
import com.roomradar.core.model.ThreatLevel
import com.roomradar.core.traffic.CorrelationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AssessmentEngineTest {
    private val engine = AssessmentEngine(SeedOuiClassifier())

    @Test
    fun genericWebPortAloneDoesNotAlarm() {
        val assessment = engine.assess(
            LanDevice(
                id = "192.168.1.20",
                ip = "192.168.1.20",
                ports = listOf(PortProbe(80, open = true)),
                sources = setOf(DiscoverySource.TCP_PORT_SCAN),
            ),
        )

        assertTrue(assessment.score < 18)
        assertNotEquals(ThreatLevel.HIGH, assessment.level)
        assertNotEquals(ThreatLevel.REVIEW, assessment.level)
    }

    @Test
    fun onvifAndValidRtspProduceHighConfidenceReview() {
        val device = LanDevice(
            id = "AA:BB:CC:DD:EE:FF",
            ip = "192.168.1.30",
            mac = "AA:BB:CC:DD:EE:FF",
            sources = setOf(DiscoverySource.ONVIF, DiscoverySource.TCP_PORT_SCAN),
            ports = listOf(
                PortProbe(554, open = true, protocol = "RTSP", banner = "RTSP/1.0 401 Unauthorized"),
            ),
            services = listOf(
                ServiceFingerprint(type = "_onvif._tcp", manufacturer = "ExampleCam"),
            ),
        )

        val assessment = engine.assess(device)

        assertEquals(ThreatLevel.HIGH, assessment.level)
        assertTrue(assessment.score >= 65)
        assertTrue(assessment.evidence.any { it.code == "ONVIF_DEVICE" })
        assertTrue(assessment.evidence.any { it.code == "RTSP_DESCRIBE_OK" })
    }

    @Test
    fun chromecastSignalsAreSuppressed() {
        val device = LanDevice(
            id = "192.168.1.40",
            name = "Bedroom Chromecast",
            ip = "192.168.1.40",
            sources = setOf(DiscoverySource.MDNS),
            ports = listOf(
                PortProbe(8080, open = true),
                PortProbe(80, open = true),
            ),
            services = listOf(ServiceFingerprint(type = "_googlecast._tcp", name = "Chromecast")),
        )

        val assessment = engine.assess(device)

        assertTrue(assessment.score < 42)
        assertNotEquals(ThreatLevel.HIGH, assessment.level)
        assertTrue(assessment.evidence.any { it.benign && it.code.startsWith("BENIGN_MEDIA") })
    }

    @Test
    fun motionAloneCannotCreateReviewOrHigh() {
        val motion = CorrelationResult(
            deviceId = "unknown",
            zoneId = "room-center",
            reliable = true,
            trialCount = 3,
            usedPointCount = 120,
            baselineBytesPerSecond = 100.0,
            motionBytesPerSecond = 9_000.0,
            medianLogRatio = 2.0,
            percentChange = 638.0,
            pValue = 0.001,
            consistency = 1.0,
            bestLagMs = 500,
            score = 96,
            reasons = emptyList(),
        )

        val assessment = engine.assess(
            LanDevice(id = "unknown", ip = "192.168.1.50"),
            motion,
        )

        assertTrue(assessment.score <= 18)
        assertNotEquals(ThreatLevel.REVIEW, assessment.level)
        assertNotEquals(ThreatLevel.HIGH, assessment.level)
    }

    @Test
    fun reliableMotionDoesNotOverrideKnownMediaSuppression() {
        val motion = CorrelationResult(
            deviceId = "tv",
            zoneId = "bed",
            reliable = true,
            trialCount = 3,
            usedPointCount = 120,
            baselineBytesPerSecond = 1_000_000.0,
            motionBytesPerSecond = 1_500_000.0,
            medianLogRatio = 0.4,
            percentChange = 49.0,
            pValue = 0.01,
            consistency = 1.0,
            bestLagMs = 0,
            score = 80,
            reasons = emptyList(),
        )
        val device = LanDevice(
            id = "tv",
            name = "Sony Bravia TV",
            services = listOf(ServiceFingerprint("_googlecast._tcp")),
        )

        val assessment = engine.assess(device, motion)

        assertTrue(assessment.score < 18)
        assertTrue(assessment.evidence.any { it.code == "MOTION_CORRELATION_IGNORED_BENIGN" })
    }
}
