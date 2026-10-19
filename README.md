# AndroidControl

> 让子女在外网直接看到并操作长辈的手机。**自托管、无遥测、不 root、长辈零操作。**

长辈不熟悉智能手机，遇到问题在电话里说不清。这个项目让你打开自己的手机，看到长辈手机
的实时画面，直接替他点。长辈只需要一台长期插电的手机，一次性配置好之后**再也不需要
做任何操作**。

项目处于**早期开发阶段**，接口与协议仍在变动。

## 它是什么

```
┌── 长辈手机（家里 Wi-Fi，长期插电）────────────┐
│  极简 App：求助 / 正在被协助 / 结束            │
│   └─ 通过 Shizuku 以 shell 身份启动采集引擎     │
│   └─ 在隧道上暴露 控制流 + 视频流               │
└──────────────▲───────────────────────────────┘
               │ WireGuard 隧道（你自己的节点）
┌──────────────┴───────────────────────────────┐
│  你的手机                                      │
│  fork 自 ScrcpyForAndroid：解码/渲染/触摸/输入法 │
└──────────────────────────────────────────────┘
```

- **不 root**：长辈手机不做任何破解，shell 能力由一次性激活的 Shizuku 提供。
- **不经第三方**：所有流量只经过**你自己**的服务器；没有账号体系，没有云端服务。
- **不落盘**：屏幕内容不写磁盘，默认不录制。
- **看得见**：被协助期间长辈屏幕上始终有提示，一键即可结束。

## 隐私

本项目**没有遥测、没有统计上报、没有第三方分析 SDK**。所有数据留在你自己的两台设备和
你自己搭的节点上。详见 [PRIVACY.md](PRIVACY.md)。

**使用前请取得长辈的知情同意。** 这个工具会看到长辈手机上的全部内容，包括可能的短信
验证码、聊天记录、支付页面。这是家庭内部的信任问题，不该绕过。

## 快速开始

完整步骤见 `docs/ops/`。三步：

**1. 搭节点**（任意有公网 IP 的机器）—— 两种方式，任选其一：

```bash
# 方式 A（推荐）：配对中继。长辈手机除了本 App 什么都不用装
python3 tools/relay-server.py --listen 0.0.0.0:27180

# 方式 B：WireGuard。要链路加密时用
sudo bash tools/setup-node.sh
```

中继方式下，控制端只需要输入长辈手机上的 6 位数字即可连接，不需要地址、不需要 VPN。

**2. 配置两台手机**

在两台手机上安装 WireGuard App，扫描对应的二维码导入配置。控制端设置 `10.66.66.2`，
长辈端 `10.66.66.3`。

配置使用**分流**（`AllowedIPs = 10.66.66.0/24`），不会影响手机正常上网。

**3. 部署长辈端 + 编译控制端**

```bash
# 长辈端（一次性，需在设备上激活 Shizuku）
cd agent && ./gradlew assembleDebug && adb install app-debug.apk

# 控制端
cd controller && ./gradlew assembleDebug && adb install app-debug.apk
```

长辈端按照 `docs/ops/elder-device-setup.md` 完成一次性授权（Shizuku、电池白名单、
自启动白名单）。

## 当前状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 调研与可行性验证 | ✅ 完成 |
| M1 | 跨公网闭环（看屏 + 操作 + 长辈零操作） | 🔨 开发中 |
| M2 | 弱网与长期稳定 | 计划 |
| M3 | 传输优化与安全加固 | 计划 |
| M4 | 适老体验（一键求助、敏感页提示） | 计划 |

路线图与决策记录见 `docs/ROADMAP.md`、`docs/DECISIONS.md`。

## 参与开发

```bash
bash tools/install-hooks.sh   # 克隆后跑一次：启用提交前个人信息检查
```

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 项目结构

```text
agent/        长辈端 App（自研）
controller/   控制端 App（fork 自 Miuzarte/ScrcpyForAndroid）
protocol/     两端共用的协议编解码
vendor/       固定版本的 scrcpy-server
tools/        节点搭建、数据检查等脚本
specs/        需求、方案、协议契约、任务清单、验证手册
docs/         路线图、决策记录、运维手册
```

## 安全

发现安全问题请按 [SECURITY.md](SECURITY.md) 的方式报告，**不要在公开 issue 里贴
你的节点地址或密钥**。

## 许可证

[Apache-2.0](LICENSE)。保留上游署名，见 [NOTICE](NOTICE)。

## 致谢

这个项目站在两个优秀的开源项目上：

- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — 屏幕采集、硬件编码与
  输入注入的引擎（Apache-2.0）
- [Miuzarte/ScrcpyForAndroid](https://github.com/Miuzarte/ScrcpyForAndroid) — 控制端
  的 Android 实现，我们在此基础上改造（Apache-2.0）

另有 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 提供免 root 的 shell
能力（Apache-2.0）。
