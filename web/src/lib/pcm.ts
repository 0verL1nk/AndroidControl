/**
 * 16 位小端 PCM 与浮点样本互转（语音通道用，与 Android 端 `VoiceAudio` 一致）。
 *
 * 单独放在这里是为了能被 node:test 直接跑：`voice.ts` 里的参数属性等
 * 语法需要真正的 TypeScript 编译，而 Node 的类型剥离跑不了。
 */

/** 16 位小端 PCM → [-1,1] 浮点。 */
export function pcm16ToFloat32(bytes: Uint8Array): Float32Array {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const samples = new Float32Array(Math.floor(bytes.byteLength / 2));
  for (let index = 0; index < samples.length; index++) {
    samples[index] = view.getInt16(index * 2, true) / 32768;
  }
  return samples;
}

/** [-1,1] 浮点 → 16 位小端 PCM 字节，越界值会被夹取。 */
export function float32ToPcm16(samples: Float32Array): Uint8Array {
  const bytes = new Uint8Array(samples.length * 2);
  const view = new DataView(bytes.buffer);
  for (let index = 0; index < samples.length; index++) {
    // 乘 32768 而不是 32767：让 -1 正好落到最小码值，和 decode 的除数是同一个刻度
    const scaled = Math.round(samples[index] * 32768);
    view.setInt16(index * 2, Math.max(-32768, Math.min(32767, scaled)), true);
  }
  return bytes;
}
