# 中继节点部署（推荐方式）

**为什么推荐它**：两台手机通常在 NAT 后面互相连不上。传统做法是组 VPN，但那要在
**长辈手机上再装一个 App、再导入一份配置**。中继把这件事省掉了——
长辈手机只需要装我们自己的 App（和 Shizuku），控制端只需要输 6 位数字。

实现是 `relay/`（Rust + tokio），编译成一个单文件二进制。

---

## 它做什么

```
长辈端 App ──主动连出──▶ 中继节点 ◀──主动连出── 控制端 App
```

协议 v2：**常连接 + 按需开流**。

| 连接 | 谁发起 | 首行 | 之后 |
|---|---|---|---|
| 信令（常驻） | 长辈端 | `AGENT <口令哈希> signaling` | 每 10 秒 `PING` / `PONG` |
| 控制流 | 控制端 | `FIND <哈希> control` | 中继在信令上发 `OPEN control`，长辈端立刻开一条 `DATA` 连接上来对接 |
| 视频流 | 控制端 | `FIND <哈希> video` | 同上，角色为 `video` |
| 语音流 | 控制端 | `FIND <哈希> audio` | 同上，角色为 `audio`；一条连接双向传 16kHz PCM |

- 线上**只有口令的 SHA-256 哈希**，没有口令本身；不知道口令就配不上对。
- 中继**不解析、不落盘**内容，只在内存里保存"哈希 → 连接"的映射。
- 两台手机都不需要任何入站可达性（不需要公网 IP、不需要端口映射）。
- 配对是毫秒级的：`FIND` 一到，信令上立刻通知长辈端开流，不需要等下一次登记。
- **求助记录**：长辈端在信令上发一次 `HELP`（每次协助只发一次）；控制端可发
  `ASK <哈希>` 查询，回复 `HELP <Unix 秒>` 或 `NONE`，有效期 30 分钟。
  求助记录只在内存里，中继重启会丢失（这是有意的：不需要落盘任何用户数据）。

---

## 部署

```bash
# 一条命令：上传源码 → 节点上编译 → 安装 → 清理编译工具链与缓存
NODE=root@<节点> bash tools/deploy-relay.sh
```

脚本内部做的事（也可以手工执行）：

```bash
# 1. 节点准备（Debian/Ubuntu）
apt-get update && apt-get install -y gcc libc6-dev curl
curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal

# 2. 编译
cargo build --release            # 产物 relay/target/release/ac-relay

# 3. 安装
install -m755 target/release/ac-relay /usr/local/bin/ac-relay
```

systemd 单元（脚本会自动写）：

```ini
[Unit]
Description=AndroidControl relay
After=network.target

[Service]
ExecStart=/usr/local/bin/ac-relay --listen 0.0.0.0:27180
Restart=always
RestartSec=5
DynamicUser=yes
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

> **磁盘注意**：Rust 的 `target/` 和工具链加起来接近 1 GB。小盘节点编译完要清理：
> `cargo clean`、`rm -rf ~/.cargo ~/.rustup`。`tools/deploy-relay.sh` 会自动做，
> 只留下 `/usr/local/bin/ac-relay`（约 500 KB）。

---

## 配置两端

**长辈端**（部署时设置一次，之后自动连）：

```bash
adb shell am start -n dev.ling.agent/.MainActivity \
  --es relay_host <节点地址> --ei relay_port 27180
```

或在 App 内由部署者填写。设置会持久化，长辈端每次启动都会主动连上去。

**控制端**：在连接页「设置 → 中继节点」填 `<节点地址>:27180`，之后只需输入长辈手机上的 6 位数字。

---

## 健康检查

```bash
systemctl status ac-relay
journalctl -u ac-relay -n 50 --no-pager     # 连接/配对/断开都有日志
ss -ltn | grep 27180
```

日志会明确写出每一对连接的结束方向（`控制端→长辈端` 或 `长辈端→控制端` 先断），
排查"用着用着就断了"时先看这一行。

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
