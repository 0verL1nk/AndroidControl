# 变更记录

## 0.3.0 — 2026-10-24

### 新增

- **正式签名与发布**：固定 release keystore；推 `v*` tag 由 CI 构建、校验签名并挂到
  GitHub Release（签名材料走仓库 Secrets，不入库）
- **配对限速与审计**：按口令哈希（直连时含来源 IP）统计失败，
  `--max-fails` / `--fail-window` / `--lock-secs`；锁定期间直接拒绝且**不惊动长辈端**；
  `AUDIT` 日志只记哈希前 8 位；成功配对清零（含 IP 维度）
- **传输加密**：中继 `--tls-listen/--tls-cert/--tls-key`，与明文监听共用配对表；
  自签证书 + **SHA-256 指纹固定**（App 两端都支持，握手阶段拒绝错误证书）；
  部署脚本 `TLS_PORT=…` 自动生成证书，并把明文监听收紧到 `127.0.0.1`
- **文本输入**：协议 `INJECT_TEXT`；App 顶栏键盘入口与网页输入框
- **网页双向语音**：AudioWorklet 采集（插值到 16 kHz）与播放（有界队列），麦克风开关与错误提示
- **可观测性**：`GET /healthz`、`GET /metrics`（Prometheus 文本）
- **自动化端到端**：Rust 流级测试（逐字节、跨分片、1 MiB 大块）、网页解析/PCM 单测、
  `tools/e2e-web.mjs`（真实中继 + 脚本化长辈端 + WebSocket）、CI `e2e` job
- 画质档位：流畅（720p / 2 Mbps）与清晰（1280p / 4 Mbps）

### 修复

- 中继支持 `HEAD/OPTIONS`（经 Cloudflare 的健康检查不再 502）
- Vercel 自动部署的根目录修正为 `web/`

## 0.2.0 — 2026-10-23

- 控制端与被协助端合并为同一个 App：首页展示自己的配对码、打开即待命，
  也能输入对方口令去协助
- 网页控制端（Next.js + WebCodecs）上线：Vercel + Cloudflare 命名隧道 `wss://`
- 完整 Material 3 主题（深色/浅色）、首页状态卡片、一键打开 Shizuku

## 0.1.0 — 2026-10-19

- 首个可运行版本：跨公网看屏与操作、Shizuku 引擎、Rust 中继、双向语音、
  一键求助、敏感页红色提示；30 分钟长跑通过
