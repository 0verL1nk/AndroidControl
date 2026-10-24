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

- [ ] 3.1 中继支持 `--tls-listen/--tls-cert/--tls-key`，同一协议逻辑跑在 TLS 上
- [ ] 3.2 长辈端 RelayClient 支持 TLS 与证书 SHA-256 指纹校验；PairingStore 增加配置项
- [ ] 3.3 集成测试：TLS 配对成功、错误指纹拒绝、无 TLS 参数时行为不变
- [ ] 3.4 部署文档（自签证书、指纹、长辈端配置）并在节点与真机上完成一次 TLS 端到端验证

## 4. 自动化端到端测试

- [ ] 4.1 `relay/tests/e2e_stream.rs`：脚本化 agent 发送伪造流头/会话/配置/帧，断言控制器逐字节收到；控制方向同理
- [ ] 4.2 `tools/e2e-web.mjs`：真实中继 + 脚本化 agent + WebSocket 控制器，验证 video/control 角色
- [ ] 4.3 网页端抽出 `parse-stream.ts` 纯函数并用 `node:test` 覆盖分片/会话/配置/关键帧
- [ ] 4.4 CI 增加 E2E job（relay 测试 + web 单测 + e2e-web）

## 5. 文本输入

- [ ] 5.1 协议层实现 `ControlMessageWriter.injectText` 并补单测（官方字节布局）
- [ ] 5.2 Android 控制端增加文字输入入口并发送；真机验证搜索框输入
- [ ] 5.3 网页控制端增加文本输入并发送；验证同一路径

## 6. 网页双向语音

- [ ] 6.1 浏览器采集麦克风（AEC/降噪），AudioWorklet 降采样为 16kHz/640B 帧并经 `audio` 角色发送
- [ ] 6.2 接收 PCM 播放（有界队列、丢旧帧），提供麦克风开关与权限失败提示
- [ ] 6.3 与长辈端联调：双向可听、无回声反馈

## 7. 可观测性

- [ ] 7.1 中继新增 `GET /healthz` 与 `GET /metrics`（uptime、信令、待配对、求助、连接/配对计数）
- [ ] 7.2 运维文档：外部探测、journald 轮转、`--max-fails`/TLS 开关说明

## 8. 版本与收尾

- [ ] 8.1 版本号与 CHANGELOG 更新，打 `v0.3.0` tag（触发 release）
- [ ] 8.2 更新 `docs/ROADMAP.md`（M6 生产加固）、`SECURITY.md`、README 的部署入口
