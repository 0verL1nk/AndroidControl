# 设计：生产级加固

## Context

项目功能已完整（M0–M5），当前形态：

- 一个 Android App（`:app`，含 agent/controller 两种角色），debug 签名分发；
- Rust 中继（配对、视频/控制/语音转发、求助记录、WebSocket 给网页端）；
- Next.js 网页控制端（Vercel），经 Cloudflare 命名隧道连 `wss://`。

已知差距（来自 2026-10-24 的评估）：release 签名缺失、6 位口令无限速、长辈手机到节点明文、
端到端验证手工、控制端不能输入文字、网页端无语音、无监控。

约束：开源可自托管、无遥测、不 root、长辈端零操作；中继要能在 1C1G 小节点上跑；
不能破坏现有明文部署的兼容性。

## Goals / Non-Goals

**Goals**
- 可以签名发布、可验证来源的 release 产物；
- 公网暴露中继时，口令猜测有速率上限且可审计；
- 长辈手机到中继的链路可加密，且部署方式可复制；
- 关键路径（配对、视频拆包、控制注入）有不依赖真机的自动化测试；
- 控制端可输入文字；网页端可双向语音；
- 中继有健康检查与最小指标，运维有据可查。

**Non-Goals**
- 不做账号体系/云服务；不引入遥测；
- 不在本变更内做 iOS 原生 App（网页端即跨平台方案）；
- 不做多节点自动调度/故障转移（文档给出双节点建议）；
- 不改变配对码格式（仍为 6 位数字）。

## Decisions

### D1. Release 签名：本地 keystore + CI Secrets，tag 驱动发布

- 维护者生成一次 `release.jks`，放 `.secrets/`（已忽略）；签名参数放
  `.secrets/keystore.properties`，本地构建读取；CI 用 `ANDROID_KEYSTORE_BASE64` 等 Secrets
  解码后写入临时文件。
- `:app` 的 `release` buildType 配置 signingConfig；没有 keystore 时构建失败并给出明确提示
  （不允许静默产出未签名包）。
- tag `v*` 触发 workflow：构建 release APK → 上传 GitHub Release。
- 备选：Play App Signing（不适用，侧载分发）；debug 签名（不可用于分发）。

### D2. 口令限速：按哈希 + 来源 IP 的双维度滑动窗口

- 中继在 `FIND`/`ASK` 失败时记一次失败：键为 `hash`（全局）和 `ip`（直连时有效）。
  默认策略：10 分钟内失败 10 次 → 锁定 10 分钟，期间 FIND 一律回 `ERR`（不暴露锁定状态）。
- 浏览器经隧道时来源 IP 全是 127.0.0.1，因此**按哈希的全局计数是主防线**，IP 维度只对直连有效。
- 审计日志：`AUDIT pair_failed hash=<前8位> ip=<ip> count=<n>`、`AUDIT locked hash=… until=…`。
- 备选：验证码加长（破坏"长辈只认数字"的体验）；外部 fail2ban（拿不到真实 IP，不适用）。

### D3. 传输加密：中继 TLS 监听 + 客户端指纹固定（WireGuard 仍是备选）

- 中继新增独立 TLS 监听（默认 `27443`），用运维自签证书（`openssl req -x509`），
  通过 `--tls-listen/--tls-cert/--tls-key` 开启；同一个 handler，不复制协议逻辑。
- 长辈端 `PairingStore` 增加 `relay_tls` 与 `relay_fingerprint`（证书 SHA-256）；
  `RelayClient` 用 `SSLSocket`，自定义 `TrustManager` 只接受指纹匹配的证书
  （固定指纹即完成身份校验，主机名校验随之关闭）。
- 部署文档把"启用 TLS"设为推荐；明文端口保留以兼容旧部署。
- 备选：只推 WireGuard（多一个 App、配置复杂，违背"长辈端少装东西"）；
  中继前置 stunnel（多一个进程，配置更绕）。

### D4. 自动化 E2E：真实中继 + 脚本化 agent/controller，不依赖真机

- 新增 `relay/tests/e2e_stream.rs`：启动中继，脚本化 agent 完成信令与数据连接，
  发送伪造的 scrcpy 流头/会话/配置/帧，断言控制器端收到逐字节一致的数据；
  控制方向发送按键字节，断言 agent 端收到。
- 新增 `tools/e2e-web.mjs`（Node + `ws`）：启动中继二进制，脚本化 agent，
  浏览器侧用 WebSocket 走 `video/control` 两个角色，验证与 Rust 测试相同的语义；
  在 CI 的 relay job 里运行。
- 网页端把流解析抽成 `web/src/lib/parse-stream.ts` 纯函数，用 `node:test` 覆盖
  会话包/配置包/关键帧/分片边界。
- 备选：双模拟器 UI 测试（重、慢，留给后续）。

### D5. 文本输入：协议 INJECT_TEXT + 两端输入框

- `ControlMessageWriter.injectText(text)`：`type=1, u32 length, UTF-8`，补单测。
- Android 控制端顶栏加"键盘"入口，弹输入框，确认即发送；网页端控制栏加输入框。
- 限制：部分安全输入框（密码/验证码）会拒绝注入，文档与 UI 提示"若无效请直接点屏幕键盘"。

### D6. 网页语音：复用 `audio` 角色，PCM 直传

- 浏览器 `getUserMedia({audio:{echoCancellation:true, noiseSuppression:true}})` →
  AudioWorklet 降采样到 16 kHz 单声道，20 ms/帧（640 字节），经第三条 WS（`audio <hash>`）发送；
  接收方向用 AudioWorklet 环形缓冲播放。
- 与 Android 端共用 `VoiceAudio` 常量（16 kHz/单声道/640B），协议不变。
- UI：麦克风开关、静音、权限失败提示。

### D7. 可观测性：健康检查 + 文本指标 + 外部探测

- 中继 HTTP：`GET /healthz` → 200 `ok`（含 uptime、信令数、待配对）；`GET /metrics` → 文本：
  `ac_signaling`, `ac_waiting`, `ac_help_pending`, `ac_connections_total`, `ac_pairs_total`。
- 运维文档给出一条 `curl` 健康检查 + UptimeRobot/系统 cron 的最小告警方案；日志交给 journald
  （`journalctl --vacuum-time=7d` 轮转建议）。
- 备选：Prometheus exporter（对小节点过重）。

### D8. 发布流程：语义化版本 + CHANGELOG + tag

- `versionName` 与 CHANGELOG 同步更新；tag 触发 release（见 D1）；网页端由 Vercel 自动部署。

## Risks / Trade-offs

- [TLS 自签证书的信任] → 用指纹固定（不是 CA 信任），指纹在部署时写入长辈端配置；
  换证书需同时更新两端，文档写明步骤。
- [限速误伤] → 锁定只影响该哈希的 FIND/ASK，已在线的会话不受影响；锁定时间可配置。
- [网页语音回声] → 浏览器开 AEC + 长辈端 AcousticEchoCanceler；仍建议戴耳机。
- [E2E 伪造流与真实 scrcpy 差异] → 断言的是字节透传与拆包，真实设备验收仍然保留。
- [release 签名密钥丢失] → 密钥与口令放 `.secrets/` 并提示备份；丢失只能换签名重新分发。

## Migration Plan

1. 先落 release 签名与 CI（不影响运行时）；
2. 再上限速（默认开启；`--max-fails 0` 可关闭以兼容）；
3. TLS 为可选开关，文档引导新部署启用；旧部署保持明文可用；
4. 文本输入、网页语音为纯增量；
5. 可观测性上线后配置外部探测。

回滚：每个能力都有独立开关或可回退提交；中继保留明文端口与 `--max-fails 0`。

## Open Questions

- 中继 TLS 端口是否也用 Cloudflare 代理（`relay.overlink.top:27443` 走 CF 需要 Origin Rule）？
  当前方案是直连 IP + 指纹，不依赖 CF。
- 文本注入对国产 ROM 输入法的兼容性需要真机抽样验证（列在验收里）。
