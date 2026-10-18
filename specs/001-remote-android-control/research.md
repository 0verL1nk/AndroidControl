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
