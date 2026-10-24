/**
 * scrcpy 协议的纯函数部分：控制消息的字节布局、Annex-B 解析。
 *
 * 与 Android 端 `protocol/ControlMessageWriter.kt` 保持逐字节一致；
 * 布局来自 scrcpy 的 `control_msg.c`，改这里必须同时改那边。
 */

export const ACTION_DOWN = 0;
export const ACTION_UP = 1;
export const ACTION_MOVE = 2;

export const KEY_ACTION_DOWN = 0;
export const KEY_ACTION_UP = 1;

/** 通用手指指针 id（scrcpy 里是 -2）。用字符串构造，避免 BigInt 字面量的 target 限制。 */
const POINTER_ID_GENERIC_FINGER = BigInt("18446744073709551614");

export const KEYCODES = {
  back: 4,
  home: 3,
  recents: 187,
  volumeDown: 25,
  volumeUp: 24,
  wake: 224,
} as const;

export type KeyName = keyof typeof KEYCODES;

/** 触摸事件：32 字节，坐标是视频帧尺寸下的坐标。 */
export function touchMessage(
  action: number,
  x: number,
  y: number,
  width: number,
  height: number,
): ArrayBuffer {
  const buffer = new ArrayBuffer(32);
  const view = new DataView(buffer);
  view.setUint8(0, 2); // INJECT_TOUCH_EVENT
  view.setUint8(1, action);
  view.setBigUint64(2, POINTER_ID_GENERIC_FINGER, false);
  view.setInt32(10, x, false);
  view.setInt32(14, y, false);
  view.setUint16(18, width, false);
  view.setUint16(20, height, false);
  view.setUint16(22, 0xffff, false); // pressure = 1.0
  view.setInt32(24, 1, false); // action button: primary
  view.setInt32(28, 1, false); // buttons: primary
  return buffer;
}

/** 按键事件：14 字节。 */
export function keycodeMessage(action: number, keycode: number): ArrayBuffer {
  const buffer = new ArrayBuffer(14);
  const view = new DataView(buffer);
  view.setUint8(0, 0); // INJECT_KEYCODE
  view.setUint8(1, action);
  view.setInt32(2, keycode, false);
  view.setInt32(6, 0, false); // repeat
  view.setInt32(10, 0, false); // meta state
  return buffer;
}

/**
 * 文本输入：1 字节类型 + 4 字节长度（大端）+ UTF-8 字节，**不带**结尾 NUL。
 * 与 `protocol/ControlMessageWriter.injectText` 逐字节一致。
 */
export function injectTextMessage(text: string): Uint8Array {
  const bytes = new TextEncoder().encode(text);
  const out = new Uint8Array(5 + bytes.length);
  out[0] = 1; // INJECT_TEXT
  new DataView(out.buffer).setUint32(1, bytes.length, false);
  out.set(bytes, 5);
  return out;
}

/** 请求新的关键帧（后连入的客户端需要它才能出画）。 */
export function resetVideoMessage(): Uint8Array {
  return new Uint8Array([17]);
}

/** 把 Annex-B 切成一个个 NAL（不含起始码）。 */
export function splitAnnexB(buffer: Uint8Array): Uint8Array[] {
  const nals: Uint8Array[] = [];
  let start = -1;
  let i = 0;
  while (i + 3 <= buffer.length) {
    const four =
      i + 4 <= buffer.length &&
      buffer[i] === 0 &&
      buffer[i + 1] === 0 &&
      buffer[i + 2] === 0 &&
      buffer[i + 3] === 1;
    const three = buffer[i] === 0 && buffer[i + 1] === 0 && buffer[i + 2] === 1;
    if (four) {
      if (start >= 0) nals.push(buffer.subarray(start, i));
      i += 4;
      start = i;
    } else if (three) {
      if (start >= 0) nals.push(buffer.subarray(start, i));
      i += 3;
      start = i;
    } else {
      i++;
    }
  }
  if (start >= 0 && start < buffer.length) nals.push(buffer.subarray(start));
  return nals;
}

export function splitSpsPps(annexB: Uint8Array): { sps?: Uint8Array; pps?: Uint8Array } {
  let sps: Uint8Array | undefined;
  let pps: Uint8Array | undefined;
  for (const nal of splitAnnexB(annexB)) {
    if (!nal.length) continue;
    const type = nal[0] & 0x1f;
    if (type === 7) sps = nal;
    else if (type === 8) pps = nal;
  }
  return { sps, pps };
}

/** Annex-B → AVCC（每个 NAL 前加 4 字节长度），WebCodecs 的 avc 模式需要它。 */
export function annexBToAvcc(buffer: Uint8Array): Uint8Array {
  const nals = splitAnnexB(buffer);
  let total = 0;
  for (const nal of nals) total += 4 + nal.length;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const nal of nals) {
    const length = nal.length;
    out[offset++] = (length >>> 24) & 0xff;
    out[offset++] = (length >>> 16) & 0xff;
    out[offset++] = (length >>> 8) & 0xff;
    out[offset++] = length & 0xff;
    out.set(nal, offset);
    offset += length;
  }
  return out;
}
