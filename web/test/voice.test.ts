import assert from "node:assert/strict";
import { test } from "node:test";

import { feedPcm16, float32ToPcm16, pcm16ToFloat32 } from "../src/lib/pcm.ts";

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

test("跨消息分片：只解码完整样本，奇数字节留到下一块", () => {
  // 第一块结尾落单 0xff，第二块补 0x7f 才组成一个样本
  const first = feedPcm16(new Uint8Array(0), Uint8Array.from([0x00, 0x80, 0xff]));
  assert.equal(first.samples.length, 1);
  assert.equal(first.samples[0], -1);
  assert.deepEqual(Array.from(first.pending), [0xff]);

  const second = feedPcm16(first.pending, Uint8Array.from([0x7f]));
  assert.equal(second.samples.length, 1);
  assert.ok(Math.abs(second.samples[0] - 32767 / 32768) < 1e-6);
  assert.equal(second.pending.length, 0);
});

test("一次收到多帧时全部解码（中继可能拼包）", () => {
  const bytes = new Uint8Array(6);
  bytes.set([0x00, 0x80], 0);
  bytes.set([0xff, 0x7f], 2);
  bytes.set([0x00, 0x00], 4);
  const { samples, pending } = feedPcm16(new Uint8Array(0), bytes);
  assert.equal(samples.length, 3);
  assert.deepEqual(Array.from(samples.subarray(0, 2)), [-1, 32767 / 32768]);
  assert.equal(pending.length, 0);
});
