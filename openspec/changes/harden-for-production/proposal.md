# 生产级加固（Production Hardening）

## Why

功能已经按路线图完成（M0–M5），但离"可以交给陌生人自托管、长期依赖"还差三块硬伤：
只有 debug 签名、6 位口令没有失败限速、长辈手机到节点是明文 TCP；此外端到端验证靠手工、
控制端不能输入文字、网页端没有语音。这些是生产可用性的门槛，而不是新功能。

## What Changes

- **正式签名与发布**：引入 release keystore（只放本机 `.secrets/` 与 CI Secrets），
  `:app` 配置 release signing；GitHub Actions 在打 tag 时构建并签名 release APK，
  上传为 GitHub Release 资产（不再分发 debug 包）。
- **口令限速与审计**：中继按"来源 IP + 口令哈希"统计失败次数，超过阈值后锁定一段时间；
  FIND/ASK 的失败与锁定写入审计日志（只记哈希与 IP，不记口令）。
- **传输加密**：中继提供 TLS 监听（rustls），长辈端 RelayClient 支持 `wss`/`tls` 模式并
  校验服务器指纹（首次部署时固定）；文档把加密链路设为推荐默认，WireGuard 仍是备选。
- **自动化端到端测试**：CI 中新增一个不依赖真机的 E2E：启动真实中继 + 脚本化的
  agent（模拟 scrcpy 流）与控制器，验证配对、视频拆包、控制注入字节；网页端的
  协议解析逻辑抽成纯函数并加单元测试。
- **文本输入**：协议层实现 `INJECT_TEXT`，Android 控制端与网页控制端都提供输入框，
  用于搜索、填表等场景（受 InputMethod 限制的字段会有提示）。
- **网页语音**：浏览器端通过现有 `audio` 角色收发 PCM（麦克风采集 + 播放），
  与 Android 端互通；带静音开关与回声提示。
- **可观测性**：中继新增 `GET /healthz` 与 `GET /metrics`（文本指标），
  systemd 增加 watchdog；文档给出最小监控/告警与日志轮转方案。
- **发布流程**：版本号、CHANGELOG、tag 驱动的 Release；README/运维文档同步。

## Capabilities

### New Capabilities

- `release-signing`: release keystore、签名配置、CI 签名与 GitHub Release 产物
- `relay-rate-limiting`: 中继的配对失败限速、锁定与审计日志
- `transport-encryption`: 中继 TLS 监听与长辈端 TLS 客户端（含指纹校验）
- `automated-e2e`: 不依赖真机的端到端测试与网页协议单测
- `text-input`: 控制端（Android/网页）的文本注入
- `web-voice`: 网页端双向语音
- `observability`: 中继健康检查、指标与进程守护

### Modified Capabilities

（`openspec/specs/` 目前为空，没有需要修改的既有能力。）

## Impact

- **relay（Rust）**：TLS 监听、限速器、审计日志、`/healthz`、`/metrics`、依赖（rustls、tokio-rustls）
- **agent**：PairingStore 增加加密配置；RelayClient 支持 TLS + 指纹；部署文档
- **controller/app**：文本输入界面、`ControlMessageWriter.injectText`、单元测试
- **web**：协议解析抽成模块并测试；音频采集/播放；文本输入
- **CI**：签名 Secrets、release workflow、E2E job
- **文档**：`docs/ops/`（TLS、监控、发布）、`docs/ROADMAP.md`、`SECURITY.md`
- **兼容性**：现有明文部署仍可用；加密与限速为增量能力，默认推荐加密
