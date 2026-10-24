#!/usr/bin/env node
/**
 * 网页控制端的端到端测试：真实中继 + 脚本化长辈端 + Node 内置 WebSocket。
 *
 *   node tools/e2e-web.mjs                 # 用 relay/target/release/ac-relay
 *   AC_RELAY_BIN=/path/to/ac-relay node tools/e2e-web.mjs
 *
 * 验证的是"浏览器这一侧"最容易出错的几件事：
 *  1. WebSocket 首帧 `video <哈希>` / `control <哈希>` 能和对端配上对；
 *  2. 长辈端发出的字节（设备头 / 会话 / 配置 / 帧）原样到达浏览器；
 *  3. 浏览器发出的控制消息（WebSocket 二进制帧）原样到达长辈端；
 *  4. 两条流互不串线。
 *
 * 不依赖任何 npm 包：Node 22 自带 WebSocket 客户端。
 */

import { spawn } from "node:child_process";
import { randomBytes, createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { createServer, connect } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

function relayBinary() {
  if (process.env.AC_RELAY_BIN) return process.env.AC_RELAY_BIN;
  const exe = process.platform === "win32" ? ".exe" : "";
  const candidates = [
    join(ROOT, "relay", "target", "release", `ac-relay${exe}`),
    join(ROOT, "relay", "target", "debug", `ac-relay${exe}`),
    join("E:/DevCache/cargo-build/ac-relay", "release", `ac-relay${exe}`),
    join("E:/DevCache/cargo-build/ac-relay", "debug", `ac-relay${exe}`),
  ];
  const found = candidates.find((path) => existsSync(path));
  if (!found) {
    console.error(
      "找不到 ac-relay 二进制。先构建：\n  cargo build --release --manifest-path relay/Cargo.toml",
    );
    process.exit(2);
  }
  return found;
}

async function freePort() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

function wait(milliseconds, message) {
  return new Promise((_, reject) =>
    setTimeout(() => reject(new Error(`超时：${message}`)), milliseconds),
  );
}

/** 带缓冲的行/字节读取器（TCP 上没有消息边界，必须自己切）。 */
class Reader {
  constructor(socket) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.waiters = [];
    socket.on("data", (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.pump();
    });
    socket.on("error", (error) => this.fail(error));
    socket.on("close", () => this.fail(new Error("连接已关闭")));
  }

  fail(error) {
    for (const waiter of this.waiters.splice(0)) waiter.reject(error);
  }

  pump() {
    while (this.waiters.length) {
      const waiter = this.waiters[0];
      if (waiter.kind === "line") {
        const index = this.buffer.indexOf(0x0a);
        if (index < 0) return;
        const line = this.buffer.subarray(0, index).toString("utf8").trim();
        this.buffer = this.buffer.subarray(index + 1);
        this.waiters.shift();
        waiter.resolve(line);
      } else {
        if (this.buffer.length < waiter.size) return;
        const data = this.buffer.subarray(0, waiter.size);
        this.buffer = this.buffer.subarray(waiter.size);
        this.waiters.shift();
        waiter.resolve(Buffer.from(data));
      }
    }
  }

  line() {
    return new Promise((resolve, reject) => {
      this.waiters.push({ kind: "line", resolve, reject });
      this.pump();
    });
  }

  bytes(size) {
    return new Promise((resolve, reject) => {
      this.waiters.push({ kind: "bytes", size, resolve, reject });
      this.pump();
    });
  }
}

async function tcp(port) {
  const socket = connect({ host: "127.0.0.1", port });
  await new Promise((resolve, reject) => {
    socket.once("connect", resolve);
    socket.once("error", reject);
  });
  socket.setNoDelay(true);
  return socket;
}

async function startRelay(port) {
  const binary = relayBinary();
  console.log(`· 中继二进制：${binary}`);
  const child = spawn(binary, ["--listen", `127.0.0.1:${port}`], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs = [];
  child.stdout.on("data", (chunk) => logs.push(chunk.toString()));
  child.stderr.on("data", (chunk) => logs.push(chunk.toString()));
  child.on("exit", (code) => {
    if (code && code !== 0) logs.push(`relay 退出码 ${code}`);
  });

  // 等端口可用（最多 5 秒）
  const deadline = Date.now() + 5000;
  for (;;) {
    try {
      const probe = await tcp(port);
      probe.destroy();
      return { child, logs };
    } catch (error) {
      if (Date.now() > deadline) {
        child.kill();
        throw new Error(`中继没有启动：${error.message}\n${logs.join("")}`);
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
}

/** 打开一条 WebSocket 流并完成 `角色 哈希` 握手。 */
async function openRole(wsUrl, role, hash) {
  const socket = new WebSocket(wsUrl);
  socket.binaryType = "arraybuffer";
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", () => reject(new Error("WebSocket 连接失败")), {
      once: true,
    });
  });
  socket.send(`${role} ${hash}`);
  return socket;
}

/** 等 WebSocket 收到一条文本消息（OK / ERR / TIMEOUT）。 */
function nextText(socket, timeoutMs = 5000) {
  return Promise.race([
    new Promise((resolve) => {
      const onMessage = (event) => {
        if (typeof event.data !== "string") return;
        socket.removeEventListener("message", onMessage);
        resolve(event.data);
      };
      socket.addEventListener("message", onMessage);
    }),
    wait(timeoutMs, "等 WebSocket 文本回复"),
  ]);
}

/** 收集 WebSocket 二进制消息，直到凑满 size 字节。 */
function collectBinary(socket, size, timeoutMs = 5000) {
  return Promise.race([
    new Promise((resolve) => {
      const chunks = [];
      let total = 0;
      const onMessage = (event) => {
        if (typeof event.data === "string") return;
        const chunk = Buffer.from(event.data);
        chunks.push(chunk);
        total += chunk.length;
        if (total >= size) {
          socket.removeEventListener("message", onMessage);
          resolve(Buffer.concat(chunks));
        }
      };
      socket.addEventListener("message", onMessage);
    }),
    wait(timeoutMs, `等 ${size} 字节视频数据`),
  ]);
}

function deviceHeader(name = "e2e-elder") {
  const header = Buffer.alloc(68);
  header.write(name, 0, "utf8");
  header.write("h264", 64, "utf8");
  return header;
}

function sessionPacket(width, height) {
  const packet = Buffer.alloc(12);
  packet[0] = 0x80;
  packet.writeUInt32BE(width, 4);
  packet.writeUInt32BE(height, 8);
  return packet;
}

function mediaPacket(flags, payload) {
  const packet = Buffer.alloc(12 + payload.length);
  packet[0] = flags;
  packet.writeUInt32BE(payload.length, 8);
  payload.copy(packet, 12);
  return packet;
}

async function main() {
  const port = await freePort();
  const hash = createHash("sha256").update(randomBytes(16)).digest("hex");
  const { child, logs } = await startRelay(port);
  const sockets = [];

  const cleanup = () => {
    for (const socket of sockets) {
      try {
        socket.close?.();
        socket.destroy?.();
      } catch {
        // 已经关了
      }
    }
    child.kill();
  };

  try {
    // ── 长辈端：登记信令 ──────────────────────────────
    const signaling = await tcp(port);
    sockets.push(signaling);
    const signalingReader = new Reader(signaling);
    signaling.write(`AGENT ${hash} signaling\n`);
    assert.equal(await signalingReader.line(), "OK", "长辈端信令应被接受");
    console.log("✓ 长辈端信令已登记");

    // ── 视频：浏览器接入 ─────────────────────────────
    const wsUrl = `ws://127.0.0.1:${port}/ws`;
    const video = await openRole(wsUrl, "video", hash);
    sockets.push(video);
    assert.equal(await signalingReader.line(), "OPEN video");
    const videoData = await tcp(port);
    sockets.push(videoData);
    const videoReader = new Reader(videoData);
    videoData.write(`DATA ${hash} video\n`);
    assert.equal(await videoReader.line(), "OK");
    assert.equal(await nextText(video), "OK", "浏览器应收到视频配对的 OK");
    console.log("✓ 视频流已配对");

    // ── 控制：浏览器接入（同一时间第二条流）────────
    const control = await openRole(wsUrl, "control", hash);
    sockets.push(control);
    assert.equal(await signalingReader.line(), "OPEN control");
    const controlData = await tcp(port);
    sockets.push(controlData);
    const controlReader = new Reader(controlData);
    controlData.write(`DATA ${hash} control\n`);
    assert.equal(await controlReader.line(), "OK");
    assert.equal(await nextText(control), "OK", "浏览器应收到控制配对的 OK");
    console.log("✓ 控制流已配对");

    // ── 长辈端 → 浏览器：字节原样到达 ───────────────
    const config = Buffer.from([0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e, 0, 0, 0, 1, 0x68, 0xce]);
    const keyframe = Buffer.from([0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x21]);
    const stream = Buffer.concat([
      deviceHeader(),
      sessionPacket(1080, 2400),
      mediaPacket(0x40, config),
      mediaPacket(0x20, keyframe),
    ]);
    // 先挂上收集再写：WebSocket 的 message 事件没有缓冲，晚挂就丢
    const collecting = collectBinary(video, stream.length);
    // 分两次写：验证跨 TCP 分片时浏览器侧仍能拼回完整字节
    videoData.write(stream.subarray(0, 30));
    await new Promise((resolve) => setTimeout(resolve, 50));
    videoData.write(stream.subarray(30));

    const received = await collecting;
    assert.deepEqual(received, stream, "视频字节应逐字节一致");
    console.log(`✓ 视频 ${stream.length} 字节原样到达浏览器`);

    // ── 浏览器 → 长辈端：控制消息原样到达 ───────────
    const touch = Buffer.alloc(32);
    touch[0] = 2; // INJECT_TOUCH_EVENT
    touch[1] = 0; // DOWN
    touch.writeInt32BE(540, 10);
    touch.writeInt32BE(1200, 14);
    touch.writeUInt16BE(1080, 18);
    touch.writeUInt16BE(2400, 20);
    touch.writeUInt16BE(0xffff, 22);
    control.send(new Uint8Array(touch));

    const arrived = await controlReader.bytes(touch.length);
    assert.deepEqual(arrived, touch, "控制消息应逐字节一致");
    console.log(`✓ 控制 ${touch.length} 字节原样到达长辈端`);

    // ── 两条流互不串线 ─────────────────────────────
    const moreControl = Buffer.from([0x11]); // RESET_VIDEO
    control.send(new Uint8Array(moreControl));
    assert.deepEqual(await controlReader.bytes(1), moreControl);
    console.log("✓ 视频/控制两条流互不串线");

    console.log("\nE2E 通过：中继 + WebSocket + 脚本化长辈端");
  } catch (error) {
    console.error("\nE2E 失败：", error.message);
    console.error("--- 中继日志 ---\n" + logs.join(""));
    process.exitCode = 1;
  } finally {
    cleanup();
  }
}

await main();
