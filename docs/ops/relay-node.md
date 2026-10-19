# 中继节点部署（推荐方式）

**为什么推荐它**：两台手机通常在 NAT 后面互相连不上。传统做法是组 VPN，但那要在
**长辈手机上再装一个 App、再导入一份配置**。中继把这件事省掉了——
长辈手机只需要装我们自己的 App（和 Shizuku），控制端只需要输 6 位数字。

---

## 它做什么

```
长辈端 App ──主动连出──▶ 中继节点 ◀──主动连出── 控制端 App
     │                       │
     └── 登记 AGENT <口令哈希> control / video
                             └── 收到 FIND <口令哈希> 时配对，并把两条连接对接
```

- 线上**只有口令的哈希**，没有口令本身；不知道口令就配不上对。
- 中继**不解析、不落盘**内容，只在内存里保存"哈希 → 连接"的映射。
- 两台手机都不需要任何入站可达性（不需要公网 IP、不需要端口映射）。

---

## 部署

```bash
# 1. 把 relay-server.py 放到节点上（任意有公网 IP 的机器）
scp tools/relay-server.py root@<节点>:/opt/androidcontrol/

# 2. 常驻运行（systemd）
cat > /etc/systemd/system/ac-relay.service <<'EOF'
[Unit]
Description=AndroidControl relay
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/androidcontrol/relay-server.py --listen 0.0.0.0:27180
Restart=always
RestartSec=5
User=nobody

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now ac-relay
```

> 中继只做转发，权限给 nobody 就够。

---

## 配置两端

**长辈端**（部署时设置一次，之后自动连）：

```bash
adb shell am start -n dev.ling.agent/.MainActivity \
  --es relay_host <节点地址> --ei relay_port 27180
```

或在 App 内由部署者填写。设置会持久化，长辈端每次启动都会主动连上去。

**控制端**：在连接页"中继节点"填 `<节点地址>:27180`，之后只需输入长辈手机上的 6 位数字。

---

## 安全提示

- 中继是**明文的字节转发**。它适合放在自己的机器/内网里；如果必须暴露在公网，
  建议只在内网或隧道内监听，或在前端加一层 TLS 终止。
- 配对靠"知道口令"来授权，口令是 6 位数字（100 万种组合）。在公网暴露中继时，
  建议配合 fail2ban 或限制来源，避免被暴力试。
- 屏幕上出现的任何内容都经过中继，但中继不落盘；如需更强保证，请自行加 TLS。

---

## 与 WireGuard 方案的关系

两条路都支持，按场景选：

| | 中继 | WireGuard |
|---|---|---|
| 长辈手机要装什么 | 只有我们的 App | App + WireGuard + 导入配置 |
| 控制端要输什么 | 6 位数字 | 6 位数字（隧道内固定 IP） |
| 加密 | 无（明文转发，见上） | 链路加密 |
| 适合 | 快速部署、不想折腾 VPN | 已在用 VPN、或对加密有硬要求 |

中继是**默认推荐**：少一个 App、少一类故障源。要加密就用 WireGuard 那套
（见 `node-wireguard.md`）。
