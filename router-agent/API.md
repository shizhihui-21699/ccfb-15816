# RoomRadar 流量代理 API

普通 Android 应用**无法**看到同一 Wi-Fi 下其他设备的单播流量内容或速率：

- `VpnService` 只能接管本机流量，不能镜像摄像头流量；
- WPA2/WPA3 下每个客户端有独立密钥，普通关联模式无法解密其他设备的单播帧；
- `/proc/net/netstat`、`TrafficStats` 只提供本机/本 UID 统计；
- 无 root/无 monitor 模式时，无法可靠获得摄像头的逐包长度时序；
- ARP 欺骗、未经授权的中间人或去认证攻击不应进入商用产品，也可能违法。

因此“走动—流量相关性”需要一个用户可控的网络数据面。推荐产品形态是：**与便携旅行路由器/路由器 OEM 合作，或销售预装代理的旅行路由器**。手机和可疑设备都接入该路由器后，代理通过 AP 站点统计、nftables/conntrack 或交换芯片计数器提供每设备累计计数。

## 参考实现

`reference_agent.py` 是仅依赖 Python 标准库的 Linux/OpenWrt 参考代理。它使用：

- `iw dev <iface> station dump` 获取 Wi-Fi 站点的 `rx/tx bytes/packets`；
- `ip neigh` 将 MAC 映射到 IP；
- `/tmp/dhcp.leases` 读取 dnsmasq 主机名。

在支持 Python 的 Linux 小主机或路由器上运行：

```bash
ROOMRADAR_TOKEN='change-me' \
WIFI_IFACES='wlan0,wlan1' \
ROOMRADAR_AGENT_HOST='192.168.8.1' \
python3 reference_agent.py
```

OpenWrt 若没有 Python，可将同样的解析逻辑用 ucode/Shell/Go/Kotlin/Native 实现，或做成路由器插件。

## 接口

所有接口返回 JSON。建议使用 HTTPS，并通过配对二维码携带主机、Token 和证书/公钥指纹。

### `GET /healthz`

```json
{ "ok": true }
```

### `GET /api/v1/devices`

请求头：

```http
Authorization: Bearer <token>
```

响应：

```json
[
  {
    "id": "AA:BB:CC:DD:EE:FF",
    "mac": "AA:BB:CC:DD:EE:FF",
    "ip": "192.168.8.23",
    "hostname": "IPCamera",
    "transport": "wifi"
  }
]
```

### `GET /api/v1/counters`

返回单调递增的累计计数器。App 端用 `RateAccumulator` 对相邻样本做差并除以时间：

```json
[
  {
    "deviceId": "AA:BB:CC:DD:EE:FF",
    "mac": "AA:BB:CC:DD:EE:FF",
    "ip": "192.168.8.23",
    "timestampMs": 1764000000000,
    "rxBytes": 18392033,
    "txBytes": 90218831,
    "rxPackets": 12884,
    "txPackets": 64210
  }
]
```

计数器重启或回绕时可以变小；App 会丢弃负增量并重新建立基线。

## 更完整的产品化数据

仅有字节/包速率已经可以做时序相关。若路由器性能够，可额外提供不含 payload 的元数据：

- 包长度分桶，例如 `<128B / 128–512B / 512–1500B`；
- 上下行方向；
- DNS/SNI 或连接域名（仅用于识别已知 OTT/云服务，需明确隐私授权）；
- 连接对端 IP/ASN；
- 802.11 MCS、重传率、RSSI。

这些数据能更好区分电视缓冲、手机云备份和摄像头 VBR 突变，但也涉及更高隐私风险，应默认最小化采集、局域网处理、不上传原始元数据。
