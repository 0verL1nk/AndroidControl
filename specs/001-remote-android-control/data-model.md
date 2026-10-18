# Data Model: 远程接管 Android 手机

**Date**: 2026-10-18

## 实体

### DeviceIdentity（设备身份）

每台设备首次运行生成，持久保存。

| 字段 | 类型 | 说明 |
|---|---|---|
| deviceId | UUID | 设备唯一标识，用于绑定与连接记录 |
| displayName | String | 展示名（如"爸爸的手机"） |
| role | Enum | `ELDER`（长辈端）/ `CONTROLLER`（控制端） |
| publicKey | ByteArray | 会话握手用的长期公钥（Ed25519） |
| createdAt | Instant | 生成时间 |

**规则**：清空应用数据即视为新设备，需要重新绑定。

### Binding（绑定关系，长辈端持有）

| 字段 | 类型 | 说明 |
|---|---|---|
| controllerId | UUID | 被允许的控制端 deviceId |
| displayName | String | 控制端展示名 |
| boundAt | Instant | 绑定时间 |
| lastUsedAt | Instant? | 最近一次成功连接时间 |
| revoked | Boolean | 是否已吊销 |

**规则**：只有存在且未吊销的绑定才允许建立会话；吊销立即生效（进行中的会话也断开）。

### Session（会话）

| 字段 | 类型 | 说明 |
|---|---|---|
| sessionId | String | 本次会话标识 |
| controllerId | UUID | 对端身份 |
| state | Enum | `IDLE` / `PAIRING` / `ACTIVE` / `DISCONNECTED` / `ENDED` |
| startedAt | Instant | 开始时间 |
| frameSize | Size | 当前画面宽高（随旋转变化） |
| estimatedLatencyMs | Int | 估算延迟，用于界面显示 |

**状态迁移**：
```
IDLE ──(控制端连入)──▶ PAIRING ──(鉴权通过)──▶ ACTIVE
ACTIVE ──(网络中断)──▶ DISCONNECTED ──(重连成功)──▶ ACTIVE
ACTIVE ──(任一端结束)──▶ ENDED
DISCONNECTED ──(超时 15s)──▶ ENDED
```

### ConnectionRecord（连接记录，长辈端持有）

| 字段 | 类型 | 说明 |
|---|---|---|
| at | Instant | 时间 |
| controllerName | String | 控制端展示名 |
| durationMs | Long | 时长 |
| result | Enum | `NORMAL` / `ENDED_BY_ELDER` / `NETWORK_LOST` / `AUTH_FAILED` / `VERSION_MISMATCH` |

**规则**：只保留最近 20 条。

### RelayNode（节点）

| 字段 | 类型 | 说明 |
|---|---|---|
| name | String | 如 `node-sg` |
| endpoint | String | `<NODE_PUBLIC_IP>:51820` |
| tunnelIp | String | 本端在隧道中的地址（如 `10.66.66.2`） |
| role | Enum | `HUB`（M1）/ `DERP`（M3） |

**规则**：M1 只用配置内的静态信息，不做节点发现。

## 关系

```
DeviceIdentity(ELDER) 1 ──── n Binding ──── 1 DeviceIdentity(CONTROLLER)
        │
        └── 1 ──── n ConnectionRecord
Session ──── 引用 Binding + RelayNode
```

## 持久化

- 长辈端：DataStore（deviceId、displayName、绑定白名单、连接记录、会话偏好）。
- 控制端：DataStore（deviceId、已绑定的长辈端列表：显示名 + 隧道 IP + 端口）。
- **不落盘**：屏幕画面、控制事件内容。
