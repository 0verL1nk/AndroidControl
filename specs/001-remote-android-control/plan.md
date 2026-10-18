# Implementation Plan: 远程接管 Android 手机（跨公网，M1）

**Branch**: `001-remote-android-control` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-remote-android-control/spec.md`

## Summary

两端都是 Android 手机，跨公网连接。**长辈端**（被控）装一个极简 App，通过一次性激活的
Shizuku 以 shell 身份启动 scrcpy-server，把采集/编码/注入握在自己手里，并把
video/control 两个流通过自建节点暴露给控制端。**控制端**（子女）**fork
ScrcpyForAndroid**（Kotlin + Apache-2.0），保留它的解码、渲染、触摸、输入法与 UI，
只把"通过 ADB 连接目标设备"这一段换成"连接我们自己的长辈端"。**节点**只跑 WireGuard：
两端各拿一个固定内网 IP，加密与 NAT 穿透由网络层解决。

**关键取舍**：不在应用层重造协议与解码器。上游已经把 scrcpy 的 C 客户端编译进
Android（`NativeCoreFacade` + NDK 子模块），协议正确性由上游保证；我们的自研范围收缩到
**长辈端的启动与暴露、绑定与安全、适老化交互**。

## Technical Context

**Language/Version**: Kotlin 2.x（两端 App）；Gradle Kotlin DSL；JDK 21（对齐上游）

**Primary Dependencies**:
- 控制端：fork 自 `Miuzarte/ScrcpyForAndroid`（Apache-2.0），保留其 native core、
  `MediaCodecVideoDecoder`、`PersistentVideoRenderer`、Compose UI
- 长辈端：AndroidX、Coroutines、Kotlin、**Shizuku API（`dev.rikka.shizuku:api`）**
- 协议：scrcpy-server 5.0.1（vendor 锁定）；控制消息按官方字节布局
- 网络：**WireGuard**（节点 hub，两端 peer），不在应用层实现加密

**Storage**: 长辈端保存设备身份、绑定白名单、最近连接记录（DataStore/SharedPreferences）；
不落盘屏幕内容。

**Testing**:
- 协议层：JVM 单测（golden vectors 对齐 scrcpy 5.0.1）
- 长辈端：Shizuku 启动路径的仪器测试 + 真机验收
- 端到端：两台真机跨公网验收脚本、延迟 p50/p95 探针、断网恢复测试

**Target Platform**: Android 8.0+（minSdk 26），compileSdk 36/37（跟随上游）

**Project Type**: 多仓库整合的单仓（fork 上游为子目录）+ 一个 Android App

**Performance Goals**: 局域网 ≥30fps / p95 ≤300ms；跨公网 ≥20fps / p95 ≤500ms；
节点带宽按 2Mbps 视频流规划

**Constraints**: 不 root；长辈端零操作；跨公网必须加密（由 WireGuard 承担）；
scrcpy 客户端/服务端版本严格一致

**Scale/Scope**: 1 对 1 会话；长辈端 1 台设备；M1 不做多设备管理

**License**: Apache-2.0（与上游 scrcpy / ScrcpyForAndroid 兼容，含专利授权）。
不得引入不兼容许可证的第三方代码；保留上游署名（`NOTICE`）。

## Constitution Check

| 原则 | 检查结果 | 说明 |
|---|---|---|
| I. 实测优先 | ✅ PASS | sing-box TUN 污染测量的教训已记录；所有延迟结论必须绕开代理实测 |
| II. 特权最小化、来源可替换 | ✅ PASS | `ServerLauncher` 接口隔离 Shizuku；不需 root |
| III. 复用验证过的引擎 | ✅ PASS | 复用 scrcpy-server + fork ScrcpyForAndroid，自研仅为胶水与适老层 |
| IV. 安全默认开启 | ✅ PASS | 绑定白名单、可见提示、一键结束、WireGuard 加密、不落盘 |
| V. 可执行验收 | ✅ PASS | 延迟/帧率/重启恢复/未授权连接均有可测指标 |
| VI. 文档即路线 | ✅ PASS | spec/plan/research/contracts/tasks/roadmap/ADR 齐备 |

**结论**：无违反项。上游 fork 会引入较大的第三方代码量，属原则 III 鼓励的复用，不需要
Complexity Tracking。

## Project Structure

```text
AndroidControl/
├── specs/001-remote-android-control/     # Spec Kit 产物（本目录）
├── docs/
│   ├── ROADMAP.md                        # 全路线 M0–M4
│   ├── DECISIONS.md                      # ADR 决策记录
│   └── ops/
│       ├── node-wireguard.md             # 节点搭建与运维手册
│       └── elder-device-setup.md         # 长辈手机一次性部署手册
├── agent/                                # 【自研】长辈端 App
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/ling/agent/
│       │   ├── MainActivity.kt           # 大字号状态页：求助/正在被协助/结束
│       │   ├── AgentService.kt           # 前台服务，保活与自动恢复
│       │   ├── launcher/
│       │   │   ├── ServerLauncher.kt     # 接口：以 shell 身份启动采集引擎
│       │   │   ├── ShizukuLauncher.kt    # 实现（M1 主路径）
│       │   │   └── AdbLauncher.kt        # 实现（开发兜底，PC 触发）
│       │   ├── engine/
│       │   │   ├── EngineInstaller.kt    # 把 vendor 的 server.jar 释放到可读位置
│       │   │   ├── SessionCoordinator.kt # scid、LocalServerSocket、接受 video/control
│       │   │   └── StreamBridge.kt       # 抽象 socket ↔ TCP 双向泵
│       │   ├── net/
│       │   │   ├── Listener.kt           # 在隧道接口上监听控制端
│       │   │   └── SessionProtocol.kt    # 握手、鉴权、版本协商
│       │   ├── trust/
│       │   │   ├── DeviceIdentity.kt
│       │   │   ├── BindingStore.kt       # 白名单与吊销
│       │   │   └── ConnectionLog.kt
│       │   └── ui/                       # 极简界面与可见提示
│       └── res/
├── controller/                           # 【fork】控制端 App
│   └── （fork 自 Miuzarte/ScrcpyForAndroid，保留 upstream remote）
│       └── app/src/main/java/.../nativecore/
│           ├── AgentClient.kt            # 【新增】连接长辈端，替代 DirectAdbClient
│           └── （其余解码/渲染/UI 原样保留）
├── vendor/
│   └── scrcpy-server-5.0.1.jar           # 锁定版本引擎
└── tools/
    ├── dev-launch-server.ps1             # 开发期用 PC/ADB 兜底启动
    └── probe-latency.ps1                 # 端到端延迟测量
```

**Structure Decision**: 采用"fork 上游 + 自研长辈端"的双 App 单仓结构。控制端不重写，
只替换连接层（`nativecore/AgentClient.kt` 取代 `DirectAdbClient.kt` 的角色），这样上游
每次更新都能通过 `git remote upstream` 合并进来。长辈端是真正的自研部分，边界清晰：
启动引擎、暴露流、管信任、给长辈看。

## 自托管交付物（开源目标）

除了两端 App，M1 还必须交付让陌生人能自己跑起来的东西：

| 交付物 | 路径 | 验收 |
|---|---|---|
| 节点一键脚本 | `tools/setup-node.sh` | 在干净的 Debian/Ubuntu VPS 上从零跑通，输出对端配置与二维码 |
| 节点手册 | `docs/ops/node-wireguard.md` | 含端口、分流、排障、轮换密钥；全程使用占位符 |
| 长辈端部署手册 | `docs/ops/elder-device-setup.md` | 非开发者可照着做完一次性授权 |
| 项目门面 | `README.md` / `PRIVACY.md` / `SECURITY.md` / `NOTICE` | 说清是什么、隐私边界、如何报告问题、上游署名 |
| 个人数据防护 | `tools/check-personal-data.sh` + pre-commit hook | 每次提交自动扫描，0 告警 |
| CI | `.github/workflows/` | 构建两端 App；签名密钥走 GitHub Secrets，不进仓库 |

**硬约束**：端点、端口、密钥全部由配置或脚本生成，代码中不得出现作者的任何基础设施；
不接入任何遥测/统计/分析 SDK。

## 网络设计

```
        ┌──────────────── 自建节点（WireGuard hub）────────────────┐
        │  公网 IP:51820/UDP   隧道网段 10.66.66.0/24              │
        └───────▲────────────────────────────────────▲────────────┘
                │ 10.66.66.2                         │ 10.66.66.3
        ┌───────┴────────┐                    ┌──────┴─────────┐
        │ 长辈手机        │                    │ 控制端手机      │
        │ Agent 监听      │ ◄── TCP 27185 ──── │ 连接 .2:27185  │
        │ 27185(控制)     │                    │                │
        │ 27186(视频)     │                    │                │
        └────────────────┘                    └────────────────┘
```

- 两端 AllowedIPs 只放开隧道网段，**不影响老人手机正常上网**（分流）。
- 节点同时承担加密与中继；带宽即节点带宽，视频码率按 2Mbps 规划。
- 打洞优化（headscale/DERP 或 WebRTC）留到 M3，不进 M1。

## Phases

- **Phase 0（已完成）**：产品/技术/合规调研；真机可行性；协议源码核对；
  服务器现状盘点与节点选型。
- **Phase 1（本文件产出）**：数据模型、Agent 协议契约、快速验证手册。
- **Phase 2**：任务拆分（tasks.md）。
- **Phase 3+**：实现。MVP = US1 + US2（能看到能操作 + 长辈零操作）。

## Milestones

| 里程碑 | 内容 | 退出条件 |
|---|---|---|
| M0（已完成） | 调研与可行性 | scrcpy 在 Android 16 真机跑通 |
| M1 | 跨公网闭环 | US1+US2 通过；SC-001~SC-005 |
| M2 | 弱网与恢复 | SC-006、SC-007；重启自恢复 ≥9/10 |
| M3 | 传输优化与安全加固 | 打洞或低延迟中继；吊销与审计完备 |
| M4 | 适老体验 | 一键求助、订阅式提醒、敏感页提示 |

## Risks

| 风险 | 影响 | 缓解 |
|---|---|---|
| Shizuku 在长辈机型上不能开机自启 | 破坏"长辈零操作" | M1 第一周先验证；失败则回退 adb 常开或改用系统自带远程协助 |
| 节点带宽不足（3–5Mbps） | 画面卡顿 | 视频降到 1.5–2Mbps、720p；或换带宽更大节点 |
| 国内节点跑隧道违反服务条款 | 被限速/停服 | 控制流量；优先用已有机器；备选海外节点 |
| fork 上游后续更新冲突 | 维护成本 | 只改 `nativecore/` 连接层，保持补丁面最小 |
| 服务器主机密钥变更（已发现） | 安全风险 | 部署前必须核实指纹归属，未确认前不写入任何密钥 |
| 安卓 15/16 对 adbd 与 shell 通道的新限制 | 通道失效 | 走 Shizuku（不经 adbd）；保留回退路径 |
