# 远程接管 Android 手机（自用）— 设计文档

- 日期：2026-10-18
- 状态：待用户评审
- 规模：个人自用项目，不上架、不分发

## 1. 背景与目标

家里的长辈不熟悉智能手机，遇到问题时需要子女远程看到屏幕并直接代为操作。
目标是做一套**自用的、手机控手机**的远程接管工具：被控端装一台闲置 Android
手机（将来换成长辈的手机），控制端装在自己的手机上，通过局域网（后续扩展到
公网）看到实时屏幕并注入触摸。

### 1.1 目标（M1）

1. 控制端实时看到被控端画面（局域网 ≥30fps，端到端 <200ms）。
2. 控制端单指点击、滑动、长按、返回/主页/最近任务在真机上生效。
3. 锁屏、应用切换、横竖屏切换下画面正确。
4. 断线可重连；配对码错误无法连接。
5. 数据流不经过 PC（PC 只负责开发期启动 server）。

### 1.2 非目标（M1 不做）

- 公网穿透（M3）、音频、剪贴板、文件传输、文字输入法注入。
- 无人值守开机自启（M2）、应用商店合规与分发。
- root 相关能力。

## 2. 已核实的事实（2026-10-18 实测）

### 2.1 开发环境

| 项 | 值 |
|---|---|
| 工程目录 | `E:\Web-leaning\AndroidControl`（空目录，非 git 仓库） |
| Android SDK | `E:\AndroidStudioSDK`（platforms 36 / 36.1，build-tools 36.x） |
| adb | `E:\adb\platform-tools\adb.exe`，36.0.0 |
| JDK | Temurin 17.0.19 |
| Node / Python | 22.14.0 / 3.13.1 |
| 模拟器 | 有 AVD `Pixel_9_Pro_XL`（android-37 PlayStore 镜像），本项目暂不使用 |

### 2.2 被控端设备

| 项 | 值 |
|---|---|
| 品牌 / 型号 | Redmi K60 Ultra |
| 系统 | HyperOS 3.0 / OS3.0.302.0.WMLCNXM，Android 16（API 36） |
| ABI | arm64-v8a |
| 分辨率 / 密度 | 1220×2712 @ 480dpi |
| 连接方式 | 无线调试，`<DEVICE_LAN_IP>:<PORT>`（配对已保留） |
| shell 身份 | `uid=2000(shell)`，groups 含 `1004(input)` |
| `ro.debuggable` | 0（生产构建，无 `adb root`） |

### 2.3 实测基线

| 方案 | 结果 | 结论 |
|---|---|---|
| `screencap -p` 单帧 | 939 / 969 / 987 ms | 不可作为视频源 |
| `screenrecord --output-format=h264 -` | 2s 输出 119KB，启动即编码 | 可用但延迟秒级，不适合交互 |
| `adb shell input tap` 往返 | 115–203 ms | 大部分是 adb 进程启动开销 |
| **scrcpy 5.0.1** | server 733KB 推送 0.4s，`app_process` 启动，硬编录制 8s 成功 | **主链路在这台 Android 16 上成立** |
| `app_process` | 存在（system/bin/app_process64） | shell 身份可运行 dex |

### 2.4 关键结论

- 免 root 下，**shell 身份**（uid 2000）同时具备屏幕采集与输入注入能力，这是本
  项目的基础。
- Android 14 起 MediaProjection 每次采集都要重新授权；Android 16 息屏/锁屏会强制
  停止 MediaProjection。**shell 路线不受这两条限制**，这是选择 scrcpy-server 的
  决定性理由。
- 无障碍注入的事件带 `FLAG_IS_ACCESSIBILITY_TOOL`，金融类 App 可检测；
  `InputManager.injectInputEvent` 注入的事件不带该标记。

## 3. 总体架构

```
┌─────────────────────┐        Wi-Fi / 后续公网        ┌──────────────────────┐
│  controller（你的手机）│ ◄──── 视频流 + 控制消息 ────► │  agent（被控手机）     │
│  MediaCodec 硬解      │                               │  代理 / 鉴权 / 状态 UI │
│  SurfaceView 渲染     │                               │        ▲   │          │
│  触摸 → 控制消息      │                               │ localabstract│ start    │
└─────────────────────┘                               │        │   ▼          │
                                                       │  scrcpy-server (shell)│
                                                       │  采集 / 硬编 / 注入    │
                                                       └──────────────────────┘
```

**核心取舍：复用 scrcpy-server 作为采集/编码/注入引擎（Apache-2.0，本机验证通过），
自研部分只包含"特权启动、代理转发、控制端 App"。**

### 3.1 连接模型

scrcpy 默认（reverse）模式下：客户端在本机监听 `localabstract:scrcpy_<scid>`，
server 启动后反向连入。我们把"客户端"替换成 agent App：

1. agent 生成 31 位随机 `scid`，用 `LocalServerSocket(ABSTRACT, "scrcpy_<scid>")`
   监听。
2. 启动器以 shell 身份运行 `scrcpy-server`，参数 `scid=<scid>`（默认 reverse）。
3. server 依次连入 **video**、**control** 两个 socket（`audio=false`）。
4. agent 将两个本地 socket 与 controller 的 TCP 连接对接，做字节级转发。

agent 自身不需要 shell 权限；需要 shell 权限的只有启动 server 这一动作。

### 3.2 特权启动层（可替换）

| 实现 | 使用阶段 | 说明 |
|---|---|---|
| `PcAdbLauncher` | M1 | PC 脚本执行 `adb push` + `app_process` 启动 server |
| `ShizukuLauncher` | M2 | agent 内通过 Shizuku 以 shell 身份启动同一命令 |
| `RootLauncher` | 可选 | 已 root 设备，直接 su 执行 |

三者输出相同的"server 已启动"结果，agent 其余逻辑不变。

## 4. 组件设计

### 4.1 `:protocol`（Kotlin 库，双端共用）

职责：scrcpy 5.0.1 协议的序列化/反序列化，不依赖 Android UI。

- `VideoDemuxer`：读取 codec id（u32）；区分 session packet（12B，MSB=1）与
  media packet（12B header：media flag / config flag / key flag / PTS u61 /
  size u32）；输出 `(config, pts, key, payload)`。codec id 必须为 `h264`。
- `ControlMessageWriter`：触摸（down/up/move）、按键、滚动。字段布局严格对齐
  scrcpy `app/tests/test_control_msg_serialize.c`，并用其测试向量做单测。
- `DeviceMessageReader`：处理 clipboard 等设备→客户端消息（M1 仅解析并丢弃）。
- `SessionInfo`：设备名、初始宽高、旋转后的新宽高。

### 4.2 `:agent`（被控端 App）

- `SessionCoordinator`：生成 scid、开 LocalServerSocket、接受两个连接、与
  controller 建立 TCP 会话。
- `Proxy`：两个本地 socket ↔ 两个 TCP 连接（video / control 各一条），固定缓冲区
  双向拷贝，不做协议解析。
- `PairingAuth`：6 位一次性配对码 + 设备白名单（首次连接后记录 controller 的
  自生成 deviceId）。
- `AgentService`：前台服务，常驻通知显示"正在被控 / 待连接 / 已断开"。
- `StatusActivity`：大字号状态页、配对码、连接记录、立即断开。
- `ControlIndicator`：被控期间屏幕边缘常驻提示（可由用户关闭）。

M1 的 server 启动由 PC 完成：agent 在界面上显示 `scid` 与配对码，PC 运行
`tools/dev-launch-server.ps1 <serial> <scid>`。

### 4.3 `:controller`（控制端 App）

- `SessionClient`：TCP 连接、配对握手、断线重连（指数退避）。
- `VideoPipeline`：`VideoDemuxer` → `MediaCodec`（硬解，异步模式）→ `SurfaceView`。
  首包 config（SPS/PPS）以 `BUFFER_FLAG_CODEC_CONFIG` 送入。
- `TouchMapper`：把 View 坐标按 `viewSize / frameSize` 映射到设备像素；处理旋转
  （session packet 更新尺寸时重建解码器）。
- `ControlChannel`：触摸事件合并（move 采样限流，例如 ≤120Hz）、按键、滚动。
- `ControllerUi`：全屏画面 + 半透明悬浮按钮（返回 / 主页 / 最近任务 / 音量 /
  断开），调试叠层显示 fps、码率、估算延迟。

### 4.4 `tools/`（仅开发期）

- `dev-launch-server`：`adb push` 固定版本 server.jar、以给定 scid 启动、打印
  日志；退出时 `cleanup`。
- `vendor/scrcpy-server-5.0.1.jar`：随仓库固定，避免版本漂移。

## 5. 数据流

1. 触摸：controller 捕获 MotionEvent → `TouchMapper` 换算 → `ControlMessageWriter`
   → TCP control → agent 代理 → scrcpy server → `InputManager.injectInputEvent`。
2. 画面：server `MediaCodec` 硬编 H.264 → video socket → agent 代理 → TCP →
   controller `VideoDemuxer` → 硬解 → 渲染。
3. 旋转：server 重建编码会话并发送 session packet → controller 重建解码器与
   Surface 尺寸。

## 6. 安全模型

自用不等于不设防，这条通道可以点亮被控手机上的任意 App。

- 仅在被控端**主动开启**"允许接管"后监听；默认关闭。
- 连接必须提供 6 位配对码（单次有效，超时作废）；配对成功后把 controller 的
  `deviceId` 写入白名单。
- 被控端常驻可见提示 + 一键断开；断开后立即关闭监听。
- M1 明文 TCP 仅限可信局域网；M3 公网阶段必须加 TLS（或整体走 WireGuard）。
- 禁止"广播发现 + 无码直连"。

## 7. 错误处理与恢复

| 场景 | 处理 |
|---|---|
| server 进程退出 | agent 检测 socket EOF → 通知 controller → 展示原因；M2 自动重启 |
| controller 断网 | 指数退避重连（1s/2s/4s…，上限 30s）；agent 保留会话 15s |
| 配对码错误 | 拒绝并延迟，连续 5 次锁定 60s |
| 旋转 | 重建解码器；渲染层先显示上一帧直到新 session |
| 解码器异常 | 重建 MediaCodec；连续失败 3 次断开并提示 |
| HyperOS 杀进程 | M2：前台服务 + 电池白名单 + 自启引导；M1 仅记录 |
| 协议版本不匹配 | server 启动时即失败；agent 展示明确错误 |

## 8. 测试策略

- **单元测试**：`ControlMessageWriter` / `VideoDemuxer` 用 scrcpy 官方测试向量
  对齐；配对码状态机。
- **集成测试**：agent 的 LocalSocket 代理与假 server 回环（JVM/Instrumented）。
- **端到端测试**（两真机）：
  - 脚本注入已知坐标点击，断言被控端 UI 状态变化；
  - 旋转、锁屏、切换 App 各跑一遍；
  - 断网 10s 后恢复，验证重连。
- **延迟测量**：在 agent 侧放一个测试 Activity，收到注入点击后立即改变纯色背景；
  controller 记录"发出触摸"到"渲染出对应帧"的时间差，统计 p50/p95。
- **稳定性**：连续运行 30 分钟，记录掉线次数、内存、解码错误。

## 9. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M0（已完成） | 环境与可行性 | scrcpy 在本机 Android 16 上跑通；延迟/截图基线 |
| M1 | 局域网闭环，PC 启动 server | §1.1 的 5 条全部满足 |
| M2 | Shizuku 启动器 + 保活 + 自启 | 闲置手机重启后无需 PC 即可被控 |
| M3 | 公网连接 | 外网下可连，延迟可接受，传输加密 |
| M4 | 适老体验 | 一键求助、极简被控端、敏感页提示 |

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| scrcpy 协议不向后兼容 | 客户端/服务端错配即失败 | vendor 固定 5.0.1，版本号写入握手校验 |
| 控制消息需从零实现 | 触摸行为错误 | 用官方测试向量做单测，逐条对齐 |
| 各 ROM shell 权限差异 | 某些机型采集失败 | 真机矩阵验证；失败时回退 MediaProjection+无障碍 |
| Google 讨论禁止 adbd localhost 回环（CVE-2026-0073） | Shizuku 类方案可能失效 | 启动层抽象；保留无障碍回退；关注落地时间 |
| HyperOS 自动关闭开发者选项/杀进程 | 连接中断 | M2 处理：白名单、前台服务、引导页 |
| 明文传输被窃听 | 屏幕内容泄露 | M1 仅可信局域网；M3 强制 TLS/VPN |
| 解码器兼容性 | 花屏/绿屏 | 支持 h264 baseline；必要时回退软解 |

## 11. 开放问题（不阻塞 M1）

- M3 组网选型：WireGuard/headscale 自建 vs Tailscale vs WebRTC+TURN。
- 是否在 M4 加入音频与语音对讲。
- 长辈真机的品牌与 Android 版本（影响 Shizuku 自启可靠性）。

## 12. 附录：M1 启动命令（参考）

```bash
adb push vendor/scrcpy-server-5.0.1.jar /data/local/tmp/scrcpy-server.jar
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / \
  com.genymobile.scrcpy.Server 5.0.1 \
  scid=<scid> log_level=info audio=false control=true max_size=1080 max_fps=60
```

agent 侧监听 `localabstract:scrcpy_<scid>`，按 video、control 顺序接受连接。
