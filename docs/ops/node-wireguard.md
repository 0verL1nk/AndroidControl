# 节点搭建指南（WireGuard）

本项目需要一个有公网 IP 的服务器做隧道 hub。本指南适用于任意 Debian/Ubuntu VPS。
**你自己的节点地址、密钥等实例信息请放在 `.secrets/` 目录下（已被 .gitignore 忽略），
不要提交到仓库。**

也可以直接跑自动化脚本：`sudo bash tools/setup-node.sh`（见文末）。

## 前置条件

| 项 | 要求 |
|---|---|
| 服务器 | 任意云 VPS，有公网 IPv4，Debian 12+/Ubuntu 22.04+ |
| 权限 | root 或 sudo |
| 端口 | UDP 51820 可入站（云厂商安全组 + 系统防火墙都要放行） |
| 规格 | ≥1 核 512MB。视频按 2Mbps 规划，故出口带宽 ≥5Mbps 更稳 |

延迟建议：**优先选离长辈所在地近的节点**。控制端与长辈端都要绕到节点，节点越近体验越好。

## 1. 安装

```bash
apt-get update && apt-get install -y wireguard-tools qrencode
sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wireguard.conf
```

`ip_forward` 必须为 1，否则两个对端之间无法互通（只能各自与服务器通信）。

## 2. 生成密钥

```bash
umask 077; mkdir -p /root/wg-clients; cd /root/wg-clients
wg genkey | tee server.key | wg pubkey > server.pub
for n in controller elder; do
  wg genkey | tee $n.key | wg pubkey > $n.pub
done
```

## 3. 服务端配置 `/etc/wireguard/wg0.conf`

```ini
[Interface]
Address    = 10.66.66.1/24
ListenPort = 51820
PrivateKey = <server.key 的内容>

[Peer]
# controller（控制端）
PublicKey  = <controller.pub 的内容>
AllowedIPs = 10.66.66.2/32

[Peer]
# elder（长辈端）
PublicKey  = <elder.pub 的内容>
AllowedIPs = 10.66.66.3/32
```

```bash
chmod 600 /etc/wireguard/wg0.conf
systemctl enable --now wg-quick@wg0
wg show
```

## 4. 对端配置

**关键**：`AllowedIPs` 只写隧道网段，这样手机上只有去往隧道的流量走 VPN，
**不影响长辈手机正常上网**（分流）。

```ini
[Interface]
PrivateKey = <该对端的私钥>
Address    = 10.66.66.3/24
DNS        = 223.5.5.5
MTU        = 1380

[Peer]
PublicKey  = <server.pub 的内容>
Endpoint   = <NODE_PUBLIC_IP>:51820
AllowedIPs = 10.66.66.0/24
PersistentKeepalive = 25
```

手机上装 WireGuard App，扫描配置的二维码导入：

```bash
qrencode -t ansiutf8 < controller.conf     # 终端直接显示二维码
qrencode -o controller.png -r controller.conf   # 或生成图片
```

## 5. 验证

```bash
# 服务器侧
wg show                      # 应能看到 peer 与 latest handshake

# 手机侧（控制端 ping 长辈端）
ping 10.66.66.3
```

## 6. 常见问题

| 现象 | 排查 |
|---|---|
| `wg show` 无 handshake | 安全组/防火墙没放行 UDP 51820；或 Endpoint 写错 |
| 能 ping 服务器但两端互 ping 不通 | `ip_forward` 未开 |
| 手机连上后无法上网 | 检查 `AllowedIPs` 是否误写成 `0.0.0.0/0` |
| 握手成功但传大流量卡 | MTU 调到 1280 |
| 部分运营商网络连不上 | 换端口（如 443/UDP），部分网络只放行常见端口 |

## 7. 运维与安全

- **不要**把私钥、配置文件提交到仓库；本项目 `.gitignore` 已忽略 `.secrets/`。
- 新增对端：生成密钥 → 追加 `[Peer]` → `systemctl restart wg-quick@wg0`。
- 吊销对端：删除对应 `[Peer]` 段并重启。WireGuard 无吊销列表，删除即失效。
- 定期在服务器上 `wg show` 确认没有陌生 peer。
- 建议加健康检查：10 分钟无握手则告警（见下）。

```bash
# /usr/local/bin/wg-healthcheck.sh（示例）
#!/bin/bash
hs=$(wg show wg0 latest-handshakes | awk '{print $2}' | sort -rn | head -1)
now=$(date +%s)
[ $((now - hs)) -gt 600 ] && echo "WireGuard: 10 分钟无握手" | logger -t wg
```

```bash
# crontab -e
*/10 * * * * /usr/local/bin/wg-healthcheck.sh
```

## 8. 一键脚本

```bash
git clone <本项目>
cd AndroidControl
sudo bash tools/setup-node.sh            # 安装并生成服务端 + 两个对端配置
sudo bash tools/setup-node.sh --peer phone2   # 追加一个对端
```

脚本做的事：装依赖、开转发、生成密钥、写 `wg0.conf`、启动服务、生成对端配置与二维码、
打印下一步提示。所有产物默认落在 `/root/wg-clients/`（700 权限）。
