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

## TLS（公网部署时必开）

中继支持两条监听并存，**共用同一份配对表**——一端走 TLS、另一端走明文也能配上对：

| 监听 | 用途 | 建议 |
|---|---|---|
| `--tls-listen 0.0.0.0:27443` | 手机直连（长辈端/控制端 App） | 公网只开放这一个 |
| `--listen 127.0.0.1:27180` | Cloudflare 隧道 / 本机反代（网页控制端） | 只绑回环 |

证书用**自签**即可：两端都靠 **SHA-256 指纹固定**认人，不依赖公共 CA，
也不会因为某张根证书被误签而连错节点。指纹优先于主机名，换证书时必须同步改配置。

### 生成证书（节点上，一次即可）

```bash
sudo mkdir -p /etc/ac-relay && sudo chmod 700 /etc/ac-relay
sudo openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
  -keyout /etc/ac-relay/key.pem -out /etc/ac-relay/cert.pem \
  -subj "/CN=ac-relay" -addext "subjectAltName=DNS:relay.local,IP:127.0.0.1"

# 记下指纹（配到手机端）
openssl x509 -in /etc/ac-relay/cert.pem -noout -fingerprint -sha256
```

`tools/deploy-relay.sh` 在 `TLS_PORT` 非空时会自动完成这一步（已有证书则沿用）。

### systemd

私钥不设全局可读，用 `LoadCredential` 交给服务（`DynamicUser` 也能读）：

```ini
[Unit]
Description=AndroidControl relay
After=network.target

[Service]
ExecStart=/usr/local/bin/ac-relay \
  --listen 127.0.0.1:27180 \
  --tls-listen 0.0.0.0:27443 \
  --tls-cert %d/cert --tls-key %d/key
LoadCredential=cert:/etc/ac-relay/cert.pem
LoadCredential=key:/etc/ac-relay/key.pem
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

### 两端怎么配

| 端 | 配置项 | 值 |
|---|---|---|
| 长辈端 | `relay_host` / `relay_port` / `relay_fingerprint`（intent 或 `local.properties`） | 节点地址 / `27443` / 上面那串指纹 |
| 控制端 | 中继地址 / 指纹（`local.properties` 的 `relay.defaultAddress`、`relay.tlsFingerprint`） | 同上 |
| 网页端 | `NEXT_PUBLIC_RELAY_URL` | `wss://relay.<你的域名>`（证书由 Cloudflare 负责，与指纹无关） |

`local.properties` 不入库；仓库里只放占位符。构建示例：

```properties
relay.defaultAddress=relay.example.com:27443
relay.tlsFingerprint=AA:BB:CC:…（64 位十六进制，冒号可有可无）
```

指纹不匹配时长辈端会在握手阶段失败并打印"证书指纹不匹配"，不会把口令发出去。

### 本机快速验证

```bash
# 握手 + 协议一句话（未登记的口令应回 ERR）
printf 'FIND %064d control\n' 0 |
  openssl s_client -quiet -connect 127.0.0.1:27443 2>/dev/null

# 用真实客户端代码验证固定与拒绝（不需要手机）
AC_RELAY_PROBE_HOST=<节点> AC_RELAY_PROBE_PORT=27443 \
AC_RELAY_PROBE_FINGERPRINT="$(openssl x509 -in /etc/ac-relay/cert.pem -noout -fingerprint -sha256 | sed 's/^.*=//')" \
./gradlew :protocol:test --tests '*TlsPinningLiveTest*'
```

---

## 配对限速与审计

公网上 6 位数字（100 万种）不能靠"猜不中"来保证安全，所以中继内置了失败计数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--max-fails` | `10` | 窗口内允许的配对失败次数；`0` 关闭限速 |
| `--fail-window` | `600` | 统计窗口（秒） |
| `--lock-secs` | `600` | 超过阈值后的锁定时间（秒） |

- 按**口令哈希**计数；直连来源还额外按 **IP** 计数（经隧道时来源都是回环，只按哈希）。
- 锁定期间 `FIND` 直接回 `ERR`，**不会**惊动长辈端；到期后计数从零开始。
- 配对成功会清零哈希与来源 IP 的记录（避免同一出口被别人的失败连坐）。
- `ASK`（查求助）**不计数**：控制端会周期性查询，`NONE` 不是猜口令。
- 审计只记哈希前 8 位与 IP，不记口令：

```
AUDIT pair_failed hash=1a2b3c4d ip=203.0.113.7 role=control count=3
AUDIT locked hash=1a2b3c4d until=1792841751
AUDIT pair_blocked hash=1a2b3c4d ip=203.0.113.7 role=control
```

---

## 网页控制端（可选）

`web/` 是 Next.js 的浏览器控制端，复用同一个中继与同一套协议：

- 浏览器打开两条 WebSocket：`video <哈希>` 与 `control <哈希>`，
  中继按角色把它们和长辈端的数据流对接（和 App 的控制端完全一样）；
- 页面用 WebCodecs 解码 H.264，触摸与按键通过控制流注入。

部署步骤：

1. **部署前端**：在 Vercel 导入本仓库，Root Directory 选 `web/`，
   环境变量设置 `NEXT_PUBLIC_RELAY_URL=wss://<你的中继域名>`。
   HTTPS 页面不能连 `ws://`，所以必须是 `wss://`。
2. **给中继一个 WSS 入口**（中继本身只管字节，不做 HTTP 证书）：
   - 最简单：Cloudflare Tunnel。
     临时试用：`cloudflared tunnel --url http://127.0.0.1:27180`（地址每次重启都变）；
     长期使用：在 Cloudflare 建一条命名隧道，把 `relay.<你的域名>` 指到
     `http://127.0.0.1:27180`（WebSocket 直通，证书由 Cloudflare 负责）。
   - 或者在节点上放 Caddy / Nginx 做 TLS 终止，反代 `/ws` 到 `127.0.0.1:27180`。
3. 浏览器要求**安全上下文**（HTTPS 或 localhost）才能使用 WebCodecs 与加密接口。

> 中继的 `GET /` 只返回一行说明文本，不再内嵌网页；前端与中继可以分开部署。

> **也可以让同一条隧道顺便给前端一个域名**（不想另外配 DNS 时）：
> 在隧道的 `ingress` 里加一条转发到 Vercel，并把 Host 头改写成 Vercel 认识的域名：
>
> ```yaml
> - hostname: assist.<你的域名>
>   service: https://<你的项目>.vercel.app
>   originRequest:
>     httpHostHeader: <你的项目>.vercel.app
> ```
>
> 然后 `cloudflared tunnel route dns ac-relay assist.<你的域名>`。
> 代价是前端多经过一次节点（Cloudflare → 节点 → Vercel）；介意这一跳的话，
> 直接在 Cloudflare 加一条 CNAME 指向 `cname.vercel-dns.com` 更好。
> 注意配置要写在 systemd 服务实际读取的 `/etc/cloudflared/config.yml`。

---

## 部署

```bash
# 只开明文（内网/隧道后端）
NODE=root@<节点> bash tools/deploy-relay.sh

# 公网部署：明文收紧到回环，另开 TLS（推荐）
NODE=root@<节点> TLS_PORT=27443 bash tools/deploy-relay.sh
```

脚本内部做的事（也可以手工执行）：

```bash
# 1. 节点准备（Debian/Ubuntu）
apt-get update && apt-get install -y gcc libc6-dev curl openssl
curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal

# 2. 编译
cargo build --release            # 产物 relay/target/release/ac-relay

# 3. 安装
install -m755 target/release/ac-relay /usr/local/bin/ac-relay
```

> **磁盘注意**：Rust 的 `target/` 和工具链加起来接近 1 GB。小盘节点编译完要清理：
> `cargo clean`、`rm -rf ~/.cargo ~/.rustup`。`tools/deploy-relay.sh` 会自动做，
> 只留下 `/usr/local/bin/ac-relay`（约 1 MB）。

---

## 配置两端

**长辈端**（部署时设置一次，之后自动连）：

```bash
adb shell am start -n dev.ling.app/.HomeActivity \
  --es relay_host <节点地址> --ei relay_port 27443 \
  --es relay_fingerprint <证书 SHA-256>
```

或在构建时写进 `local.properties`（见上），装出来的 App 默认就连自己的节点。
设置会持久化，长辈端每次启动都会主动连上去。

**控制端**：连接页右上角「设置 → 中继节点 / 中继证书指纹」填写一次即可，
之后直接输 6 位数字；也可以 `adb` 用同样的 intent 覆盖。

真机上还需要确认的项（TLS、文本输入、网页语音）见
[`verify-device.md`](verify-device.md)。

---

## 健康检查

```bash
systemctl status ac-relay
journalctl -u ac-relay -n 50 --no-pager     # 连接/配对/断开/AUDIT 都有日志
ss -ltn | grep -E '27180|27443'             # 明文应在 127.0.0.1，TLS 在 0.0.0.0
```

日志会明确写出每一对连接的结束方向（`控制端→长辈端` 或 `长辈端→控制端` 先断），
排查"用着用着就断了"时先看这一行。

### 可观测性

| 端点 | 内容 |
|---|---|
| `GET /healthz` | 存活探测：`{"status":"ok","uptime_seconds":N}`；`HEAD` 只回头 |
| `GET /metrics` | Prometheus 文本：uptime、信令连接数、待配对、求助记录、累计连接/配对 |

```bash
curl -s http://127.0.0.1:27180/healthz
curl -s http://127.0.0.1:27180/metrics | grep ac_relay_
```

探测请走 `127.0.0.1:27180`（Cloudflare 隧道后面），或直接对 TLS 端口做 HTTPS 探测；
**不要用 `FIND` 当健康检查**——它会真的触发配对，还会计入失败限速。

日志全部写 stdout 交给 journald。小盘节点建议限制一下体积：

```bash
journalctl --vacuum-size=100M
# 或永久生效：/etc/systemd/journald.conf 里设 SystemMaxUse=100M
```

`--max-fails` / `--fail-window` / `--lock-secs` 的含义见上面的「配对限速与审计」；
`--tls-*` 见「TLS」一节。

---

## 安全提示

- 公网部署时**只开放 TLS 端口**，明文监听绑 `127.0.0.1`（给隧道/反代用）。
  本仓库的部署脚本在 `TLS_PORT` 模式下已经这样配置。
- 配对靠"知道口令"授权，6 位数字在公网上**必须**配合上面的限速；
  如果怀疑口令泄露，在长辈端"高级信息 → 重新生成配对码"立即作废旧口令。
- 指纹固定防的是"连到假节点/中间人"，但不能防节点运营者看到明文（中继不落盘、不解析）。
- 更彻底的链路加密可以继续用 WireGuard 方案（见 `node-wireguard.md`）。

---

## 与 WireGuard 方案的关系

两条路都支持，按场景选：

| | 中继 | WireGuard |
|---|---|---|
| 长辈手机要装什么 | 只有我们的 App | App + WireGuard + 导入配置 |
| 控制端要输什么 | 6 位数字 | 6 位数字（隧道内固定 IP） |
| 加密 | TLS + 指纹固定（可选开） | 链路加密 |
| 适合 | 快速部署、不想折腾 VPN | 已在用 VPN、或对加密有硬要求 |

中继是**默认推荐**：少一个 App、少一类故障源。要链路层加密就用 WireGuard
（见 `node-wireguard.md`）。
