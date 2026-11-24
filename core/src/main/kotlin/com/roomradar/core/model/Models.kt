package com.roomradar.core.model

/** How a device or service was observed. Several sources make an assessment more reliable. */
enum class DiscoverySource {
    TCP_PORT_SCAN,
    MDNS,
    NSD,
    SSDP,
    ONVIF,
    NEIGHBOR_TABLE,
    ROUTER_API,
    MANUAL,
}

enum class ThreatLevel {
    /** No meaningful signal in this scan. This never means “proved safe”. */
    NO_SIGNAL,

    /** A few weak/ambiguous signals; no user-facing alarm. */
    LOW,

    /** Worth a guided walk test and manual inspection. */
    REVIEW,

    /** Multiple independent camera-specific signals; show a strong but non-accusatory warning. */
    HIGH,
}

enum class Confidence {
    INSUFFICIENT,
    LOW,
    MEDIUM,
    HIGH,
}

data class PortProbe(
    val port: Int,
    val open: Boolean,
    /** e.g. RTSP, HTTP, HTTPS, HIKVISION_SDK, DAHUA_SDK, XMEYE. */
    val protocol: String? = null,
    /** Truncated, sanitized banner or response line. Do not store credentials or full HTML. */
    val banner: String? = null,
)

data class ServiceFingerprint(
    /** Standard service type, e.g. `_onvif._tcp`, `_googlecast._tcp`, `urn:schemas-upnp-org:device:...`. */
    val type: String,
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val endpoint: String? = null,
)

data class LanDevice(
    /** Stable-ish identity: normalized MAC when available, otherwise IP. */
    val id: String,
    val ip: String? = null,
    val mac: String? = null,
    val name: String? = null,
    val vendor: String? = null,
    val sources: Set<DiscoverySource> = emptySet(),
    val ports: List<PortProbe> = emptyList(),
    val services: List<ServiceFingerprint> = emptyList(),
    /** Only small, non-sensitive key/value metadata. */
    val metadata: Map<String, String> = emptyMap(),
    val firstSeenEpochMs: Long = 0L,
    val lastSeenEpochMs: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "LanDevice.id must not be blank" }
    }
}

data class Evidence(
    val code: String,
    /** Positive weights increase suspicion; benign evidence is rendered as a suppressor. */
    val weight: Int,
    val title: String,
    val detail: String? = null,
    val benign: Boolean = false,
)

data class DeviceAssessment(
    val deviceId: String,
    val score: Int,
    val level: ThreatLevel,
    val confidence: Confidence,
    val headline: String,
    val evidence: List<Evidence>,
    val recommendedActions: List<String>,
    /** True when a guided traffic test materially contributed to the result. */
    val motionTestApplied: Boolean = false,
) {
    init {
        require(score in 0..100) { "score must be in 0..100, was $score" }
    }
}

data class NetworkSnapshot(
    val ssid: String?,
    val bssid: String?,
    val ownIpv4: String?,
    val prefixLength: Int?,
    val gatewayIpv4: String?,
    /** False when the OS/location permission did not allow reading SSID/BSSID. */
    val identityVisible: Boolean,
    /** True when Wi-Fi client isolation is suspected from gateway/scan behavior. */
    val clientIsolationSuspected: Boolean = false,
)
