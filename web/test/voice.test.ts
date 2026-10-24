import assert from "node:assert/strict";
import { test } from "node:test";

import { float32ToPcm16, pcm16ToFloat32 } from "../src/lib/pcm.ts";

test("16 位小端 PCM 解码为浮点", () => {
  // -32768 / 32767 / 0
  const bytes = Uint8Array.from([0x00, 0x80, 0xff, 0x7f, 0x00, 0x00]);
  const samples = pcm16ToFloat32(bytes);
  assert.equal(samples.length, 3);
  assert.equal(samples[0], -1);
  assert.ok(Math.abs(samples[1] - 32767 / 32768) < 1e-6);
  assert.equal(samples[2], 0);
});

test("浮点编码回 16 位小端，且会夹取越界值", () => {
  const bytes = float32ToPcm16(Float32Array.from([-1, 1, 0, 2, -2]));
  assert.deepEqual(
    Array.from(bytes),
    [0x00, 0x80, 0xff, 0x7f, 0x00, 0x00, 0xff, 0x7f, 0x00, 0x80],
  );
});

test("一帧 20 ms 是 320 样本 / 640 字节", () => {
  const samples = new Float32Array(320).fill(0.25);
  assert.equal(float32ToPcm16(samples).length, 640);
  assert.equal(pcm16ToFloat32(float32ToPcm16(samples)).length, 320);
});
