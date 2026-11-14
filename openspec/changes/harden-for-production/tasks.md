# 任务：生产级加固

## 1. 正式签名与发布

- [x] 1.1 新增 `tools/gen-release-keystore.sh`：生成 `.secrets/release.jks` 与 `.secrets/keystore.properties`（含随机口令、备份提示）
- [x] 1.2 `:app` 配置 release signingConfig：读取 `keystore.properties`/环境变量，缺失时报错；用 `apksigner` 本地验证签名
- [x] 1.3 新增 `.github/workflows/release.yml`：`v*` tag 触发，用 Secrets 解码 keystore，构建签名 APK 并创建 GitHub Release
- [x] 1.4 `docs/ops/release.md`：签名、密钥备份、版本号与 CHANGELOG 流程

## 2. 中继口令限速与审计

- [x] 2.1 中继实现按哈希（及直连 IP）的失败计数与锁定窗口，支持 `--max-fails`、`--lock-secs`
- [x] 2.2 输出 `AUDIT pair_failed` / `AUDIT locked` 日志，不记录口令本身
- [x] 2.3 集成测试：达到阈值锁定、成功后清零、`--max-fails 0` 关闭
- [x] 2.4 部署到节点并验证（真实 FIND 失败/锁定/解锁）

## 3. 传输加密（TLS + 指纹固定）

- [x] 3.1 中继支持 `--tls-listen/--tls-cert/--tls-key`，同一协议逻辑跑在 TLS 上
- [x] 3.2 长辈端 RelayClient 支持 TLS 与证书 SHA-256 指纹校验；PairingStore 增加配置项
- [x] 3.3 集成测试：TLS 配对成功、错误指纹拒绝、无 TLS 参数时行为不变
- [x] 3.4 部署文档 + 节点与真机 TLS 端到端：真机 0.3.2（debug）经 27443 连上、视频 3.7 KB 送达；
      故意配错指纹时日志为「证书指纹不匹配（期望 00…，实际 0dc8…）」，恢复正确指纹后立即在线

## 4. 自动化端到端测试

- [x] 4.1 `relay/tests/e2e_stream.rs`：脚本化 agent 发送伪造流头/会话/配置/帧，断言控制器逐字节收到；控制方向同理
- [x] 4.2 `tools/e2e-web.mjs`：真实中继 + 脚本化 agent + WebSocket 控制器，验证 video/control 角色
- [x] 4.3 网页端抽出 `parse-stream.ts` 纯函数并用 `node:test` 覆盖分片/会话/配置/关键帧
- [x] 4.4 CI 增加 E2E job（relay 测试 + web 单测 + e2e-web）

## 5. 文本输入

- [x] 5.1 协议层实现 `ControlMessageWriter.injectText` 并补单测（官方字节布局）
- [x] 5.2 文字输入真机验证：经控制流向 Chrome 地址栏注入 `hello123`/`zzz9` 并出现在输入框；
      App 顶栏键盘入口与网页输入框走同一条 `injectText` 路径（网页字节有单测）
- [x] 5.3 网页控制端增加文本输入并发送；验证同一路径

## 6. 网页双向语音

- [x] 6.1 浏览器采集麦克风（AEC/降噪），AudioWorklet 降采样为 16kHz/640B 帧并经 `audio` 角色发送
- [x] 6.2 接收 PCM 播放（有界队列、丢旧帧），提供麦克风开关与权限失败提示
- [x] 6.3 语音联调（真机）：修复长辈端走听筒/音量 1/15；修复小米上 AEC 顺序导致的麦克风静音；
      自环验证 1kHz 回传能量提升约 600 倍；AEC 仅部分衰减回声，控制端建议戴耳机

## 7. 可观测性

- [x] 7.1 中继新增 `GET /healthz` 与 `GET /metrics`（uptime、信令、待配对、求助、连接/配对计数）
- [x] 7.2 运维文档：外部探测、journald 轮转、`--max-fails`/TLS 开关说明

## 8. 版本与收尾

- [x] 8.1 版本号与 CHANGELOG 更新，打 `v0.3.0` tag（release 成功，APK 已挂到 GitHub Release）
- [x] 8.2 更新 `docs/ROADMAP.md`（M6 生产加固）、`SECURITY.md`、README 的部署入口

## 9. 设备白名单与管理面板

- [x] 9.1 中继默认拒绝：信令登记设备码/机型，未加白设备的 FIND/ASK 一律失败
- [x] 9.2 `/admin` 面板：Bearer 令牌（连续 10 次错误锁 10 分钟）、一键加白/取消、状态落盘
- [x] 9.3 节点端到端验证：登记 → 拒绝 → 加白 → 配对 → 取消 → 重启后保留
- [x] 9.4 发布 APK 由 CI Secrets 注入内置中继地址与证书指纹（仓库不含节点信息）

## 10. 独立复核修复（27 项确认）

- [x] 10.1 critical：成功配对不再清来源 IP；新增全局失败漏斗；回环来源读取 CF-Connecting-IP
- [x] 10.2 high：信令行/HTTP 头部上限、首行与头部超时、连接上限、长辈端断开补 WS CLOSE、TLS 模式明文默认回环
- [x] 10.3 发布与部署校验：签名证书与 keystore 一致、版本与 tag 一致、部署后端口就绪
- [x] 10.4 网页：重连重置解析器、payload 上限、播放重采样、PCM 对齐、上行背压、离开会话停麦
- [x] 10.5 Android：重连指数退避、统计线程回收、设备键 (host,token)、指纹不被静默清除、触摸 UP、默认端口 27443
- [x] 10.6 指纹固定进入 CI：keytool 自建服务端，正确指纹通过、错误指纹拒绝

## 11. 仓库拆分与整理

- [x] 11.1 服务端源码迁往私有仓库 `AndroidControl-relay`，public 仓库以 submodule 引用
- [x] 11.2 公开仓库历史移除服务端源码（强制重写；全量克隆核对 0 个 relay blob）
- [x] 11.3 目录整理：`specs/` → `docs/specs/`，早期设计归档到 `docs/archive/`，新增 `docs/README.md` 索引
- [x] 11.4 公开 CI 改为 Android + 网页检查；服务端 CI 移到私有仓库，使用本机 WSL 的 Linux 自托管 runner（免费，能构建节点用的 Linux 二进制）

## 12. 依赖

- [x] 12.1 网页：Next/React 升级到 16.4.6 / 19.3.1；`npm audit --omit=dev` 为 0；
      剩余 moderate 仅来自 eslint 开发依赖的 `minimatch@3`（不随页面发布）
- [x] 12.2 Android/Rust 依赖保持锁定版本（`Cargo.lock`、Gradle 版本目录），本轮未做跨大版本升级
