# 变更记录

## Unreleased（0.3.0）

### 新增

- 生产加固（OpenSpec `harden-for-production`）：release 签名与 tag 发布、中继口令限速与审计、
  TLS 传输加密（指纹固定）、不依赖真机的端到端测试、文本输入、网页双向语音、
  中继 `/healthz` 与 `/metrics`
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
