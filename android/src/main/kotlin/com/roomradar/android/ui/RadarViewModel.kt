package com.roomradar.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomradar.android.scan.DefaultLanScanner
import com.roomradar.android.traffic.RouterTrafficClient
import com.roomradar.core.detect.AssessmentEngine
import com.roomradar.core.detect.SeedOuiClassifier
import com.roomradar.core.model.DeviceAssessment
import com.roomradar.core.model.LanDevice
import com.roomradar.core.model.NetworkSnapshot
import com.roomradar.core.traffic.MotionCorrelationAnalyzer
import com.roomradar.core.traffic.MultipleTesting
import com.roomradar.core.traffic.PhaseWindow
import com.roomradar.core.traffic.RateAccumulator
import com.roomradar.core.traffic.RatePoint
import com.roomradar.core.traffic.WalkPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab { SCAN, WALK, GUIDE }

data class AssessedDevice(
    val device: LanDevice,
    val assessment: DeviceAssessment,
)

data class RadarUiState(
    val tab: AppTab = AppTab.SCAN,
    val isScanning: Boolean = false,
    val scanStage: String = "尚未扫描",
    val network: NetworkSnapshot? = null,
    val warnings: List<String> = emptyList(),
    val results: List<AssessedDevice> = emptyList(),
    val gatewayUrl: String = "http://192.168.8.1:8787",
    val gatewayToken: String = "",
    val gatewayConnected: Boolean = false,
    val gatewayStatus: String = "未连接。走动相关性需要路由器/旅行路由器代理提供每设备流量计数。",
    val gatewayDevices: List<LanDevice> = emptyList(),
    val zoneId: String = "正对床头/烟感方向",
    val guidedRunning: Boolean = false,
    val cue: String? = null,
    val windows: List<PhaseWindow> = emptyList(),
    val motionStatus: String = "尚未执行走动相关性分析",
    val hasRuntimePermissions: Boolean = false,
)

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = DefaultLanScanner(app)
    private val assessmentEngine = AssessmentEngine(SeedOuiClassifier())
    private val correlationAnalyzer = MotionCorrelationAnalyzer()
    private val rateAccumulator = RateAccumulator()
    private val rateSeries = mutableMapOf<String, MutableList<RatePoint>>()

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var gatewayClient: RouterTrafficClient? = null
    private var counterJob: Job? = null
    private var guidedJob: Job? = null

    fun setTab(tab: AppTab) = _uiState.update { it.copy(tab = tab) }
    fun setPermissionsGranted(granted: Boolean) =
        _uiState.update { it.copy(hasRuntimePermissions = granted) }

    fun updateGatewayUrl(value: String) = _uiState.update { it.copy(gatewayUrl = value) }
    fun updateGatewayToken(value: String) = _uiState.update { it.copy(gatewayToken = value) }
    fun updateZone(value: String) = _uiState.update { it.copy(zoneId = value) }

    fun startScan() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    scanStage = "准备扫描",
                    warnings = emptyList(),
                )
            }
            try {
                val outcome = scanner.scan { progress ->
                    _uiState.update {
                        it.copy(
                            scanStage = progress.stage,
                        )
                    }
                }
                val assessed = outcome.devices.map { device ->
                    AssessedDevice(device, assessmentEngine.assess(device))
                }.sortedByDescending { it.assessment.score }

                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanStage = "扫描完成：${assessed.size} 台设备",
                        network = outcome.network,
                        warnings = outcome.warnings,
                        results = assessed,
                    )
                }
                reanalyzeMotion()
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanStage = "扫描失败：${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun connectGateway() {
        val state = _uiState.value
        val client = RouterTrafficClient(state.gatewayUrl.trim(), state.gatewayToken.trim())
        gatewayClient = client
        counterJob?.cancel()
        viewModelScope.launch {
            try {
                val devices = client.listDevices()
                _uiState.update {
                    it.copy(
                        gatewayConnected = true,
                        gatewayDevices = devices,
                        gatewayStatus = "已连接：${devices.size} 台路由器设备",
                    )
                }
                counterJob = launch {
                    client.counters(1_000L)
                        .catch { error ->
                            _uiState.update {
                                it.copy(
                                    gatewayStatus = "流量计数中断：${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                        }
                        .collect { counters ->
                            counters.forEach { countersSample ->
                                val point = rateAccumulator.update(countersSample) ?: return@forEach
                                appendRate(countersSample.deviceId, point)
                                mapRouterRateToScannedDevice(countersSample.deviceId, point)
                            }
                        }
                }
            } catch (t: Throwable) {
                gatewayClient = null
                _uiState.update {
                    it.copy(
                        gatewayConnected = false,
                        gatewayStatus = "连接失败：${t.message ?: t.javaClass.simpleName}。请确认手机和代理路由器在同一网络。",
                    )
                }
            }
        }
    }

    fun startGuidedTrial() {
        if (_uiState.value.guidedRunning) return
        val zone = _uiState.value.zoneId
        guidedJob = viewModelScope.launch {
            val trialId = (_uiState.value.windows.maxOfOrNull { it.trialId } ?: -1) + 1
            val baselineStart = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    guidedRunning = true,
                    cue = "第 ${trialId + 1} 次：保持静止/正常使用，建立基线（15 秒）",
                )
            }
            kotlinx.coroutines.delay(BASELINE_MS)
            val baselineEnd = System.currentTimeMillis()

            val motionStart = System.currentTimeMillis()
            _uiState.update {
                it.copy(cue = "现在在「$zone」缓慢走动、挥手或转动身体（20 秒）")
            }
            kotlinx.coroutines.delay(MOTION_MS)
            val motionEnd = System.currentTimeMillis()

            val windows = listOf(
                PhaseWindow(trialId, WalkPhase.BASELINE, baselineStart, baselineEnd, zone),
                PhaseWindow(trialId, WalkPhase.MOTION, motionStart, motionEnd, zone),
            )
            _uiState.update {
                it.copy(
                    windows = it.windows + windows,
                    guidedRunning = false,
                    cue = "本次完成，建议换一个物理区域或再重复一次",
                )
            }
            reanalyzeMotion()
        }
    }

    fun cancelGuidedTrial() {
        guidedJob?.cancel()
        _uiState.update { it.copy(guidedRunning = false, cue = null) }
    }

    fun clearMotionWindows() {
        rateSeries.clear()
        rateAccumulator.reset()
        _uiState.update {
            it.copy(
                windows = emptyList(),
                motionStatus = "已清空走动窗口和速率样本",
                results = it.results.map { assessed ->
                    assessed.copy(assessment = assessmentEngine.assess(assessed.device, null))
                },
            )
        }
    }

    private fun appendRate(deviceId: String, point: RatePoint) {
        val series = rateSeries.getOrPut(deviceId) { mutableListOf() }
        series += point
        if (series.size > MAX_RATE_POINTS) {
            repeat(series.size - MAX_RATE_POINTS) { series.removeAt(0) }
        }
    }

    private fun mapRouterRateToScannedDevice(routerId: String, point: RatePoint) {
        val state = _uiState.value
        val gatewayDevice = state.gatewayDevices.firstOrNull {
            it.id.equals(routerId, ignoreCase = true) ||
                it.mac?.equals(routerId, ignoreCase = true) == true
        }
        val target = state.results.firstOrNull { assessed ->
            assessed.device.id.equals(routerId, ignoreCase = true) ||
                assessed.device.mac?.equals(routerId, ignoreCase = true) == true ||
                (gatewayDevice?.ip != null && assessed.device.ip == gatewayDevice.ip)
        }
        target?.let { appendRate(it.device.id, point) }
    }

    private fun reanalyzeMotion() {
        val state = _uiState.value
        if (state.windows.isEmpty()) return

        val rawResults = state.results.map { assessed ->
            val device = assessed.device
            val keyCandidates = listOfNotNull(device.id, device.mac, device.ip).distinct()
            val series = keyCandidates.firstNotNullOfOrNull { rateSeries[it] }
                .orEmpty()
                .toList()
            correlationAnalyzer.analyze(
                deviceId = device.id,
                points = series,
                windows = state.windows,
                zoneId = state.zoneId,
            )
        }
        val adjustedPValues = MultipleTesting.benjaminiHochberg(rawResults.map { it.pValue })
        val motionById = rawResults.mapIndexed { index, result ->
            val q = adjustedPValues[index]
            val corrected = when {
                result.reliable && q != null && q > FDR_Q_LIMIT -> result.copy(
                    reliable = false,
                    adjustedPValue = q,
                    reasons = result.reasons +
                        "同时检验多台设备后，Benjamini-Hochberg FDR 校正 q=${"%.3f".format(q)} 高于 $FDR_Q_LIMIT，已降权。",
                )

                else -> result.copy(adjustedPValue = q)
            }
            corrected.deviceId to corrected
        }.toMap()

        val updated = state.results.map { assessed ->
            assessed.copy(
                assessment = assessmentEngine.assess(
                    assessed.device,
                    motionById[assessed.device.id],
                ),
            )
        }.sortedByDescending { it.assessment.score }

        val reliable = motionById.values.count { it.reliable }
        _uiState.update {
            it.copy(
                results = updated,
                motionStatus = when {
                    rateSeries.isEmpty() -> "还没有收到路由器流量样本。"
                    reliable > 0 -> "有 $reliable 台设备在当前区域表现出可重复时序相关；已纳入评分。"
                    else -> "样本或重复次数不足，或没有设备出现稳定相关；这不会作为报警依据。"
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        counterJob?.cancel()
        guidedJob?.cancel()
        gatewayClient = null
    }

    companion object {
        private const val BASELINE_MS = 15_000L
        private const val MOTION_MS = 20_000L
        private const val MAX_RATE_POINTS = 600
        private const val FDR_Q_LIMIT = 0.05
    }
}
