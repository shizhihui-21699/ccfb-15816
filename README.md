# RoomRadar / 客房雷达

一款面向出差、旅行人群的 Android 局域网安全排查原型：在连上酒店/民宿 Wi-Fi 或自备旅行路由器后，通过

1. **局域网设备与服务发现**：TCP 端口、mDNS/DNS-SD、SSDP/UPnP、ONVIF WS-Discovery、ARP/neighbor best-effort；
2. **摄像头指纹评分与误报抑制**：ONVIF、RTSP 有效响应、海康/大华/XMEye 端口、摄像头 Banner/OUI，同时识别 Chromecast、AirPlay、Android/Google TV、Sonos/Echo 等；
3. **引导式分区走动 + 加密流量元数据相关性**：需要配对的旅行路由器/合作路由器提供每设备计数器，不破解加密、不做 ARP 欺骗；
4. **克制的风险表达和人工排查指引**：输出“高优先排查 / 建议复核 / 弱信号 / 未见异常”，而不是宣称“已找到摄像头”。

> **重要结论**：加密不是主要问题，可见性才是。加密摄像头仍会在人进入画面时改变 VBR 码率、包速率和包长分布；但普通 Android App 无法看到其他 Wi-Fi 设备的流量。`VpnService` 只接管手机自己的流量，WPA2/WPA3 单播密钥也阻止普通模式旁听其他客户端。因此可靠的流量变化检测必须借助用户可控路由器/AP 的每设备计数器。

## 当前代码结构

```text
.
├── core/                         # 纯 Kotlin/JVM，可单测，不依赖 Android
│   └── src/main/kotlin/com/roomradar/core/
│       ├── model/                # LanDevice、Evidence、Assessment、NetworkSnapshot
│       ├── net/IPv4Cidr.kt       # 安全的 /24 扫描范围，避免扫整个 /8 /16
│       ├── detect/
│       │   ├── AssessmentEngine.kt
│       │   └── OuiClassifier.kt
│       └── traffic/
│           ├── MotionCorrelationAnalyzer.kt
│           ├── RateAccumulator.kt
│           └── DeviceTrafficGateway.kt
├── android/                      # Android Compose + 扫描实现
│   └── src/main/kotlin/com/roomradar/android/
│       ├── net/                  # Wi-Fi 信息、TCP、NSD、SSDP/ONVIF、ARP
│       ├── scan/                 # 合并发现结果
│       ├── traffic/              # 路由器代理 HTTP client
│       └── ui/                   # Compose 页面和 RadarViewModel
└── router-agent/
    ├── API.md                    # 每设备流量代理接口与能力边界
    └── reference_agent.py        # Linux/OpenWrt 参考实现：iw station dump
```

## 快速验证核心算法

本机需要 JDK 17+。仓库已带 Gradle Wrapper：

```bash
./gradlew :core:test
```

当前核心测试覆盖：

- `/24` 范围生成，避免大规模扫描；
- 仅开放 80 端口不会报警；
- ONVIF + 有效 RTSP 响应进入高优先排查；
- Chromecast/电视特征会抑制通用端口/OUI/流量相关性；
- 单独流量相关不能把未知设备提升到复核/高风险；
- 重复、显著、方向一致的流量变化才被判定为 reliable；
- 平坦流量和单次试验不会触发流量证据。

## 构建 Android App

1. 安装 Android SDK Platform 35、Build-Tools 和 Platform-Tools；
2. 复制 `local.properties.example` 为 `local.properties`，设置 `sdk.dir`；
3. 打开项目，或运行：

```bash
./gradlew :android:assembleDebug
```

没有 `local.properties` 且未设置 `ANDROID_HOME` 时，`settings.gradle.kts` 只包含 `:core`，方便在无 Android SDK 的 CI/环境中运行 JVM 测试。

## 扫描策略

### 1. 确定安全范围

从 `LinkProperties` 读取本机 IPv4 和前缀。即使酒店下发 `/8` 或 `/16`，也默认只扫描本机所在 `/24`（254 个地址），避免对大型住客网络进行慢速、扰民甚至有法律风险的全网段扫描。

### 2. 多源发现

- **mDNS/NSD**：`_onvif._tcp`、`_axis-video._tcp`、`_rtsp._tcp`、`_camera._tcp`，以及 `_googlecast._tcp`、`_airplay._tcp`、`_raop._tcp`、`_androidtvremote._tcp`、`_sonos._tcp` 等干扰源；
- **SSDP/UPnP**：`239.255.255.250:1900` M-SEARCH；
- **ONVIF WS-Discovery**：`239.255.255.250:3702` Probe `dn:NetworkVideoTransmitter`；
- **TCP 指纹**：
  - `554/8554` RTSP，并发送非侵入式 `OPTIONS`；
  - `80/443/8080/8443` 等 HTTP；
  - `8000` 海康常见 SDK 端口；
  - `37777` 大华常见端口；
  - `34567` XMEye/雄迈常见端口；
  - `1935` RTMP；
- **neighbor/ARP**：尽力读取 `/proc/net/arp`，现代 Android 上可能为空或只含少量条目。

端口探测只做 TCP 连接和一次无认证协议 Banner/OPTIONS 读取，不包含爆破、默认密码尝试、漏洞利用或拒绝服务。

### 3. 评分和误报抑制

高权重信号：

- ONVIF 服务；
- RTSP `OPTIONS/DESCRIBE` 返回 `RTSP/1.0 ...` 或 SDP；
- 摄像头 HTTP Banner/品牌；
- 海康/大华/XMEye 协议或端口；
- Axis/ONVIF mDNS。

低权重信号：

- 仅开放 Web 端口；
- OUI 前缀；
- 通用 “camera” 文本。

已知媒体设备抑制：

- Google Cast/Android TV、AirPlay/RAOP、Sonos、Spotify Connect、HomeKit；
- 名称含 Chromecast、Bravia、Fire TV、Roku、Echo、HomePod、Soundbar 等；
- Google、Amazon、Apple、Roku、Sonos、Bose、Samsung/LG/Sony/TV 等厂商。

如果设备有明确电视/音箱画像，通用端口、OUI 和走动相关性会大幅降权；只有出现两个以上难以解释的摄像头硬协议信号时才可能进入高优先级。

## 走动流量相关性算法

`MotionCorrelationAnalyzer` 只使用加密流量之外的元数据：

```text
累计计数器 -> RateAccumulator 差分 -> bytes/s、packets/s
引导 UI 记录 BASELINE / MOTION 时间窗和物理 zone
对每个候选设备、每个区域：
  1. 按 0/0.5/.../3s lag 对齐；
  2. 每个 trial 计算运动窗相对基线窗的 log-rate 中位效应；
  3. 至少 2 次有效重复；
  4. 要求实际增幅和绝对增幅；
  5. trial 间一致性 >= 2/3；
  6. trial 内置换检验 p <= 0.05，并对 lag 搜索做 max-statistic 校正；
  7. 多设备同时检验时再做 Benjamini-Hochberg FDR 校正；
  8. 输出 0..100 的证据分，但不是“摄像头概率”。
```

只有 `reliable=true` 时，`AssessmentEngine` 才把最多约 18 分加入局域网指纹分。**流量信号永远不能单独产生高风险结论**。这是为了控制电视缓冲、云同步、手机备份等干扰。

无相关性也不能排除摄像头：设备可能使用恒定码率、人物未进入视角、摄像头休眠，或流量经过 Zigbee/蓝牙网关/另一 VLAN 上行。

## Android 权限和新版本限制

当前 App 目标 SDK 35：

- `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`；
- `CHANGE_WIFI_MULTICAST_STATE`：接收 mDNS/SSDP/ONVIF 组播时持有 `MulticastLock`；
- Android 12 及以下使用 `ACCESS_FINE_LOCATION` 读取 SSID；
- Android 13+ 使用 `NEARBY_WIFI_DEVICES` + `neverForLocation`。

根据 Android 文档，**targetSdk 37/Android 17 起将强制本地网络权限**，权限名为 `android.permission.ACCESS_LOCAL_NETWORK`，属于 `NEARBY_DEVICES` 权限组；新 targetSdk App 默认可能无法访问本地网络。升级 compileSdk 37 后需要：

1. 在 Manifest 声明 `ACCESS_LOCAL_NETWORK`；
2. 设计运行时授权解释和拒绝后的降级；
3. 对“只选择一个设备”的场景优先接入 `NsdManager` 系统服务选择器，避免申请广泛本地网络访问；
4. 建立无本地网络权限时的物理排查/自备路由器引导。

参考：

- [Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission?hl=zh-cn)
- [Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android NSD 文档](https://developer.android.com/reference/android/net/nsd/NsdManager)

## 推荐产品化路线

### MVP（当前仓库已具雏形）

- 核心评分/相关性纯 Kotlin 模块和单测；
- Compose 扫描结果页、走动测试页、排查指南；
- TCP/NSD/SSDP/ONVIF 扫描器；
- 路由器代理 API 和 Linux 参考代理。

### Beta 前必须补齐

- 接入维护中的 IEEE OUI/品牌数据库，并支持应用内更新；
- 对 ONVIF `GetDeviceInformation`、RTSP `DESCRIBE` 做更多无认证指纹样本和误报回归库；
- 建立真实设备测试集：海康/大华/小米/TP-Link/Reolink 摄像头，以及 Chromecast、Apple TV、HomePod、Sonos、Echo、三星/LG/索尼电视；
- 用真实 pcap/路由器计数器回放校准相关性阈值；
- 路由器代理配对、HTTPS/Pinning、Token 轮换；
- Android 17 `ACCESS_LOCAL_NETWORK` 和 NSD picker 适配；
- 扫描速率、电量、超时和取消机制调优；
- 增加镜头反光/红外扫描、蓝牙/BLE 发现等互补检查，但不要夸大为可靠检测。

### 商业化/数据闭环

- 与旅行路由器或家用路由器厂商合作，预装签名代理；
- 允许用户导出脱敏扫描结果用于改进指纹，但默认不上传 SSID/MAC/IP；
- 维护“已知 benign 设备指纹库”和“摄像头协议指纹库”；
- 对高风险结果提供附近线下排查流程，而不是只给一个分数。

## 安全与合规边界

- 只扫描用户当前入住、已合法接入的网络，且限制速率和范围；
- 不提供默认密码爆破、漏洞利用、去认证、ARP 欺骗、流量劫持；
- 不收集真实画面或解密内容；
- 路由器元数据默认只在局域网内处理；
- UI 必须避免确定性指控，避免误报造成冲突或恐慌。

## 免责声明

本项目是安全排查辅助工具，不保证发现所有隐藏摄像头，也不能替代专业反窃照服务和现场检查。不同地区对网络扫描、流量统计和隐私数据处理的法律要求不同，商用前需要法律审查。
