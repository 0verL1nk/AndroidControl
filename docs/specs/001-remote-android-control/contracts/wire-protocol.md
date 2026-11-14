# Contract: scrcpy 5.0.1 线协议（我们实际用到的那部分）

**Date**: 2026-10-19
**来源**：**源码核对**（scrcpy v5.0.1 的 `Streamer.java` / `Options.java` /
`DesktopConnection.java` / `control_msg.h`），不是凭 develop.md 的示意图。
**踩坑记录**：develop.md 里会话包的 ASCII 示意图容易让人误以为宽高是 u16，
实际是 **32 位整数**。以源码为准。

## 1. 会话建立

- socket 名：`scrcpy_%08x`（scid 为 31 位随机数，**十六进制**）
- `scid` 参数按 radix 16 解析 —— 传十进制会 `NumberFormatException`
- 默认 reverse：客户端（我们）监听，引擎连入
- 连接顺序：**video → (audio) → control**；`audio=false` 时只有两条

## 2. 视频通道

### 2.1 流头（连接建立后立即发送一次）

| 长度 | 内容 |
|---|---|
| 64 字节 | 设备名（UTF-8，0 填充） |
| 4 字节 | 编码 id，`h264` = `0x68323634` |

### 2.2 会话包（12 字节，首字节最高位 = 1）

```
[0..3]  flags (i32, 大端)  最高位为 1 表示 session；最低位 = 客户端发起缩放
[4..7]  width  (i32, 大端)   ← 是 32 位，不是 u16
[8..11] height (i32, 大端)   ← 同上
```

> 旋转会触发新的会话包，客户端必须据此重建解码器尺寸。

### 2.3 媒体包（12 字节头 + 负载，首字节最高位 = 0）

```
[0]      标志位：bit7=0（媒体包）、bit6=config、bit5=key frame
[0..7]   ptsAndFlags (i64, 大端)：高 3 位是标志，其余是 PTS（微秒）
[8..11]  payload 长度 (i32/u32, 大端)
[12..]   H.264 负载
```

标志位常量（源码 `Streamer.java`）：
```java
PACKET_FLAG_SESSION   = 1L << 63;
PACKET_FLAG_CONFIG    = 1L << 62;
PACKET_FLAG_KEY_FRAME = 1L << 61;
```

**config 包必须缓存并回放给后接入的客户端**，否则解码器拿不到 SPS/PPS 解不出画面。

## 3. 控制通道（客户端 → 设备）

全部大端。用到的消息类型：

| 类型 | 值 | 布局 | 长度 |
|---|---|---|---|
| INJECT_KEYCODE | 0 | type u8, action u8, keycode u32, repeat u32, metastate u32 | 14 |
| INJECT_TOUCH_EVENT | 2 | type u8, action u8, pointerId u64, x i32, y i32, w u16, h u16, pressure u16(0xFFFF=1.0), actionButton i32, buttons i32 | 32 |
| INJECT_SCROLL_EVENT | 3 | type u8, x i32, y i32, w u16, h u16, hscroll i16, vscroll i16, buttons i32 | 21 |
| BACK_OR_SCREEN_ON | 4 | type u8, action u8 | 2 |

- action：`DOWN=0`、`UP=1`、`MOVE=2`
- 通用手指 pointer id = `-2`（即 `0xFFFFFFFFFFFFFFFE`）
- 坐标是**视频帧尺寸**下的坐标，不是原屏幕尺寸

**校验依据**：`app/tests/test_control_msg_serialize.c` 的官方测试向量，
已逐条复刻为 `protocol/src/test/.../ControlMessageWriterTest`（待补）。

## 4. 我们额外加的一层（不是 scrcpy 的）

| 项 | 约定 |
|---|---|
| 控制端口 | 27185（固定） |
| 视频端口 | 27186（固定） |
| 握手 | 控制：`HELLO <token>\n` → `OK\n`；视频：`AUTH <token>\n` → `OK\n` |
| 失败 | 回 `ERR\n` 并断开 |

固定端口是刻意的：两端在隧道里 IP 固定，这样"长辈端一打开，控制端就能连"，
不需要任何手工输入。
