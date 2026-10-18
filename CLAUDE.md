<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/001-remote-android-control/plan.md`
<!-- SPECKIT END -->

# 项目约定（给 AI 协作者）

## 这是什么

让子女跨公网看到并操作长辈的 Android 手机。**开源、可自托管、无遥测、不 root、
长辈端零操作。** 详见 `README.md`。

## 动手前必读

| 文档 | 用途 |
|---|---|
| `.specify/memory/constitution.md` | 项目原则，冲突时以此为准 |
| `specs/001-remote-android-control/spec.md` | 需求（做什么） |
| `specs/001-remote-android-control/plan.md` | 技术方案（怎么做） |
| `specs/001-remote-android-control/tasks.md` | 任务清单（改代码看这个） |
| `specs/001-remote-android-control/research.md` | 调研证据与实测数据 |
| `docs/DECISIONS.md` | 决策记录，改架构前先看有没有定过 |
| `docs/ROADMAP.md` | 里程碑与当前进度 |

## 硬约束

1. **不得提交个人信息**：节点地址、密钥、主机名、账号一律用占位符；真实值放 `.secrets/`
   （已忽略）。提交前 `tools/check-personal-data.sh` 会自动拦截。
2. **不重造引擎**：采集/编码/注入用 `vendor/scrcpy-server-5.0.1.jar`；控制端复用
   `controller/`（fork 自 ScrcpyForAndroid），只动连接层。
3. **不 root**：长辈端特权来源是 Shizuku，接口在 `agent/launcher/ServerLauncher`。
4. **无遥测**：不引入任何统计/崩溃上报/广告 SDK。
5. **测量必须绕开本机代理**：开发机跑着 TUN 模式的代理，直接 ping 得到的 RTT 是假的。
   测延迟要从服务器侧或手机侧测。

## 常用命令

```bash
bash tools/install-hooks.sh                 # 启用提交前检查（新克隆必跑）
bash tools/check-personal-data.sh           # 手动扫描个人信息
bash tools/setup-node.sh                    # 在节点上搭建 WireGuard（root）

# 构建（Gradle 工程尚未初始化，见 tasks.md Phase 1）
./gradlew :protocol:test
./gradlew :agent:assembleDebug
./gradlew :controller:assembleDebug
```

## 当前进度

M0 完成（调研与真机可行性）。M1 进行中：下一个动作是 `tasks.md` 的 **T009 —— 验证
Shizuku 能否以 shell 身份拉起 scrcpy-server**。这是整个架构最大的风险点，必须最先做。
