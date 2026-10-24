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
│   └─ 在隧道上暴露 控制流 + 视频流 + 语音流       │
└──────────────▲───────────────────────────────┘
               │ 配对中继（或你自己的 WireGuard）
┌──────────────┴───────────────────────────────┐
│  你的手机                                      │
│  fork 自 ScrcpyForAndroid：解码/渲染/触摸/输入法 │
└──────────────────────────────────────────────┘
```

- **不 root**：长辈手机不做任何破解，shell 能力由一次性激活的 Shizuku 提供。
- **不经第三方**：所有流量只经过**你自己**的服务器；没有账号体系，没有云端服务。
- **不落盘**：屏幕内容不写磁盘，默认不录制。
- **看得见**：被协助期间长辈屏幕上始终有提示，一键即可结束。
- **6 位数字配对**：控制端只输长辈手机上的数字，不需要地址、不需要 VPN。
- **双向语音**：看着画面的同时直接说话，不用再开免提电话。
- **敏感页面提示**：停在支付/银行 App 时，长辈屏幕上会变成红色提示。
- **一键求助**：长辈按下"开启协助"，你的设备卡片上就会出现"刚刚按了求助"。
- **多台长辈**：可以保存多台设备，按名字区分。

## 隐私

本项目**没有遥测、没有统计上报、没有第三方分析 SDK**。所有数据留在你自己的两台设备和
你自己搭的节点上。详见 [PRIVACY.md](PRIVACY.md)。

**使用前请取得长辈的知情同意。** 这个工具会看到长辈手机上的全部内容，包括可能的短信
验证码、聊天记录、支付页面。这是家庭内部的信任问题，不该绕过。

## 快速开始

完整步骤见 `docs/ops/`。三步：

**1. 搭节点**（任意有公网 IP 的机器）—— 两种方式，任选其一：

```bash
# 方式 A（推荐）：配对中继。两台手机都只需要装本 App
# 公网部署请只开 TLS 端口（自动生成自签证书并打印 SHA-256 指纹）
NODE=root@<节点> TLS_PORT=27443 bash tools/deploy-relay.sh

# 方式 B：WireGuard。想要链路层加密时用
sudo bash tools/setup-node.sh
```

TLS 版本请在构建前把指纹写进 `local.properties`（不入库）：

```properties
relay.defaultAddress=<节点>:27443
relay.tlsFingerprint=<部署脚本打印的 SHA-256>
```

**2. 两台手机装同一个 App**

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

同一个安装包有两种角色：首页显示**这台手机自己的配对码**，也可以输入对方的配对码去协助别人。
被协助的那台按 `docs/ops/elder-device-setup.md` 完成一次性授权（Shizuku、麦克风、悬浮窗、电池白名单）。
打开首页即待命，被协助不需要再按任何按钮。

**3. （可选）网页控制端**

`web/` 是一个 Next.js 应用，部署到 Vercel 后可以用电脑浏览器直接协助——不用在控制端装 App。
它需要连到中继的 **WSS** 入口（HTTPS 页面不能连 `ws://`），部署与反向代理方式见
`docs/ops/relay-node.md`。

## 当前状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 调研与可行性验证 | ✅ |
| M1 | 跨公网闭环（看屏 + 操作） | ✅ |
| M2 | 弱网、长跑与自愈 | ✅ |
| M3 | 传输优化与安全加固 | ✅ |
| M4 | 适老体验（求助、敏感页、大字、播报） | ✅ |
| M5 | 双向语音、多设备、网页控制端 | ✅（iOS 控制端为可选项，未做） |
| M6 | 生产加固（签名发布、限速、TLS、E2E、文本输入、可观测性） | 🚧 进行中 |

细节与实测数据见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 参与开发

```bash
bash tools/install-hooks.sh   # 克隆后跑一次：启用提交前个人信息检查
```

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 项目结构

```text
app/          唯一安装包（首页 = 自己的配对码 + 协助别人）
agent/        被协助逻辑（库）：Shizuku 启动引擎、中继连接、语音/播报
controller/   控制端逻辑（库，fork 自 Miuzarte/ScrcpyForAndroid）
protocol/     两端共用的协议编解码与 TLS 指纹校验
relay/        配对中继（Rust，明文 + TLS 双监听）
web/          浏览器控制端（Next.js + WebCodecs）
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
