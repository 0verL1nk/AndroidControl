import assert from "node:assert/strict";
import { test } from "node:test";

import { injectTextMessage } from "../src/lib/scrcpy.ts";

test("文本输入：1 字节类型 + 大端长度 + UTF-8 字节", () => {
  assert.deepEqual(Array.from(injectTextMessage("hi")), [1, 0, 0, 0, 2, 0x68, 0x69]);
  assert.deepEqual(Array.from(injectTextMessage("")), [1, 0, 0, 0, 0]);
});

test("长度按 UTF-8 字节数计算（中文 / emoji）", () => {
  const chinese = injectTextMessage("你好");
  assert.equal(chinese.length, 5 + 6);
  assert.deepEqual(Array.from(chinese.subarray(0, 5)), [1, 0, 0, 0, 6]);

  const emoji = injectTextMessage("😀");
  assert.equal(emoji.length, 5 + 4);
  assert.deepEqual(Array.from(emoji.subarray(0, 5)), [1, 0, 0, 0, 4]);
});
