package com.roomradar.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roomradar.core.model.Confidence
import com.roomradar.core.model.DeviceAssessment
import com.roomradar.core.model.ThreatLevel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    state: RadarUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text("客房针孔摄像头排查", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "通过局域网指纹、mDNS/SSDP/ONVIF 和可选的路由器流量时序分析降低漏报与误报。结果是风险线索，不是法律意义上的认定。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (!permissionsGranted) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("需要附近 Wi-Fi/位置权限以读取 SSID 和发现局域网服务")
                        Text(
                            "拒绝后仍可扫描 IP 和端口，但网络名称、部分系统发现结果可能不可用。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = onRequestPermissions) { Text("重新授权") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("网络：${state.network?.ssid ?: "SSID 不可见"}")
                    Text("本机地址：${state.network?.ownIpv4 ?: "未知"}")
                    Text("网关：${state.network?.gatewayIpv4 ?: "未知"}")
                    Text(state.scanStage, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onScan, enabled = !state.isScanning) {
                        if (state.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            Text("扫描中…")
                        } else {
                            Text("开始扫描当前 /24")
                        }
                    }
                }
            }
        }

        if (state.warnings.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("扫描限制", fontWeight = FontWeight.Bold)
                        state.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        items(state.results, key = { it.device.id }) { assessed ->
            DeviceCard(assessed)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(assessed: AssessedDevice) {
    var expanded by remember { mutableStateOf(false) }
    val device = assessed.device
    val assessment = assessed.assessment

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name ?: device.services.firstOrNull()?.name ?: "未知设备",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(device.ip, device.mac).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LevelBadge(assessment)
            }

            Text(assessment.headline, style = MaterialTheme.typography.bodyMedium)
            Text(
                "风险分 ${assessment.score}/100 · 置信度 ${assessment.confidence.label()}",
                style = MaterialTheme.typography.labelMedium,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assessment.evidence.filter { it.weight > 0 }.take(4).forEach { evidence ->
                    AssistChip(onClick = { expanded = true }, label = { Text(evidence.code) })
                }
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起详情" else "查看证据与处置建议")
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    assessment.evidence.forEach { evidence ->
                        Column {
                            Text(
                                (if (evidence.benign) "降权/排除：" else "证据：") + evidence.title,
                                fontWeight = FontWeight.Medium,
                                color = if (evidence.benign) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            evidence.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (device.ports.isNotEmpty()) {
                        Text(
                            "端口：" + device.ports.joinToString { "${it.port}/${it.protocol ?: "?"}" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (device.services.isNotEmpty()) {
                        Text(
                            "服务：" + device.services.take(8).joinToString { it.type },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HorizontalDivider()
                    Text("建议", fontWeight = FontWeight.Bold)
                    assessment.recommendedActions.forEach { action ->
                        Text("• $action", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelBadge(assessment: DeviceAssessment) {
    val (label, container) = when (assessment.level) {
        ThreatLevel.HIGH -> "高优先排查" to MaterialTheme.colorScheme.errorContainer
        ThreatLevel.REVIEW -> "建议复核" to MaterialTheme.colorScheme.secondaryContainer
        ThreatLevel.LOW -> "弱信号" to MaterialTheme.colorScheme.surfaceVariant
        ThreatLevel.NO_SIGNAL -> "未见异常" to MaterialTheme.colorScheme.surface
    }
    androidx.compose.material3.SuggestionChip(
        onClick = {},
        label = { Text(label) },
        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
            containerColor = container,
        ),
    )
}

private fun Confidence.label(): String = when (this) {
    Confidence.INSUFFICIENT -> "不足"
    Confidence.LOW -> "低"
    Confidence.MEDIUM -> "中"
    Confidence.HIGH -> "高"
}
