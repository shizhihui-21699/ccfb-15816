package com.roomradar.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("如何降低漏报和误报", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Card {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Section(
                        "1. 优先使用自备旅行路由器",
                        "入住后让手机和房间设备都加入你的旅行路由器。这样可以绕过酒店 AP 隔离，" +
                            "并通过本地代理获得每设备流量计数；不需要攻击酒店网络。",
                    )
                    Section(
                        "2. 扫描结果只作为线索",
                        "ONVIF、RTSP 有效响应、海康/大华/XMEye 端口和摄像头横幅权重高；" +
                            "普通 80/443 端口或 MAC OUI 权重低。Chromecast、AirPlay、Sonos、" +
                            "Android TV 等会触发媒体设备降权。",
                    )
                    Section(
                        "3. 走动测试必须重复",
                        "加密摄像头通常采用 VBR 编码，人进入画面会改变包大小和码率；但恒定码率、" +
                            "云同步、电视缓冲也可能变化。至少两个 trial、置换检验显著且方向一致才计分。",
                    )
                    Section(
                        "4. 物理排查仍然不可替代",
                        "检查烟感、USB 充电头、插座、闹钟、路由器、机顶盒、挂钩和浴室/卧室正对床的位置。" +
                            "关灯寻找红外补光灯，用手电筒寻找镜头反光，也可使用专用镜头探测器。",
                    )
                    Section(
                        "5. 提示话术要克制",
                        "高风险也应表述为“发现多个与摄像头一致的特征，建议人工排查”，" +
                            "不要显示“已确认针孔摄像头”或给出法律结论，避免误报吓坏用户。",
                    )
                    Section(
                        "6. 明确能力边界",
                        "AP 隔离、不同 VLAN、摄像头休眠、蓝牙/Zigbee 网关上行、恒定码率、" +
                            "Android 17 ACCESS_LOCAL_NETWORK 授权拒绝，都可能导致漏检。" +
                            "应用必须持续展示“未发现不等于安全”。",
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, fontWeight = FontWeight.Bold)
    Text(body, style = MaterialTheme.typography.bodyMedium)
}
