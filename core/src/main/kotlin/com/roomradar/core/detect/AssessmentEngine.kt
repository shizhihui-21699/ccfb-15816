package com.roomradar.core.detect

import com.roomradar.core.model.Confidence
import com.roomradar.core.model.DeviceAssessment
import com.roomradar.core.model.Evidence
import com.roomradar.core.model.LanDevice
import com.roomradar.core.model.ThreatLevel
import com.roomradar.core.traffic.CorrelationResult
import kotlin.math.roundToInt

class AssessmentEngine(
    private val ouiClassifier: OuiClassifier = OuiClassifier.EMPTY,
) {
    fun assess(
        devices: Collection<LanDevice>,
        motion: Map<String, CorrelationResult> = emptyMap(),
    ): List<DeviceAssessment> = devices.map { assess(it, motion[it.id]) }
        .sortedWith(compareByDescending<DeviceAssessment> { it.score }.thenBy { it.deviceId })

    fun assess(device: LanDevice, motion: CorrelationResult? = null): DeviceAssessment {
        val evidence = mutableListOf<Evidence>()
        evidence += portEvidence(device)
        evidence += serviceEvidence(device)
        evidence += textEvidence(device)
        ouiClassifier.vendorFor(device.mac)?.let { vendor ->
            evidence += Evidence(
                code = "CAMERA_OUI",
                weight = 10,
                title = "MAC 厂商前缀与摄像头/摄像模组厂商相关",
                detail = vendor,
            )
        }

        val benign = benignProfile(device)
        if (benign != null) evidence += benign

        if (motion != null) {
            evidence += motionEvidence(motion, benign != null)
        }

        val adjustedScore = evidence.sumOf { e ->
            if (e.benign) 0 else adjustedWeight(e, benign != null)
        }.coerceIn(0, 100)

        val hardCodes = evidence.filter { !it.benign && it.code in HARD_CAMERA_CODES }
            .map { it.code }
            .distinct()
        val independentSources = device.sources.size +
            (if (motion?.reliable == true && benign == null) 1 else 0)

        val score = when {
            benign != null && hardCodes.size < 2 -> minOf(adjustedScore, 34)
            else -> adjustedScore
        }

        val level = when {
            score >= 65 && hardCodes.size >= 2 -> ThreatLevel.HIGH
            score >= 42 -> ThreatLevel.REVIEW
            score >= 18 -> ThreatLevel.LOW
            else -> ThreatLevel.NO_SIGNAL
        }

        val confidence = confidence(
            level = level,
            hardSignalCount = hardCodes.size,
            independentSources = independentSources,
            motionReliable = motion?.reliable == true,
            evidenceCount = evidence.count { !it.benign && it.weight > 0 },
        )

        return DeviceAssessment(
            deviceId = device.id,
            score = score,
            level = level,
            confidence = confidence,
            headline = headline(level, benign != null, motion?.reliable == true),
            evidence = evidence.sortedByDescending { it.weight },
            recommendedActions = actions(level, motion?.reliable == true),
            motionTestApplied = motion != null,
        )
    }

    private fun portEvidence(device: LanDevice): List<Evidence> {
        val result = mutableListOf<Evidence>()
        var genericWebWeight = 0

        device.ports.filter { it.open }.forEach { probe ->
            when {
                probe.protocol == "RTSP" || probe.banner?.startsWith("RTSP/") == true -> {
                    result += Evidence(
                        code = "RTSP_DESCRIBE_OK",
                        weight = 28,
                        title = "RTSP 端口返回了摄像头/视频流协议响应",
                        detail = probe.banner?.lineSequence()?.firstOrNull(),
                    )
                }

                probe.port == 554 || probe.port == 8554 -> {
                    result += Evidence(
                        code = "RTSP_PORT_OPEN",
                        weight = 12,
                        title = "开放常见 RTSP 视频流端口 ${probe.port}",
                    )
                }

                probe.port == 37777 || probe.protocol == "DAHUA_SDK" -> {
                    result += Evidence(
                        code = "DAHUA_SDK_PORT",
                        weight = if (probe.protocol != null) 26 else 18,
                        title = "开放大华/DVR 常见 SDK 端口 ${probe.port}",
                        detail = probe.banner,
                    )
                }

                probe.port == 34567 || probe.protocol == "XMEYE" -> {
                    result += Evidence(
                        code = "XMEYE_PORT",
                        weight = if (probe.protocol != null) 24 else 16,
                        title = "开放 XMEye/雄迈方案常见端口 ${probe.port}",
                        detail = probe.banner,
                    )
                }

                probe.port == 8000 || probe.protocol == "HIKVISION_SDK" -> {
                    result += Evidence(
                        code = "HIKVISION_SDK_PORT",
                        weight = if (probe.protocol != null) 26 else 16,
                        title = "开放海康设备/SDK 常见端口 ${probe.port}",
                        detail = probe.banner,
                    )
                }

                probe.port == 1935 -> result += Evidence(
                    code = "RTMP_PORT",
                    weight = 9,
                    title = "开放常见 RTMP 推流端口 1935",
                )

                probe.port in GENERIC_WEB_PORTS -> genericWebWeight += 3
            }

            if (probe.banner != null && CAMERA_BANNER_KEYWORDS.any {
                    probe.banner.contains(it, ignoreCase = true)
                }
            ) {
                result += Evidence(
                    code = "CAMERA_BANNER",
                    weight = 22,
                    title = "HTTP/设备横幅包含网络摄像机特征",
                    detail = sanitizeBanner(probe.banner),
                )
            }
        }

        if (genericWebWeight > 0) {
            result += Evidence(
                code = "WEB_PORT",
                weight = minOf(genericWebWeight, 9),
                title = "开放一个或多个 Web 管理端口",
                detail = "路由器、电视和 IoT 设备也常见，单独不能说明是摄像头。",
            )
        }
        return result
    }

    private fun serviceEvidence(device: LanDevice): List<Evidence> {
        val evidence = mutableListOf<Evidence>()
        val serializedServices = device.services.joinToString("\n") {
            listOf(it.type, it.name, it.manufacturer, it.model).filterNotNull().joinToString(" ")
        }

        if (device.services.any { it.type.contains("onvif", ignoreCase = true) } ||
            serializedServices.contains("ONVIF", ignoreCase = true)
        ) {
            evidence += Evidence(
                code = "ONVIF_DEVICE",
                weight = 42,
                title = "发现 ONVIF 网络视频设备服务",
                detail = "ONVIF 主要用于摄像头/NVR，但仍应核对设备实体。",
            )
        }

        device.services.forEach { service ->
            val type = service.type.lowercase()
            when {
                "axis-video" in type || "_axis-video" in type -> evidence += Evidence(
                    code = "CAMERA_MDNS_STRONG",
                    weight = 34,
                    title = "发现 Axis 视频设备服务",
                    detail = service.name,
                )

                "_rtsp._tcp" in type -> evidence += Evidence(
                    code = "CAMERA_MDNS_STRONG",
                    weight = 26,
                    title = "mDNS 广播 RTSP 视频服务",
                    detail = service.name,
                )

                "_camera" in type || "webcam" in type -> evidence += Evidence(
                    code = "CAMERA_MDNS_MEDIUM",
                    weight = 20,
                    title = "mDNS 服务名包含 camera/webcam",
                    detail = service.name ?: service.type,
                )
            }

            val makerText = listOfNotNull(service.manufacturer, service.model, service.name)
                .joinToString(" ")
            CAMERA_BRAND_KEYWORDS.firstOrNull { makerText.contains(it, ignoreCase = true) }
                ?.let { brand ->
                    evidence += Evidence(
                        code = "CAMERA_BRAND",
                        weight = 16,
                        title = "服务信息中出现摄像头/安防品牌：$brand",
                    )
                }
        }

        val allText = listOfNotNull(device.name, device.vendor, device.metadata.values.joinToString(" "))
            .joinToString(" ")
        CAMERA_BRAND_KEYWORDS.firstOrNull { allText.contains(it, ignoreCase = true) }?.let { brand ->
            evidence += Evidence(
                code = "CAMERA_BRAND_DEVICE",
                weight = 14,
                title = "设备名称/厂商包含摄像头品牌：$brand",
            )
        }
        return evidence
    }

    private fun textEvidence(device: LanDevice): List<Evidence> {
        val text = listOfNotNull(
            device.name,
            device.vendor,
            device.metadata["SERVER"],
            device.metadata["MODEL"],
            device.metadata["MANUFACTURER"],
        ).joinToString(" ")
        return GENERIC_CAMERA_WORDS.mapNotNull { keyword ->
            if (text.contains(keyword, ignoreCase = true)) {
                Evidence(
                    code = "CAMERA_NAME_KEYWORD",
                    weight = 12,
                    title = "设备文本包含“$keyword”",
                    detail = "设备可能主动广播为摄像头，也可能使用通用模组名称。",
                )
            } else {
                null
            }
        }.take(1)
    }

    private fun benignProfile(device: LanDevice): Evidence? {
        val serviceTypes = device.services.joinToString(" ") { it.type }.lowercase()
        val text = listOfNotNull(
            device.name,
            device.vendor,
            device.services.joinToString(" ") {
                listOfNotNull(it.name, it.manufacturer, it.model).joinToString(" ")
            },
        ).joinToString(" ").lowercase()

        val mediaService = BENIGN_SERVICE_MARKERS.any { it in serviceTypes }
        val mediaName = BENIGN_NAME_MARKERS.any { it in text }
        val benignVendor = BENIGN_VENDOR_MARKERS.any { it in text }

        return when {
            mediaService && mediaName -> Evidence(
                code = "BENIGN_MEDIA_STRONG",
                weight = 0,
                title = "识别为电视/投屏/音箱等媒体设备",
                detail = "已对通用端口、OUI 和走动相关性信号降权，避免用电视缓冲波动误报。",
                benign = true,
            )

            mediaService || mediaName -> Evidence(
                code = "BENIGN_MEDIA_WEAK",
                weight = 0,
                title = "存在投屏/电视/音箱特征",
                detail = "该类设备的流媒体流量可能随剧情、缓冲和音量变化，已降低弱信号权重。",
                benign = true,
            )

            benignVendor && GENERIC_WEB_PORTS.any { port ->
                device.ports.any { it.port == port && it.open }
            } -> Evidence(
                code = "BENIGN_VENDOR",
                weight = 0,
                title = "常见消费电子/媒体厂商",
                detail = "厂商信息不能排除伪装，但可降低 OUI 和 Web 端口等弱信号权重。",
                benign = true,
            )

            else -> null
        }
    }

    private fun motionEvidence(motion: CorrelationResult, benignMedia: Boolean): Evidence =
        if (motion.reliable && !benignMedia) {
            Evidence(
                code = "MOTION_CORRELATION",
                weight = (motion.score * 0.18).roundToInt().coerceIn(8, 18),
                title = "走动窗口与该设备的加密流量速率可重复相关",
                detail = motion.reasons.firstOrNull(),
            )
        } else if (motion.reliable && benignMedia) {
            Evidence(
                code = "MOTION_CORRELATION_IGNORED_BENIGN",
                weight = 0,
                title = "流量随走动变化，但设备具有明确电视/音箱特征，未计入风险分",
                detail = motion.reasons.firstOrNull(),
                benign = true,
            )
        } else {
            Evidence(
                code = "MOTION_INCONCLUSIVE",
                weight = 0,
                title = "走动流量测试未能形成可重复证据",
                detail = motion.reasons.firstOrNull(),
            )
        }

    private fun adjustedWeight(evidence: Evidence, benign: Boolean): Int {
        if (!benign) return evidence.weight
        return when (evidence.code) {
            "ONVIF_DEVICE", "RTSP_DESCRIBE_OK", "CAMERA_MDNS_STRONG" -> evidence.weight
            "CAMERA_BANNER", "CAMERA_BRAND", "CAMERA_BRAND_DEVICE",
            "HIKVISION_SDK_PORT", "DAHUA_SDK_PORT", "XMEYE_PORT",
            -> (evidence.weight * 0.55).roundToInt()

            else -> (evidence.weight * 0.15).roundToInt()
        }
    }

    private fun confidence(
        level: ThreatLevel,
        hardSignalCount: Int,
        independentSources: Int,
        motionReliable: Boolean,
        evidenceCount: Int,
    ): Confidence = when {
        level == ThreatLevel.HIGH && hardSignalCount >= 2 -> Confidence.HIGH
        hardSignalCount >= 1 && (independentSources >= 2 || motionReliable) -> Confidence.HIGH
        hardSignalCount >= 1 || evidenceCount >= 2 -> Confidence.MEDIUM
        evidenceCount == 1 -> Confidence.LOW
        else -> Confidence.INSUFFICIENT
    }

    private fun headline(level: ThreatLevel, benign: Boolean, motion: Boolean): String = when (level) {
        ThreatLevel.HIGH -> "发现多个与网络摄像头一致的独立特征，建议优先人工排查"
        ThreatLevel.REVIEW -> when {
            motion -> "局域网特征与走动流量变化吻合，建议检查候选设备"
            benign -> "存在少量视频/网络服务特征，但更像普通媒体设备，可做走动测试复核"
            else -> "存在可疑网络特征，建议进行分区走动测试和外观检查"
        }

        ThreatLevel.LOW -> "只有弱信号，暂不建议报警；可结合物理镜头检查"
        ThreatLevel.NO_SIGNAL -> "本轮未发现强摄像头信号；不能据此证明房间内没有摄像头"
    }

    private fun actions(level: ThreatLevel, motionApplied: Boolean): List<String> {
        val base = listOf(
            "重点检查烟感、USB 充电器、插座、闹钟、路由器、机顶盒和正对床/浴室的小孔。",
            "拉窗帘关灯后用手机相机扫描异常红点，并用手电筒照射镜头反光；注意部分手机会过滤红外光。",
            "对候选设备执行分区走动测试：每个区域保持基线静止 15–30 秒，再走动/挥手 15–30 秒，重复 2–3 次。",
        )
        return when (level) {
            ThreatLevel.HIGH -> base + listOf(
                "不要自行拆卸或指控任何人；保留扫描时间和设备信息，联系酒店/民宿负责人或警方在场处理。",
                "如需阻断，可在你可控的便携路由器中临时将该设备拉黑，而不是在酒店上级网络中做干扰。",
            )

            ThreatLevel.REVIEW -> base + listOf(
                if (motionApplied) "若某个物理区域反复触发同一设备，优先检查该方向的物体。"
                else "配置配对的旅行路由器流量代理后，可启用加密流量时序相关性测试。",
            )

            else -> listOf(
                "未发现信号不等于安全：AP 隔离、摄像头休眠、恒定码率或不在同一 /24 都可能导致漏检。",
                "可改用自备旅行路由器建立可信网络，让所有设备和手机都接入它后重新扫描。",
                "智能电视、音箱和手机云同步可能产生类似流量，不应仅凭速率变化惊吓用户。",
            )
        }
    }

    private fun sanitizeBanner(banner: String): String =
        banner.lineSequence().firstOrNull()?.take(120) ?: banner.take(120)

    companion object {
        private val GENERIC_WEB_PORTS = setOf(80, 81, 82, 443, 800, 8080, 8081, 8082, 8443, 8888)

        private val HARD_CAMERA_CODES = setOf(
            "ONVIF_DEVICE",
            "RTSP_DESCRIBE_OK",
            "CAMERA_MDNS_STRONG",
            "CAMERA_BANNER",
            "HIKVISION_SDK_PORT",
            "DAHUA_SDK_PORT",
            "XMEYE_PORT",
        )

        private val CAMERA_BANNER_KEYWORDS = listOf(
            "hikvision", "dahua", "imou", "reolink", "xmeye", "axis", "vivotek",
            "foscam", "tp-link ipc", "tapo c", "ezviz", "uniview", "ip camera",
            "network camera", "web camera", "webcam", "nvr", "dvr",
        )

        private val CAMERA_BRAND_KEYWORDS = listOf(
            "hikvision", "dahua", "imou", "ezviz", "reolink", "axis", "vivotek",
            "foscam", "xmeye", "uniview", "amcrest", "lorex", "swann", "tp-link vigi",
        )

        private val GENERIC_CAMERA_WORDS = listOf(
            "ip camera", "network camera", "web camera", "webcam", "camera",
        )

        private val BENIGN_SERVICE_MARKERS = listOf(
            "_googlecast._tcp",
            "_airplay._tcp",
            "_raop._tcp",
            "_androidtvremote._tcp",
            "_spotify-connect._tcp",
            "_sonos._tcp",
            "_homekit._tcp",
            "_hap._tcp",
            "mediarenderer",
            "dial",
        )

        private val BENIGN_NAME_MARKERS = listOf(
            "chromecast", "google tv", "android tv", "appletv", "apple tv", "fire tv",
            "roku", "bravia", "webos tv", "tizen", "smart tv", "television",
            "sonos", "homepod", "echo", "alexa", "soundbar", "speaker",
            "playstation", "xbox", "nintendo",
        )

        private val BENIGN_VENDOR_MARKERS = listOf(
            "google", "amazon", "apple", "sonos", "bose", "roku", "samsung",
            "lg electronics", "sony", "tcl", "hisense", "bang & olufsen",
        )
    }
}
