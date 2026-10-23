"use client";

import {
  annexBToAvcc,
  keycodeMessage,
  resetVideoMessage,
  splitSpsPps,
  touchMessage,
} from "./scrcpy";

export type SessionState = "idle" | "connecting" | "live" | "error";

export interface RemoteSessionOptions {
  /** 形如 wss://relay.example.com（不带路径）。 */
  relayUrl: string;
  canvas: HTMLCanvasElement;
  onState: (state: SessionState, detail?: string) => void;
  onFps: (fps: number) => void;
}

/**
 * 浏览器端的会话：两条 WebSocket（视频/控制）+ WebCodecs 解码。
 *
 * 与 Android 控制端是同一套协议，所以服务端不需要为浏览器做任何适配——
 * 中继只是把数据流从 TCP 换成 WebSocket 帧。
 */
export class RemoteSession {
  private videoSocket?: WebSocket;
  private controlSocket?: WebSocket;
  private decoder?: VideoDecoder;
  private configured = false;
  private configPacket?: Uint8Array;
  private headerDone = false;
  private buffer = new Uint8Array(0);
  private sawKeyFrame = false;
  private timestamp = 0;
  private videoWidth = 0;
  private videoHeight = 0;
  private frames = 0;
  private fpsTimer?: number;
  private hash = "";
  private stopped = false;
  private liveAnnounced = false;
  private readonly context: CanvasRenderingContext2D | null;

  constructor(private readonly options: RemoteSessionOptions) {
    this.context = options.canvas.getContext("2d", { alpha: false });
  }

  async connect(token: string) {
    this.stopped = false;
    this.liveAnnounced = false;
    this.hash = await sha256Hex(token);
    this.options.onState("connecting");
    this.resetStream();
    this.startFpsTimer();
    this.openVideo();
    this.openControl();
  }

  stop() {
    this.stopped = true;
    this.videoSocket?.close();
    this.controlSocket?.close();
    this.videoSocket = undefined;
    this.controlSocket = undefined;
    this.closeDecoder();
    if (this.fpsTimer !== undefined) {
      window.clearInterval(this.fpsTimer);
      this.fpsTimer = undefined;
    }
    this.options.onState("idle");
  }

  sendKey(keycode: number) {
    this.sendControl(keycodeMessage(0, keycode));
    this.sendControl(keycodeMessage(1, keycode));
  }

  sendTouch(action: number, x: number, y: number) {
    if (!this.videoWidth || !this.videoHeight) return;
    this.sendControl(touchMessage(action, x, y, this.videoWidth, this.videoHeight));
  }

  // ── 连接管理 ────────────────────────────────────────────

  private endpoint(): string {
    const base = this.options.relayUrl.trim().replace(/\/+$/, "");
    return base + "/ws";
  }

  private openVideo() {
    if (this.stopped) return;
    const socket = new WebSocket(this.endpoint());
    socket.binaryType = "arraybuffer";
    this.videoSocket = socket;

    socket.onopen = () => {
      // 重连后要等下一帧再宣布"已连上"
      this.liveAnnounced = false;
      socket.send(`video ${this.hash}`);
    };
    socket.onmessage = (event) => {
      if (typeof event.data === "string") {
        if (event.data === "ERR") {
          this.options.onState("error", "被协助的手机不在线（先在手机上开启协助）");
        } else if (event.data === "TIMEOUT") {
          this.options.onState("error", "被协助的手机没有及时响应");
        }
        return;
      }
      this.consume(new Uint8Array(event.data));
    };
    socket.onclose = () => {
      if (this.stopped) return;
      this.options.onState("connecting", "视频断开，正在重连…");
      window.setTimeout(() => this.openVideo(), 1000);
    };
  }

  private openControl() {
    if (this.stopped) return;
    const socket = new WebSocket(this.endpoint());
    this.controlSocket = socket;

    socket.onopen = () => {
      socket.send(`control ${this.hash}`);
      // 后连入的客户端需要一个关键帧
      window.setTimeout(() => this.sendControl(resetVideoMessage()), 200);
    };
    socket.onmessage = (event) => {
      if (typeof event.data === "string" && event.data === "ERR") {
        this.options.onState("error", "被协助的手机不在线");
      }
    };
    socket.onclose = () => {
      if (!this.stopped) window.setTimeout(() => this.openControl(), 500);
    };
  }

  private sendControl(data: ArrayBuffer | Uint8Array) {
    if (this.controlSocket?.readyState === WebSocket.OPEN) {
      this.controlSocket.send(data);
    }
  }

  // ── 视频流 ──────────────────────────────────────────────

  private resetStream() {
    this.headerDone = false;
    this.buffer = new Uint8Array(0);
    this.sawKeyFrame = false;
    this.configured = false;
    this.configPacket = undefined;
    this.timestamp = 0;
    this.closeDecoder();
  }

  private closeDecoder() {
    try {
      this.decoder?.close();
    } catch {
      // 已经关闭
    }
    this.decoder = undefined;
    this.configured = false;
  }

  private consume(chunk: Uint8Array) {
    const merged = new Uint8Array(this.buffer.length + chunk.length);
    merged.set(this.buffer);
    merged.set(chunk, this.buffer.length);
    this.buffer = merged;

    for (;;) {
      if (!this.headerDone) {
        if (this.buffer.length < 68) return;
        const codec = String.fromCharCode(
          this.buffer[64],
          this.buffer[65],
          this.buffer[66],
          this.buffer[67],
        );
        if (codec !== "h264") {
          this.options.onState("error", `不支持的编码：${codec}`);
          return;
        }
        this.buffer = this.buffer.slice(68);
        this.headerDone = true;
      }
      if (this.buffer.length < 12) return;

      const flags = this.buffer[0];
      if (flags & 0x80) {
        // 会话包：宽高是 32 位整数
        this.videoWidth = readU32(this.buffer, 4);
        this.videoHeight = readU32(this.buffer, 8);
        this.buffer = this.buffer.slice(12);
        this.configureDecoder();
        continue;
      }

      const size = readU32(this.buffer, 8);
      if (this.buffer.length < 12 + size) return;
      const payload = this.buffer.slice(12, 12 + size);
      this.buffer = this.buffer.slice(12 + size);

      if (flags & 0x40) {
        this.configPacket = payload;
        this.configureDecoder();
        continue;
      }
      this.decodeFrame(payload, (flags & 0x20) !== 0);
    }
  }

  private configureDecoder() {
    if (!this.configPacket || !this.videoWidth || !this.videoHeight) return;
    const { sps, pps } = splitSpsPps(this.configPacket);
    if (!sps || !pps || sps.length < 4) return;

    // AVCDecoderConfigurationRecord：description 里的 SPS/PPS 不带起始码
    const description = new Uint8Array(11 + sps.length + pps.length);
    let offset = 0;
    description[offset++] = 1;
    description[offset++] = sps[1];
    description[offset++] = sps[2];
    description[offset++] = sps[3];
    description[offset++] = 0xff;
    description[offset++] = 0xe1;
    description[offset++] = (sps.length >> 8) & 0xff;
    description[offset++] = sps.length & 0xff;
    description.set(sps, offset);
    offset += sps.length;
    description[offset++] = 1;
    description[offset++] = (pps.length >> 8) & 0xff;
    description[offset++] = pps.length & 0xff;
    description.set(pps, offset);

    const codec =
      "avc1." +
      [sps[1], sps[2], sps[3]].map((v) => v.toString(16).padStart(2, "0")).join("");

    this.closeDecoder();
    this.decoder = new VideoDecoder({
      output: (frame) => this.draw(frame),
      error: () => {
        // 解码器坏了就等下一个配置包/关键帧重建
        this.configured = false;
      },
    });
    this.decoder.configure({ codec, description, optimizeForLatency: true });
    this.configured = true;
    this.sawKeyFrame = false;
  }

  private decodeFrame(payload: Uint8Array, isKey: boolean) {
    if (!this.configured || !this.decoder) return;
    // 首个关键帧之前不喂 P 帧：解码器会占住缓冲却解不出画面
    if (!isKey && !this.sawKeyFrame) return;
    if (isKey) this.sawKeyFrame = true;
    // 落后时果断丢非关键帧：延迟优先于完整（远程操作"跟手"比"每一帧都看到"重要）
    if (!isKey && this.decoder.decodeQueueSize > 6) return;

    this.timestamp += 33_333;
    try {
      this.decoder.decode(
        new EncodedVideoChunk({
          type: isKey ? "key" : "delta",
          timestamp: this.timestamp,
          data: annexBToAvcc(payload),
        }),
      );
    } catch {
      this.configured = false;
    }
  }

  private draw(frame: VideoFrame) {
    const canvas = this.options.canvas;
    if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    this.context?.drawImage(frame, 0, 0);
    frame.close();
    this.frames++;
    if (!this.liveAnnounced) {
      // 第一帧上屏后才算真正"通了"；只通知一次，避免每帧触发 React 更新
      this.liveAnnounced = true;
      this.options.onState("live");
    }
  }

  private startFpsTimer() {
    if (this.fpsTimer !== undefined) window.clearInterval(this.fpsTimer);
    this.frames = 0;
    this.fpsTimer = window.setInterval(() => {
      this.options.onFps(this.frames);
      this.frames = 0;
    }, 1000);
  }
}

function readU32(buffer: Uint8Array, offset: number): number {
  return (
    ((buffer[offset] << 24) |
      (buffer[offset + 1] << 16) |
      (buffer[offset + 2] << 8) |
      buffer[offset + 3]) >>>
    0
  );
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
