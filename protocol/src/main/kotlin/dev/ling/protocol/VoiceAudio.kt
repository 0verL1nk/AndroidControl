package dev.ling.protocol

/**
 * 双向语音的音频参数。两端必须完全一致，否则听到的是噪声。
 *
 * 选 PCM 而不是 Opus：没有原生依赖、没有编解码延迟，16 kHz 单声道约 256 kbps，
 * 家庭 Wi-Fi 与蜂窝网络都承受得起；语音对讲的瓶颈本来就是网络往返，不是码率。
 * 帧长 20 ms 是语音的常用值：再短会增加系统调用与包头开销，再长会明显增加延迟。
 */
object VoiceAudio {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_SAMPLES = SAMPLE_RATE / 50 // 20 ms
    const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE
}
