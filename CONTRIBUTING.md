# 参与开发

## 先做一件事

hooks 不会被 `git clone` 带过来，请手动启用一次：

```bash
bash tools/install-hooks.sh
```

这会把 `core.hooksPath` 指向仓库内的 `tools/git-hooks/`。其中 `pre-commit` 会在每次提交
前扫描个人信息（公网 IP、内网 IP、私钥、账号名），命中就拒绝提交。

**不要用 `git commit --no-verify` 绕过它。** 如果真的需要保留某个地址，改成占位符，或把
真实值放进 `.secrets/`（已被忽略），或在行末加注释 `# nodata-allow`。

## 最重要的一条规则

**仓库里不得出现任何使用者的个人信息**：节点地址、密钥、主机名、账号、邮箱、设备序列号、
二维码。文档里一律用 `<NODE_PUBLIC_IP>`、`<DEVICE_LAN_IP>` 这样的占位符。

## 开发流程

本项目用 [Spec Kit](https://github.com/github/spec-kit) 组织规格：

```
constitution → specify → plan → tasks → implement
```

- 原则与约束：`.specify/memory/constitution.md`
- 需求：`specs/001-remote-android-control/spec.md`
- 方案：`specs/001-remote-android-control/plan.md`
- 任务：`specs/001-remote-android-control/tasks.md`
- 协议契约：`specs/001-remote-android-control/contracts/`
- 验证手册：`specs/001-remote-android-control/quickstart.md`

**改代码前先看 `tasks.md`**：每个任务都有编号与文件路径。大改动请先提 issue 讨论，
不要直接改架构。

## 代码约定

| 项 | 约定 |
|---|---|
| 语言 | Kotlin，Android 原生 |
| 文档与提交信息 | 中文 |
| 标识符、日志键名 | 英文 |
| 协议层 | 必须有单元测试；用 scrcpy 官方测试向量对齐 |
| 提交 | 小步提交，说明"为什么"而不是"改了什么" |
| 遥测 | 禁止引入任何统计、崩溃上报、广告 SDK |

## 不要做的事

- ❌ 提交任何密钥、配置、二维码（见上）
- ❌ 引入非 Apache-2.0 兼容的第三方代码
- ❌ 在代码里硬编码某个人/某个节点的地址、端口
- ❌ 加入遥测或"回传诊断数据"的功能
- ❌ 移除长辈端的"正在被协助"可见提示（这是刻意的安全设计）

## 报告问题

安全相关请走 [SECURITY.md](SECURITY.md)，**不要在公开 issue 里贴节点地址或日志原文**。

## 许可证

贡献即表示同意以 [Apache-2.0](LICENSE) 授权。fork 自上游的部分保留原版权声明。
