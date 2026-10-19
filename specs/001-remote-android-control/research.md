# Research: 远程接管 Android 手机（WAN 版）

**Date**: 2026-10-18（含 2026-10-18 当天实测）
**Feature**: 001-remote-android-control

标注规则：**【实测】**=本机/本服务器测量；**【官方】**=官方文档或源码；**【二手】**=第三方转载，
不单独作为决策依据。

## R1. 现成产品与开源项目

### 商业产品

| 类别 | 代表 | 免 root 远控 | 老人端负担 | 局限 |
|---|---|---|---|---|
| 厂商自带 | OPPO/vivo 远程协助、苹果 FaceTime | 是 | 最低 | 锁品牌/账号 |
| 厂商自带 | 华为畅连、荣耀 | 否（只看屏） | 低 | 不能点击 |
| 厂商自带 | 小米通话远程协助 | 曾是 | 低 | 2025-12 停服 |
| 第三方 | 向日葵/ToDesk/AnyDesk/AirDroid | 是 | 中（无障碍+录屏） | 老人自助困难 |
| 开源 | RustDesk | 是 | 中 | MediaProjection+无障碍；锁屏不可输入 |

**结论**：没有产品同时满足"跨品牌 + 真点击 + 老人零操作 + 跨公网"。

### 开源项目（关键发现）

| 项目 | 语言/许可 | 对我们的价值 |
|---|---|---|
| **Miuzarte/ScrcpyForAndroid** | Kotlin + NDK / **Apache-2.0** | ⭐ **控制端直接 fork**：手机控手机、mDNS 发现、二维码配对、中文输入、剪贴板、录制 |
| Genymobile/scrcpy | C / Apache-2.0 | 引擎本体（server 端复用） |
| hqw700/ScrcpyOverWebRTC | Vue+Go / Apache-2.0 | 浏览器控制 + Docker/TURN，参考其 Shizuku 脱机自启 |
| RustDesk | Rust/C++ / GPL | 达不到要求（见 R1 表） |

**ScrcpyForAndroid 的代码结构【二手：GitHub 仓库树】**：
- `nativecore/` 是传输层：`DirectAdbClient`、`DirectAdbPairingClient`、`NativeAdbService`、
  `UsbAdbTunnel`、`AdbMdnsDiscoverer` → **ADB 通信被隔离在这一层**。
- 解码渲染层：`MediaCodecVideoDecoder`、`VideoDecoderController`、
  `PersistentVideoRenderer`、`ScrcpyAudioPlayer` → 与传输无关。
- 仓库带 `.gitmodules` 与 `submodule/`，构建需 NDK → 说明它把 **scrcpy 的 C 客户端**
  编进来（`NativeCoreFacade`），协议正确性由上游保证。

**推论**：把"通过 ADB 连接目标设备"换成"连接我们自己的长辈端"，其余代码可原样保留。
这是本项目最大的一笔复用。

## R2. 免 root 的四条技术路线

| 路线 | 能否控制 | 老人端操作 | 系统限制 | 结论 |
|---|---|---|---|---|
| MediaProjection + 无障碍 | 是 | 每次点"同意" | Android 14 每次授权；16 锁屏即停；金融 App 可检测 | 仅作回退 |
| **Shizuku（shell 授权）** | 是 | 一次性 | 依赖 Shizuku 存活 | **M1 主路线** |
| adb / scrcpy（shell） | 是 | 重启需重连 | adbd 端口与开关易失效 | 开发兜底 |
| root | 是 | 一次性 | 刷机风险 | 不采用 |

**关键事实【官方】**：
- MediaProjection 自 Android 14 起每次采集必须重新授权，token 单次使用。
- Android 15 QPR1 起锁屏自动停投屏；Android 16 `MediaProjectionStopController` 强制：
  息屏/锁屏/通话结束即停。
- shell 身份（uid 2000）具备采集与注入能力；`InputManager.injectInputEvent` 注入的
  事件不带无障碍标记，金融 App 无法据此识别。

## R3. 本机真机实测【实测】

设备：Redmi K60 Ultra，Android 16 / HyperOS 3.0，1220×2712，arm64，
shell uid 2000，`ro.debuggable=0`。

| 项目 | 结果 | 判定 |
|---|---|---|
| `screencap -p` 单帧 | 939 / 969 / 987 ms | 不可用 |
| `screenrecord --output-format=h264 -` | 2 秒 119KB，可流式 | 秒级延迟，仅应急 |
| `adb shell input tap` 往返 | 115–203 ms | 进程启动开销为主 |
| **scrcpy 5.0.1 server** | 733KB 推送 0.4s，`app_process` 启动，8 秒硬编录制成功 | **主链路成立** |

## R4. scrcpy 5.0.1 协议【官方：源码与 doc/develop.md】

- 启动：`CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 <k=v…>`。
- 版本严格匹配，协议不保证兼容。
- 默认 reverse 模式：客户端监听 abstract socket `scrcpy_%08x`（scid 为 31 位随机数），
  server 反向连入；连接顺序 video → (audio) → control。
- 第一个 socket 上发送 **64 字节设备名**（UTF-8，截断至 63 字节，0 填充）。
- 视频 socket：4 字节 codec id（`h264` = `0x68323634`），随后 12 字节包头：
  - 最高位 1 → session packet：字节 4–5 宽、6–7 高（大端 u16）。
  - 最高位 0 → media packet：byte0 的 bit6=config、bit5=key，PTS 61 位；
    bytes 8–11 payload 长度（大端 u32）。
- 控制消息（大端，见 `app/src/control_msg.h` 与 `test_control_msg_serialize.c`）：
  - 触摸 type=2（32 字节）：action u8、pointer id u64、x i32、y i32、screen w u16、
    screen h u16、pressure u16（0xFFFF=1.0）、action button i32、buttons i32。
  - 按键 type=0（14 字节）：action u8、keycode u32、repeat u32、metastate u32。
  - 滚动 type=3（21 字节）：x i32、y i32、w u16、h u16、hscroll i16、vscroll i16、buttons i32。
  - BACK_OR_SCREEN_ON type=4（2 字节）：action u8。
  - 类型枚举：INJECT_KEYCODE=0 … RESET_VIDEO=17。

## R5. 服务器现状【实测 2026-10-18】

**自建节点（新加坡 VPS）<NODE_PUBLIC_IP>**
- Debian 13 / 内核 6.12 / 1 vCPU / 967MB 内存 / 9.8G 盘（用 2.9G）。
- 该机同时运行着其它网络服务，与本隧道无关、互不干扰（端口与路由不混用）。
- 出口带宽【实测】：Cloudflare 测速端点下行 **95.7 MB/s（≈766 Mbps）**，带宽非瓶颈。
- 到国内 RTT【实测】：阿里 DNS 223.5.5.5 **32.3ms**、百度 180.76.76.76 **37.8ms**。
  这是相当好的线路（SG→CN 走优化路径）。
- 防火墙：iptables/nft 三链策略均 ACCEPT，无阻塞。
- WireGuard：内核模块已内置，`wireguard-tools 1.0.20210914` 已安装，`ip_forward` 已
  持久化开启。**隧已建成**：`wg0 = 10.66.66.1/24`，ListenPort 51820，
  两个 peer（controller=10.66.66.2、elder=10.66.66.3）。

**备选节点**：另有多台可用机器（境内云服务器 ×2、境外 VPS 若干）。
→ 境内节点延迟更低，可作为第二阶段优化备选；本次实测的新加坡节点（32ms）已接近可用
区间，**暂不切换**。节点选型原则见 `docs/ops/node-wireguard.md`。

## R6. 测量环境的陷阱【实测】

用户 PC 运行 **sing-box TUN 模式**（虚拟网卡接管全部流量），透明接管所有流量。
后果：
- `ping <NODE_PUBLIC_IP>` 显示 `<1ms`、`tracert` 只有一跳 —— **纯属本地伪造**。
- 端口探测"开放"也可能是代理解答，不能证明从国内可直连。

**规则**：本项目所有网络测量必须在**绕开代理**的环境下进行，或从服务器/手机侧测量。
本次服务器延迟数据即是从服务器侧取得。

## R7. 安全与合规【官方/二手】

- 项目自用、不上架：商店审核、ICP/APP 备案、软著不适用。**不需要 ICP 备案**——
  备案只在"域名解析到大陆 80/443"时要求，本项目直连 IP。
- 国内云厂商服务条款普遍禁止 VPN/代理用途；本项目节点在境外（新加坡），不受此约束，
  （自备节点若同时用作其他用途，注意端口与路由不要与本隧道混用）。
- 监管背景：国内电诈中"远程控制+屏幕共享"高发 → 必须保留绑定白名单、可见提示、
  一键结束、不落盘。
- 银行/支付应用的 `FLAG_SECURE` 窗口无法采集属系统行为。

## R8. 主机密钥事件【实测】

连接时发现服务器 ED25519 指纹（`SHA256:8GT9JH/9B77lOoRdMEDoqsrTWdDpoq6X1tPMyXwUnSo`）
与本地 known_hosts 记录（`SHA256:DOcf4Yb2F34JIbGmxr1QTZnO5iclkkAXmvsjDPjiQCs`）不一致。

**核实过程**：服务器 `/root/.ssh/authorized_keys` 中第一条公钥指纹为
`SHA256:/v6nBLdPfUCy0wtiCK+IsohUdYUMTaCv9HZw9bMh/uw`（（你的 SSH 公钥）），与用户本地
`id_ed25519.pub` 完全一致；`/root` 下的 服务器上的运维痕迹均为账号主人本人
的操作痕迹。

**结论**：密钥变化源于 2026-09 底重装系统，机器归属确认为用户本人。

**教训**：接受主机密钥变更前必须先取得独立证据（此处为"用用户私钥成功认证"），
不能因为"能连上"就默认安全。

## R9. 关键决策

| 决策 | 选择 | 理由 | 放弃的备选 |
|---|---|---|---|
| 采集/编码/注入 | scrcpy-server 5.0.1（vendor） | 已实测、Apache-2.0、无 MediaProjection 限制 | 自研引擎 |
| 控制端实现 | **fork ScrcpyForAndroid** | 传输层与解码层分离，复用成本最低 | 自研客户端 |
| 长辈端实现 | 自研极简 App + Shizuku | 上游没有"长辈零操作"这个概念 | 直接用上游（需无线调试常开） |
| 特权来源 | Shizuku | 不 root、可自动恢复 | root / adb 常开 |
| 跨公网 | **WireGuard hub**（新加坡节点） | 30 分钟可落地、加密天然、已验证 32ms | headscale/WebRTC（M3 再评估） |
| 隧道网段 | 10.66.66.0/24，两端分流 | 不影响手机正常上网 | 全局路由（会影响老人上网） |
| 规格流程 | Spec Kit | 用户指定；产物可追踪 | superpowers（已用于立项调研） |

## R10. 待验证风险

1. **Shizuku 在长辈机型上能否开机自启**（Android 13+ 需"可信 WLAN"）——M1 第一周验证。
2. 长辈端 App + WireGuard + Shizuku 三个组件的自启链路是否可靠。
3. 打洞优化（避免流量全走节点）——M3。
4. fork 上游后续更新的合并成本。
5. 手机蜂窝网络对 UDP 51820 的放行情况（部分运营商对非标准 UDP 端口限速）。

## R11. T009 结论：Shizuku 以 shell 身份拉起引擎【实测 2026-10-19】

**结论：可行，且已在真机跑通。**

探针（`agent` 的 `ShizukuProbeService`，一个 Shizuku UserService）在
Redmi K60 Ultra / Android 16 上输出：

```
[UserService 进程身份]  uid=2000(shell) ... context=u:r:shell:s0
[引擎文件可见]          /data/local/tmp/scrcpy-server.jar 733930 字节
[启动引擎]              exit=124（被 timeout 掐断，说明一直在运行）
                        [server] INFO: Device: [Xiaomi] Redmi 23078RKD5C (Android 16)
[进程列表]              shell  7322  dev.ling.agent:probe
```

这证明了整条架构最关键的一环：**不 root、由 Shizuku 提供 shell 身份、以 `app_process`
拉起 scrcpy-server，全部成立。**

### 途中发现的致命兼容性问题：Shizuku 13.6.0 在联发科机型上不可用

首次用 Shizuku **13.6.0** 时 UserService 启动失败：

```
W ShizukuServiceStarter: unable to start service dev.ling.agent/dev.ling.agent.ShizukuProbeService...
Caused by: java.lang.NullPointerException: Attempt to invoke virtual method
           'boolean java.lang.Object.equals(java.lang.Object)' on a null object reference
    at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1605)
```

这是**已知上游缺陷**，非本项目代码问题：

- RikkaApps/Shizuku#1198「User services don't work on MediaTek devices」——堆栈完全一致；
  作者 vvb2060 定位为**联发科机型**的框架改动，加上 Shizuku 启动 UserService 时未经过
  `ActivityThread#bindApplication`，导致 `Application.getProcessName()` 为 null 时 NPE。
- RikkaApps/Shizuku#1171 指出成因与 Shizuku-API PR #80（网络安全配置处理）有关，
  该改动进入了 13.6.0。
- 社区反馈：**13.5.4 正常、13.6.0 坏**。

**处理：改装 Shizuku 13.5.4，UserService 立即正常。** 已在文档中固定该版本，并把
"目标机型上必须实测 Shizuku 版本"列为部署前的硬性检查。

**影响面**：长辈端手机若是联发科芯片（K60 Ultra、多数天玑机型），必须用 13.5.x；
高通机型上 13.6.0 可能正常，但仍建议统一用 13.5.4 以避免行为不一致。

### 仍未解决

- **重启后 Shizuku 不会自动运行**——13.5.4 界面明确提示"每次设备重新启动后需要重新进行"。
  这是"长辈零操作"的下一个关卡（M2）：候选方案是保持无线调试常开，由 Shizuku 的
  "可信 WLAN 自启"接管；需在本 ROM 上实测。

## R12. 长辈端闭环打通【实测 2026-10-19】

在真机（Redmi K60 Ultra / Android 16）上，纯设备内、不依赖 PC 的完整链路已跑通：

```
打开 App → 释放引擎 → shell 侧建 socket → Shizuku 以 shell 身份拉起 scrcpy-server
        → 接受 video/control 两条流（fd 经 Binder 传回）→ 在 27185/27186 暴露给控制端
```

实测结果：

| 验证项 | 结果 |
|---|---|
| UserService 身份 | `uid=2000(shell)`、`u:r:shell:s0` |
| 引擎启动 | `[server] INFO: Device: [Xiaomi] Redmi 23078RKD5C (Android 16)` |
| 视频通道 | 设备名 `23078RKD5C`、编码 `h264`、会话包尺寸 `320x720`（max_size=720，比例正确） |
| 控制通道 | 握手 + 注入滑动后**截图哈希改变**（`eb264147…` → `cc335da2…`）→ 注入真实生效 |

### 途中发现的三个坑（都已修复，写入契约文档）

1. **SELinux 跨域**：shell 进程**不能**连接应用创建的抽象 socket（`Permission denied`）。
   解法：由 shell 侧的 UserService 建 socket 并接受引擎连接，再用 Binder 把 **fd**
   交给应用。这是本方案能成立的关键一步。
2. **`scid` 是十六进制**：scrcpy 用 `Integer.parseInt(v, 16)` 解析，传十进制直接崩。
3. **流头会丢**：引擎在应用启动最初几十毫秒就发出「设备名 + 编码 id + 会话包 + 配置包」，
   此时通常还没有控制端连进来。必须缓存并在客户端接入时回放，否则后接入的客户端
   只能拿到裸 H.264，解不出画面。这一条同时解决了"断线重连恢复画面"。

另：`LocalSocket` 对象若被 GC 回收会 shutdown 底层 socket，把 fd dup 出去的同时
必须**持住原 socket 引用**直到会话结束。

### 结论

架构上最关键的不确定性（免 root 拿 shell、跨域传流、协议对接、注入生效）已全部消除。
剩下的都是常规工程量：控制端 App、适配 UI、保活与自启、公网稳定性。

## R13. M1 主链路在"两机分离"下验证通过【实测 2026-10-19】

配置：**模拟器（控制端）** → 宿主机中继 → **真机 Redmi K60 Ultra（长辈端）**。
（模拟器自身网络被宿主机 TUN 干扰，用 `adb reverse` + `tools/tcp-relay.py` 绕过，
这不影响被测链路本身。）

| 验证项 | 结果 |
|---|---|
| 跨设备连接 | 控制端连上长辈端，握手通过 |
| 视频解码渲染 | 设备名 `23078RKD5C`、`h264`、320x720，**首帧上屏**，控制端看到的是真机画面 |
| 性能 | 静态画面 1.7 fps、网络 RTT 95ms（含宿主机中继）、质量"优秀" |
| **触摸注入（经控制端 UI）** | 在控制端画面点击后，**真机截图哈希改变**，注入真实生效 |
| 长辈端状态联动 | `Preparing → Ready → Active（正在被协助）`，由真实连接驱动 |

### 途中修复的问题（都已写入代码注释与契约文档）

1. **控制端握手用 `BufferedReader` 预读**，把紧随 `OK\n` 的视频流头部吞掉，
   导致"设备名乱码、永远解不出帧"。改为逐字节读到换行。
2. **联发科解码器不吃 CODEC_CONFIG 缓冲**：必须等"尺寸 + 配置包"齐备后建解码器，
   并把 SPS/PPS 放进 `MediaFormat` 的 `csd-0/csd-1`，否则报 `CSD is not calculated` 且不出帧。
3. **后接入的客户端拿不到 IDR**：引擎开头的关键帧早已过去，画面静止时不再产生帧。
   解法是发 scrcpy 的 `RESET_VIDEO`（控制消息 type=17）让引擎重新编码。
4. **前台服务是必须的**：长辈端退到后台后，普通进程会被系统冻结、会话中断；
   `AgentService` 常驻通知后会话稳定。

### 模拟器在本机的坑（供后来者参考）

- 宿主机跑着 TUN 模式的代理时，模拟器 `eth0/wlan0` 起不来（`ip route` 为空）。
  用 `adb reverse` 即可绕过，不影响测试。
- Play Store 镜像的 `/data` 只有 774M，装不下就报 `not enough space`；
  在 AVD 的 `config.ini` 里把 `disk.dataPartition.size` 调到 6G 并 `-wipe-data` 重启。
- 无窗口模式（`-no-window -gpu swiftshader_indirect`）比带窗口稳定得多。

## R14. 重启生存实测：目前最大的产品缺口【实测 2026-10-19】

**做法**：在长辈端真机（Redmi K60 Ultra / HyperOS 3 / Android 16）上执行重启，
观察哪些东西能自己恢复。

**重启前**：Shizuku 13.5.4 运行中（`shizuku_server` 以 shell 身份在跑）、
无线调试已开启（`adb_wifi_enabled=1`）、长辈端处于"已准备好协助"。

**重启后**（等待 4 分钟）：

| 观察项 | 结果 |
|---|---|
| 手机是否在线 | 是（ping 通） |
| 无线调试是否恢复 | **否**——mDNS 不再广播 `_adb-tls-connect`，端口也不再监听 |
| Shizuku 是否自动运行 | **否**（非 root 模式必须借助调试通道重新启动） |
| adb 可达性 | 丢失（5555/5037 均关闭，随机端口不可猜） |

**结论**：在这台机器上，**重启后长辈端不再可被连接**，且恢复需要有人
重新打开无线调试并启动 Shizuku——这是长辈做不到的操作。

### 为什么这在意料之外又在情理之中

非 root 的 Shizuku 本质是"借一次调试通道拿到 shell"。设备重启会同时失去：
调试通道（无线调试未自动恢复）与 Shizuku 进程。二者都需要一次人工触发。

### 可选出路（M2 需要决策）

| 方案 | 代价 | 说明 |
|---|---|---|
| 保持设备不重启 | 无 | 长期插电的手机通常数月不重启；真正的触发点是系统更新/断电/崩溃 |
| 让长辈点一次 | 需要长辈配合 | 我们的 App 可以引导（大字提示"请点一下家人才能帮你"），但仍要长辈动手 |
| 换成无线调试能跨重启保留的机型 | 换机 | 需实测（部分类原生 ROM 会保留） |
| 用支持开机自启的 Shizuku（13.6.0） | 有兼容风险 | 13.6.0 在**联发科**机型上 UserService 崩溃；高通机型可考虑（需实测） |
| root | 用户已否决 | root 后 Shizuku 可开机自启，问题消失 |
| 退化到厂商自带远程协助 | 仅同品牌 | 作为"链路不可用"时的兜底 |

**当前建议**：M2 先把"恢复路径"做扎实——App 在开机后检测到 Shizuku 未运行，
用大字提示 + 一键跳转 Shizuku 启动页，把长辈要做的事压到最少；
同时把"换用能保留无线调试的机型"列为选购长辈机时的硬性检查项。
