import assert from "node:assert/strict";
import { test } from "node:test";

import {
  VideoStreamParser,
  streamHeader,
  type StreamEvent,
} from "../src/lib/parse-stream.ts";

/** 12 字节包头 + payload。 */
function packet(flags: number, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + payload.length);
  out[0] = flags;
  new DataView(out.buffer).setUint32(8, payload.length);
  out.set(payload, 12);
  return out;
}

/** 会话包：宽高在包头里。 */
function sessionPacket(width: number, height: number): Uint8Array {
  const out = new Uint8Array(12);
  out[0] = 0x80;
  const view = new DataView(out.buffer);
  view.setUint32(4, width);
  view.setUint32(8, height);
  return out;
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

const CONFIG = new Uint8Array([0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e]);
const FRAME = new Uint8Array([0, 0, 0, 1, 0x65, 0x88, 0x84]);
const P_FRAME = new Uint8Array([0, 0, 0, 1, 0x41, 0x9a, 0x02]);

function fullStream(): Uint8Array {
  return concat(
    streamHeader("h264", "elder-phone"),
    sessionPacket(1080, 2400),
    packet(0x40, CONFIG),
    packet(0x20, FRAME),
    packet(0x00, P_FRAME),
  );
}

test("解析会话、配置与关键帧的完整流", () => {
  const parser = new VideoStreamParser();
  const events = parser.push(fullStream());

  assert.deepEqual(
    events.map((event) => event.kind),
    ["header", "session", "config", "frame", "frame"],
  );
  assert.deepEqual(events[0], { kind: "header", codec: "h264" });
  assert.deepEqual(events[1], { kind: "session", width: 1080, height: 2400 });
  assert.deepEqual((events[2] as { data: Uint8Array }).data, CONFIG);
  assert.equal((events[3] as { keyframe: boolean }).keyframe, true);
  assert.equal((events[4] as { keyframe: boolean }).keyframe, false);
});

test("逐字节到达时结果完全一致（半包不会丢）", () => {
  const whole = new VideoStreamParser().push(fullStream());
  const parser = new VideoStreamParser();
  const drip: StreamEvent[] = [];
  for (const byte of fullStream()) {
    drip.push(...parser.push(new Uint8Array([byte])));
  }
  assert.deepEqual(drip, whole);
});

test("payload 跨分片时等到齐了才产出", () => {
  const parser = new VideoStreamParser();
  const stream = concat(streamHeader(), packet(0x20, FRAME));
  const cut = 68 + 12 + 2; // 包头读完了，payload 只到一半
  assert.deepEqual(parser.push(stream.subarray(0, cut)), [
    { kind: "header", codec: "h264" },
  ]);
  const rest = parser.push(stream.subarray(cut));
  assert.equal(rest.length, 1);
  assert.deepEqual((rest[0] as { data: Uint8Array }).data, FRAME);
});

test("未知编码只报一次 header，不再解释后续字节", () => {
  const parser = new VideoStreamParser();
  const events = parser.push(concat(streamHeader("h265"), sessionPacket(720, 1280)));
  assert.deepEqual(events, [{ kind: "header", codec: "h265" }]);
  // 之后即使再喂数据也不会产出乱事件
  assert.deepEqual(parser.push(new Uint8Array(100)), []);
});

test("reset 后可以重新解析一段新流（重连）", () => {
  const parser = new VideoStreamParser();
  parser.push(fullStream());
  parser.reset();
  const events = parser.push(fullStream());
  assert.equal(events[0].kind, "header");
  assert.equal(events.length, 5);
});

test("大尺寸用 32 位读取（宽高超过 32767 不溢出）", () => {
  const parser = new VideoStreamParser();
  const events = parser.push(concat(streamHeader(), sessionPacket(40000, 90000)));
  assert.deepEqual(events[1], { kind: "session", width: 40000, height: 90000 });
});
