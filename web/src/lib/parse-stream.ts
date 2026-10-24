/**
 * scrcpy 视频流的**增量分帧器**（纯函数，无 DOM / 无 WebCodecs 依赖）。
 *
 * 流的结构（与 Android 端一致）：
 *
 * ```
 * [64B 设备名][4B 编解码器]        ← 只在连接开头出现一次
 * [12B 包头][payload]              ← 重复
 * ```
 *
 * 包头第 0 字节的高 3 位是标志：
 * - `0x80` 会话包：payload 为空，宽高在包头里（32 位，偏移 4 / 8）
 * - `0x40` 配置包：payload 是 SPS/PPS
 * - `0x20` 关键帧
 *
 * 其余 5 位 + 后续 11 字节是 PTS（微秒）。这里不做解码，只把字节切成事件，
 * 方便单测覆盖"分片到达 / 半包 / 配置与关键帧顺序"这些容易错的地方。
 */

export type StreamEvent =
  | { kind: "header"; codec: string }
  | { kind: "session"; width: number; height: number }
  | { kind: "config"; data: Uint8Array }
  | { kind: "frame"; data: Uint8Array; keyframe: boolean };

const DEVICE_NAME_LENGTH = 64;
const CODEC_LENGTH = 4;
const STREAM_HEADER = DEVICE_NAME_LENGTH + CODEC_LENGTH; // 68
const PACKET_HEADER = 12;

const FLAG_SESSION = 0x80;
const FLAG_CONFIG = 0x40;
const FLAG_KEYFRAME = 0x20;

export class VideoStreamParser {
  private buffer = new Uint8Array(0);
  private headerDone = false;
  /** 遇到不认识的编码后停止解析，直到 reset（重新建会话）。 */
  private broken = false;

  reset() {
    this.buffer = new Uint8Array(0);
    this.headerDone = false;
    this.broken = false;
  }

  /** 喂一段任意长度的字节，返回已经完整解析出的事件（可能为空）。 */
  push(chunk: Uint8Array): StreamEvent[] {
    if (this.broken) return [];
    if (chunk.length) {
      const merged = new Uint8Array(this.buffer.length + chunk.length);
      merged.set(this.buffer);
      merged.set(chunk, this.buffer.length);
      this.buffer = merged;
    }

    const events: StreamEvent[] = [];
    for (;;) {
      if (!this.headerDone) {
        if (this.buffer.length < STREAM_HEADER) break;
        const codec = String.fromCharCode(
          this.buffer[DEVICE_NAME_LENGTH],
          this.buffer[DEVICE_NAME_LENGTH + 1],
          this.buffer[DEVICE_NAME_LENGTH + 2],
          this.buffer[DEVICE_NAME_LENGTH + 3],
        );
        events.push({ kind: "header", codec });
        this.buffer = this.buffer.slice(STREAM_HEADER);
        this.headerDone = true;
        if (codec !== "h264") {
          // 不认识的编码：后面的字节无法解释，等调用方重建会话
          this.buffer = new Uint8Array(0);
          this.broken = true;
          break;
        }
        continue;
      }

      if (this.buffer.length < PACKET_HEADER) break;

      const flags = this.buffer[0];
      if (flags & FLAG_SESSION) {
        events.push({
          kind: "session",
          width: readU32(this.buffer, 4),
          height: readU32(this.buffer, 8),
        });
        this.buffer = this.buffer.slice(PACKET_HEADER);
        continue;
      }

      const size = readU32(this.buffer, 8);
      if (this.buffer.length < PACKET_HEADER + size) break;
      const data = this.buffer.slice(PACKET_HEADER, PACKET_HEADER + size);
      this.buffer = this.buffer.slice(PACKET_HEADER + size);

      if (flags & FLAG_CONFIG) {
        events.push({ kind: "config", data });
      } else {
        events.push({ kind: "frame", data, keyframe: (flags & FLAG_KEYFRAME) !== 0 });
      }
    }
    return events;
  }
}

export function readU32(buffer: Uint8Array, offset: number): number {
  return (
    ((buffer[offset] << 24) |
      (buffer[offset + 1] << 16) |
      (buffer[offset + 2] << 8) |
      buffer[offset + 3]) >>>
    0
  );
}

/** 测试与调试用：把一段流拼成规范的 68 字节头。 */
export function streamHeader(codec = "h264", deviceName = "test-device"): Uint8Array {
  const out = new Uint8Array(STREAM_HEADER);
  const name = new TextEncoder().encode(deviceName);
  out.set(name.subarray(0, DEVICE_NAME_LENGTH), 0);
  out.set(new TextEncoder().encode(codec.padEnd(CODEC_LENGTH, "\0")), DEVICE_NAME_LENGTH);
  return out;
}
