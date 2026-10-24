"use client";

/**
 * 浏览器端的双向语音：`audio <哈希>` 一条 WebSocket，双向传定长 PCM 帧。
 *
 * 与 Android 端 `audio/VoiceSession.kt` 的线上格式完全一致：
 * 16 kHz / 单声道 / 16 位小端，20 ms 一帧（320 样本 = 640 字节）。
 * 解码播放**不假设**每帧一条 WebSocket 消息：中继按读写块转发，可能把几帧拼在一起。
 *
 * 采集与播放都用 AudioWorklet（不碰已废弃的 ScriptProcessor）：
 * - 采集：把浏览器采样率线性插值到 16 kHz，攒满 320 样本发一帧；
 * - 播放：有界队列（最多 8 帧），网络抖动时丢旧帧，避免越积越延迟。
 */

import { pcm16ToFloat32 } from "./pcm";

export type VoiceState = "closed" | "connecting" | "live" | "error";

export interface VoiceOptions {
  /** 形如 wss://relay.example.com（不带路径）。 */
  relayUrl: string;
  onState: (state: VoiceState, detail?: string) => void;
}

const SAMPLE_RATE = 16_000;
const FRAME_SAMPLES = 320;
const PLAYER_QUEUE_LIMIT = 8;

const CAPTURE_WORKLET = `
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.pos = 0;
    this.tail = new Float32Array(0);
    this.frame = new Int16Array(320);
    this.index = 0;
  }
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;
    const data = new Float32Array(this.tail.length + channel.length);
    data.set(this.tail);
    data.set(channel, this.tail.length);

    let pos = this.pos;
    while (pos < data.length - 1) {
      const index = Math.floor(pos);
      const frac = pos - index;
      const sample = data[index] * (1 - frac) + data[index + 1] * frac;
      const value = Math.max(-32768, Math.min(32767, Math.round(sample * 32767)));
      this.frame[this.index++] = value;
      if (this.index === 320) {
        this.port.postMessage(this.frame.slice());
        this.index = 0;
      }
      pos += this.ratio;
    }
    const consumed = Math.floor(pos);
    this.tail = data.slice(consumed);
    this.pos = pos - consumed;
    return true;
  }
}
registerProcessor("capture-processor", CaptureProcessor);
`;

const PLAYER_WORKLET = `
class PlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.offset = 0;
    this.port.onmessage = (event) => {
      this.queue.push(event.data);
      // 网络抖动时丢最旧的帧，宁可断一下也不要越积越延迟
      while (this.queue.length > 8) this.queue.shift();
    };
  }
  process(inputs, outputs) {
    const output = outputs[0] && outputs[0][0];
    if (!output) return true;
    let written = 0;
    while (written < output.length) {
      const current = this.queue[0];
      if (!current) {
        output.fill(0, written);
        return true;
      }
      const take = Math.min(output.length - written, current.length - this.offset);
      output.set(current.subarray(this.offset, this.offset + take), written);
      written += take;
      this.offset += take;
      if (this.offset >= current.length) {
        this.queue.shift();
        this.offset = 0;
      }
    }
    return true;
  }
}
registerProcessor("player-processor", PlayerProcessor);
`;

export class WebVoice {
  private socket?: WebSocket;
  private context?: AudioContext;
  private capture?: AudioWorkletNode;
  private player?: AudioWorkletNode;
  private stream?: MediaStream;
  private stopped = true;
  private lastState: VoiceState = "closed";

  constructor(private readonly options: VoiceOptions) {}

  get active(): boolean {
    return !this.stopped;
  }

  async start(hash: string) {
    if (!this.stopped) return;
    this.stopped = false;
    this.emit("connecting");

    // 必须在点击手势里创建并恢复 AudioContext：否则自动播放策略会让它一直 suspended
    try {
      this.context = new AudioContext({ sampleRate: SAMPLE_RATE });
    } catch {
      // 个别浏览器不接受指定采样率，退回默认值（采集 worklet 会插值到 16k）
      this.context = new AudioContext();
    }
    await this.context.resume();

    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
      });
    } catch (error) {
      this.stopped = true;
      await this.context?.close().catch(() => undefined);
      this.context = undefined;
      this.emit(
        "error",
        error instanceof DOMException && error.name === "NotAllowedError"
          ? "需要麦克风权限才能语音"
          : "无法打开麦克风",
      );
      return;
    }
    if (this.stopped) {
      this.releaseStream();
      return;
    }

    const socket = new WebSocket(this.endpoint());
    socket.binaryType = "arraybuffer";
    this.socket = socket;

    socket.onopen = () => socket.send(`audio ${hash}`);
    socket.onmessage = (event) => {
      if (typeof event.data === "string") {
        if (event.data === "OK") {
          void this.startAudio();
          this.emit("live");
        } else {
          this.emit("error", event.data === "TIMEOUT" ? "对方没有及时响应" : "对方不在线");
          this.stop();
        }
        return;
      }
      if (!this.stopped && this.player) {
        this.player.port.postMessage(pcm16ToFloat32(new Uint8Array(event.data)));
      }
    };
    socket.onclose = () => {
      if (!this.stopped) this.stop();
    };
  }

  stop() {
    if (this.stopped) return;
    this.stopped = true;
    try {
      this.socket?.close();
    } catch {
      // 已经关了
    }
    this.socket = undefined;
    this.capture?.disconnect();
    this.player?.disconnect();
    this.capture = undefined;
    this.player = undefined;
    this.releaseStream();
    void this.context?.close().catch(() => undefined);
    this.context = undefined;
    // 出错后保留错误文案，不要在收尾时又改回"已关闭"
    if (this.lastState !== "error") this.emit("closed");
  }

  private emit(state: VoiceState, detail?: string) {
    this.lastState = state;
    this.options.onState(state, detail);
  }

  private endpoint(): string {
    const base = this.options.relayUrl.trim().replace(/\/+$/, "");
    return base + "/ws";
  }

  private releaseStream() {
    for (const track of this.stream?.getTracks() ?? []) track.stop();
    this.stream = undefined;
  }

  private async startAudio() {
    const context = this.context;
    if (!this.socket || !this.stream || !context) return;
    try {
      // 请求 16 kHz：能直接给就用，给不了由采集 worklet 插值降采样
      await addWorklet(context, CAPTURE_WORKLET);
      await addWorklet(context, PLAYER_WORKLET);
      if (this.stopped) return;
      await context.resume();

      const source = context.createMediaStreamSource(this.stream);
      const capture = new AudioWorkletNode(context, "capture-processor");
      this.capture = capture;
      capture.port.onmessage = (event) => {
        const frame = event.data as Int16Array;
        if (this.stopped || this.socket?.readyState !== WebSocket.OPEN) return;
        const bytes = new Uint8Array(frame.buffer, frame.byteOffset, frame.byteLength);
        this.socket.send(bytes);
      };
      // 采集节点必须连到目的地才会被调度；音量设为 0，避免本地回放自己的声音
      const mute = context.createGain();
      mute.gain.value = 0;
      source.connect(capture).connect(mute).connect(context.destination);

      const player = new AudioWorkletNode(context, "player-processor");
      this.player = player;
      player.connect(context.destination);
    } catch {
      this.emit("error", "浏览器音频初始化失败");
      this.stop();
    }
  }
}

/** worklet 源码以 Blob URL 加载，省去额外静态资源与缓存策略。 */
async function addWorklet(context: AudioContext, source: string): Promise<void> {
  const url = URL.createObjectURL(new Blob([source], { type: "text/javascript" }));
  try {
    await context.audioWorklet.addModule(url);
  } finally {
    URL.revokeObjectURL(url);
  }
}
