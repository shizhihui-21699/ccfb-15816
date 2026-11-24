package com.roomradar.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WalkScreen(
    state: RadarUiState,
    onGatewayUrlChange: (String) -> Unit,
    onGatewayTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onZoneChange: (String) -> Unit,
    onStartTrial: () -> Unit,
    onCancelTrial: () -> Unit,
    onClearWindows: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text("走动—加密流量相关性", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "加密隐藏的是内容，不是“何时产生/速率如何变化”。但普通 Android 应用看不到其他设备的流量，" +
                    "必须由你可控的旅行路由器或合作路由器提供每设备字节/包计数。不要使用 ARP 欺骗或中间人方案。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("路由器流量代理", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = state.gatewayUrl,
                        onValueChange = onGatewayUrlChange,
                        label = { Text("代理地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.gatewayToken,
                        onValueChange = onGatewayTokenChange,
                        label = { Text("配对 Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onConnect) { Text(if (state.gatewayConnected) "重新连接" else "连接并开始取计数") }
                    Text(state.gatewayStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("分区引导测试", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = state.zoneId,
                        onValueChange = onZoneChange,
                        label = { Text("当前物理区域") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "建议区域：门口/玄关、床头正对墙面、烟感或空调出风口、书桌/路由器、浴室门口。" +
                            "每个区域重复 2–3 次；若同一设备只在某一区域响应，优先检查该方向的物体。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.guidedRunning) {
                        Text(state.cue ?: "计时中…", fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = onCancelTrial) { Text("取消本次") }
                    } else {
                        Button(onClick = onStartTrial, enabled = state.gatewayConnected) {
                            Text("开始一次 15 秒静止 + 20 秒走动")
                        }
                    }
                    OutlinedButton(onClick = onClearWindows) { Text("清空采样窗口") }
                    Text(
                        "已记录 ${state.windows.count { it.phase.name == "BASELINE" }} 次基线、" +
                            "${state.windows.count { it.phase.name == "MOTION" }} 次走动窗口。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(state.motionStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        items(state.results.take(20), key = { it.device.id }) { assessed ->
            val motionEvidence = assessed.assessment.evidence.firstOrNull {
                it.code.startsWith("MOTION_CORRELATION")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(assessed.device.name ?: assessed.device.ip ?: assessed.device.id, fontWeight = FontWeight.Bold)
                    Text("风险分 ${assessed.assessment.score}/100 · ${assessed.assessment.level}")
                    Text(
                        motionEvidence?.title ?: "尚无可靠走动相关性",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
