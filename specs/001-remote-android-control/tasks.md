---
description: "Task list for 远程接管 Android 手机（跨公网 M1）"
---

# Tasks: 远程接管 Android 手机（跨公网 M1）

**Input**: Design documents from `specs/001-remote-android-control/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/agent-protocol.md, quickstart.md

**Tests**: 协议层按宪法要求写单测（TDD）；Android UI 以仪器测试与真机验收补足。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 该任务属于哪个用户故事（US1/US2/US3/US4）

## Path Conventions

- 长辈端 App：`agent/src/main/kotlin/dev/ling/agent/`
- 控制端 App：`controller/`（fork 自 Miuzarte/ScrcpyForAndroid）
- 共享协议：`protocol/src/main/kotlin/dev/ling/protocol/`
- 脚本：`tools/`；文档：`docs/`

---

## Phase 1: Setup（工程初始化）

- [ ] T001 创建仓库骨架：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`，声明 `:protocol`、`:agent` 两个模块
- [ ] T002 创建 `:protocol` 纯 Kotlin 模块（java-library），配置 JUnit4 与 kotlin-test
- [ ] T003 [P] 创建 `:agent` Android App 模块（minSdk 26，compileSdk 36，Compose）
- [ ] T004 [P] 写入 `.gitignore`（`.secrets/`、`build/`、`.gradle/`、`local.properties`）
- [ ] T005 [P] 把 scrcpy-server 5.0.1 放入 `vendor/scrcpy-server-5.0.1.jar` 并计算校验和记录到 `vendor/README.md`
- [ ] T006 [P] 编写 `tools/dev-launch-server.ps1`：通过 ADB 推送并以指定 scid 启动 server（开发兜底路径）
- [ ] T007 fork `Miuzarte/ScrcpyForAndroid` 到 `controller/`，添加 `upstream` remote，记录 fork 基线 commit 到 `controller/FORK.md`
- [ ] T008 [P] 验证 `controller/` 能独立构建出 APK（NDK 版本对齐上游要求）

**Checkpoint**：两个模块可构建，vendor 引擎就位，fork 可编译。

---

## Phase 2: Foundational（阻塞所有用户故事）

**⚠️ 关键**：T009 是本项目最大的技术风险，必须最先验证。

- [ ] T009 **Shizuku 启动引擎验证**（探针）：在 `agent/` 中实现最小 Shizuku `UserService`，以 shell 身份执行 `CLASSPATH=… app_process … com.genymobile.scrcpy.Server 5.0.1 …`，确认 server 能连上 App 创建的 abstract socket
- [ ] T010 [P] `protocol/ProtocolConstants.kt`：协议版本、消息类型枚举（INJECT_KEYCODE=0 … RESET_VIDEO=17）、按键码
- [ ] T011 [P] `protocol/ByteReader.kt` + 单测：大端读取工具
- [ ] T012 [P] `protocol/VideoDemuxer.kt` + 单测：解析 64 字节设备名、codec id、session/media 包（12 字节包头）
- [ ] T013 [P] `protocol/ControlMessageWriter.kt` + 单测：触摸/按键/滚动/BACK_OR_SCREEN_ON 字节布局，用 scrcpy 官方测试向量逐条对齐
- [ ] T014 [P] `protocol/PairingCode.kt` + 单测：6 位码生成、过期、尝试计数
- [ ] T015 `agent/engine/EngineInstaller.kt`：把 `vendor/scrcpy-server-5.0.1.jar` 从 assets 释放到应用外部目录（供 shell 读取）
- [ ] T016 `agent/engine/SessionCoordinator.kt`：生成 scid、创建 `LocalServerSocket("scrcpy_%08x")`、按 video→control 顺序接受连接
- [ ] T017 `agent/net/SessionProtocol.kt`：实现 `contracts/agent-protocol.md` 的 HELLO/WELCOME/REJECT/PING/BYE 与签名校验
- [ ] T018 `agent/net/Listener.kt`：在隧道接口监听 27185（控制）与 27186（视频），校验 token
- [ ] T019 `agent/engine/StreamBridge.kt` + 单测：抽象 socket ↔ TCP 双向泵，支持"控制端未连接时丢弃视频而不阻塞"
- [ ] T020 `agent/trust/DeviceIdentity.kt` + `BindingStore.kt` + `ConnectionLog.kt`：设备身份、白名单、最近 20 条记录（DataStore）
- [ ] T021 `controller/app/.../nativecore/AgentClient.kt`：替代 `DirectAdbClient`，连接长辈端两条 TCP，把 socket 交给既有 native core
- [ ] T022 打通 `controller/` 的连接流程改造：设备列表改为"已绑定长辈端"（隧道 IP + 显示名），移除 ADB 配对入口
- [ ] T023 [P] 编写 `docs/ops/elder-device-setup.md`：长辈手机一次性部署步骤（Shizuku 激活、WireGuard 导入、自启设置）

**Checkpoint**：长辈端能启动引擎并暴露端口；控制端能连上拿到两条流。

---

## Phase 3: User Story 1 - 跨公网看屏与操作 (P1) 🎯 MVP

**Goal**: 控制端在蜂窝网络下看到并操作长辈端。

**Independent Test**: 控制端用 5G、长辈端用家宽，连接后能看到画面并打开一个应用。

- [ ] T024 [P] [US1] `agent/ui/` 状态页：显示"已就绪/正在被协助"、隧道地址、配对信息
- [ ] T025 [US1] 长辈端引擎参数下发：`max_size`、`max_fps`、`video_codec=h264`、`audio=false`，按节点带宽默认 2Mbps 码率
- [ ] T026 [US1] 控制端画面渲染联调：确认 64 字节设备名被正确跳过、codec id = h264、首帧为 key frame
- [ ] T027 [US1] 控制端触摸映射：视图坐标 → 设备像素，处理黑边与旋转后的尺寸变化
- [ ] T028 [US1] 控制端悬浮操作栏：返回 / 主页 / 最近任务 / 音量±，映射到 `ControlMessageWriter`
- [ ] T029 [US1] 长按与多指手势按上游实现接入，验证长按生效
- [ ] T030 [US1] 端到端联调：在 5G + 家宽条件下跑通 quickstart §3、§4，记录数据

**Checkpoint**：US1 独立可验证 —— 跨公网画面与操作均可用。

---

## Phase 4: User Story 2 - 长辈端零操作 (P1)

**Goal**: 长辈设备重启后无需任何人操作即可被连接；被协助时可见、可一键结束。

**Independent Test**: 重启长辈手机，不碰它，60 秒内控制端能连上。

- [ ] T031 [US2] `agent/launcher/ServerLauncher.kt` 接口 + `ShizukuLauncher.kt` 正式实现（把 T009 探针产品化）
- [ ] T032 [US2] 长辈端 Shizuku 存活检测与自动恢复：Binder 死亡监听、失败重试、状态提示
- [ ] T033 [US2] `agent/AgentService.kt` 前台服务：常驻通知、开机自启、被杀后恢复
- [ ] T034 [US2] 长辈端引导页：一次性授权（Shizuku 权限、WireGuard 导入、电池优化白名单、自启动白名单）
- [ ] T035 [US2] 被协助期间的可见提示：屏幕边缘常驻条 + 一键"结束协助"
- [ ] T036 [US2] 结束路径：立即关闭两条 TCP、关闭引擎、回到"未开启协助"状态
- [ ] T037 [US2] 重启恢复验收：连续 10 次重启测试，记录成功率（目标 ≥9/10）
- [ ] T038 [P] [US2] 更新 `docs/ops/elder-device-setup.md`：记录实测可行的自启组合与厂商差异

**Checkpoint**：长辈端真正零操作，US2 独立可验证。

---

## Phase 5: User Story 3 - 安全边界 (P2)

- [ ] T039 [US3] 绑定流程：控制端首次连接需长辈端确认（长辈端弹出大字号确认页），确认后写入白名单
- [ ] T040 [US3] 长辈端绑定管理页：展示已绑定设备、最近使用时间、吊销按钮
- [ ] T041 [US3] 吊销即时生效：进行中的会话也要被断开
- [ ] T042 [US3] 单会话限制：已有会话时新连接返回 `BUSY`
- [ ] T043 [US3] 版本协商与拒绝：`VERSION` 拒绝码与用户可见提示
- [ ] T044 [US3] 落盘检查与保证：确认无屏幕内容写盘；日志只记元数据
- [ ] T045 [US3] 安全验收：按 quickstart §6 逐项执行，未授权连接拒绝 20/20

---

## Phase 6: User Story 4 - 弱网与恢复 (P3)

- [ ] T046 [US4] 心跳与断线检测：5 秒 PING，连续 3 次失败判定断线
- [ ] T047 [US4] 长辈端会话保持窗口：15 秒内保留引擎，视频数据丢弃不阻塞
- [ ] T048 [US4] 控制端自动重连：指数退避（1s/2s/4s…上限 15s），恢复后不重放旧事件
- [ ] T049 [US4] 控制端前后台切换：回到前台自动恢复画面
- [ ] T050 [US4] 弱网验收：按 quickstart §7 执行，恢复成功率 ≥8/10

---

## Phase 7: Polish & 交叉关注点

- [ ] T051 [P] 延迟探针 App（长辈端）：纯色背景，收到点击立即变色，用于 SC-003 测量
- [ ] T052 [P] `tools/probe-latency.ps1`：自动化 100 次点击测量并输出 p50/p95/max
- [ ] T053 性能调优：按实测把码率/分辨率/帧率调到节点带宽与观感的最优组合
- [ ] T054 稳定性长跑：30 分钟连续会话，记录崩溃与断流（SC-007）
- [ ] T055 [P] 更新 `docs/ROADMAP.md` 与 `quickstart.md` 的实测结果
- [ ] T056 完整验收：执行 quickstart 全部章节，填写记录模板并归档到 `docs/acceptance/`

---

## Phase 8: 开源与自托管（项目级）

**目标**：陌生人能自己搭一套；仓库不含任何使用者信息。

- [x] T057 写入 `LICENSE`（Apache-2.0）与 `NOTICE`（scrcpy / ScrcpyForAndroid / Shizuku 署名）
- [x] T058 写入 `README.md`、`PRIVACY.md`、`SECURITY.md`
- [x] T059 `tools/setup-node.sh`：节点一键搭建（装依赖、开转发、生成密钥、写配置、出二维码）
- [x] T060 `tools/check-personal-data.sh` + `.personal-data-allowlist` + pre-commit hook
- [x] T061 个人信息清洗：工作区文本 + 历史提交（含唯一一条历史记录）
- [ ] T062 在**干净的** Debian/Ubuntu VPS 上端到端验证 `tools/setup-node.sh`
- [ ] T063 `.github/workflows/build.yml`：构建两端 App；签名密钥与 keystore 走 GitHub Secrets
- [ ] T064 无遥测核查：审查依赖树，确认无统计/崩溃上报/广告 SDK
- [ ] T065 陌生人自建验收（SC-009）：请一个人只按 README + `docs/ops/` 从零走一遍，记录卡点
- [ ] T066 `docs/ops/elder-device-setup.md` 面向非开发者重写，并请非开发者试读

---

## Dependencies & Execution Order

### Phase 依赖

- **Setup (P1)**：无依赖
- **Foundational (P2)**：依赖 Setup；**阻塞所有用户故事**
- **US1 (P3)**：依赖 Foundational
- **US2 (P4)**：依赖 Foundational；T031 依赖 T009 的结论
- **US3 (P5)**：依赖 US1（需要能连上才能做绑定）
- **US4 (P6)**：依赖 US1
- **Polish (P7)**：依赖所需用户故事完成

### 关键路径

T009（Shizuku 探针）→ T015–T019（引擎与协议）→ T021–T022（控制端接入）→ T026–T028
（画面与操作）→ T031–T037（零操作）→ 验收。

### 并行机会

- T010–T014（协议层五个文件）可完全并行
- T024 与 T026 分别在两端，可并行
- Phase 7 的 T051/T052 与 Phase 5/6 无依赖，可提前启动

---

## Implementation Strategy

### MVP First

1. Phase 1 + Phase 2（T009 优先）
2. Phase 3（US1）→ **停下来验收**：跨公网能看能操作
3. Phase 4（US2）→ 长辈零操作达成
4. 再按 US3、US4 增量推进

### 风险优先

T009 是最可能失败的一步（Shizuku 能否以 shell 身份拉起 app_process）。**在第一周内
完成它**：成功则整个架构成立；失败则立即转向备选（无线调试常开 / 系统自带远程协助），
避免在错误路线上投入。
