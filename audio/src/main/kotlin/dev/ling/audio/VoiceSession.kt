package dev.ling.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import dev.ling.protocol.VoiceAudio
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双向语音：一条连接同时收发定长 PCM 帧（格式见 [VoiceAudio]）。
 *
 * 两端（长辈端 / 控制端）用的是同一份实现——配置只要有一处不同，听到的就是噪声，
 * 所以这里只允许一个事实来源。
 *
 * 几个必须的细节：
 * - 用 `VOICE_COMMUNICATION` 音源并开回声消除，否则长辈会从扬声器里听到自己；
 * - 读麦克风用阻塞模式，保证每次正好一帧，对端的定长读取才不会错位；
 * - 任一方向结束就关闭整条通道——语音"要么通、要么不通"，半死状态最迷惑人。
 */
class VoiceSession(private val socket: Socket) {

    private val running = AtomicBoolean(false)
    private var echoCanceler: AcousticEchoCanceler? = null

    /**
     * 阻塞到语音结束（断开或出错）。
     *
     * @param onReady 麦克风/扬声器都就绪、可以开始说话时回调一次。
     * @throws IllegalStateException 麦克风/扬声器不可用（通常是没授权）
     */
    fun run(onReady: (() -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) return

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        try {
            recorder = openMicrophone()
            player = openSpeaker()
            Log.i(TAG, "语音通道已就绪（双向 PCM）")
            onReady?.invoke()
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val capture = Thread({ captureLoop(recorder, out) }, "voice-capture")
                .apply { isDaemon = true; start() }
            playbackLoop(player, input)
            capture.interrupt()
            Log.i(TAG, "语音通道结束")
        } catch (t: Throwable) {
            // 这里绝不能把异常抛出去：Android 的默认未捕获处理器会杀掉整个进程，
            // 一次"对方没授权麦克风"不该让长辈端 App 崩掉。
            Log.w(TAG, "语音通道不可用：${t.message}")
        } finally {
            running.set(false)
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching { echoCanceler?.release() }
            runCatching { socket.close() }
        }
    }

    private fun openMicrophone(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, VoiceAudio.FRAME_BYTES * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "麦克风不可用（可能没有录音权限）"
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)
                ?.apply { enabled = true }
        }
        recorder.startRecording()
        return recorder
    }

    private fun openSpeaker(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(VoiceAudio.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val player = AudioTrack(
            attributes,
            format,
            maxOf(minBuffer, VoiceAudio.FRAME_BYTES * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        check(player.state == AudioTrack.STATE_INITIALIZED) { "扬声器不可用" }
        player.play()
        return player
    }

    private fun captureLoop(recorder: AudioRecord, out: OutputStream) {
        val frame = ByteArray(VoiceAudio.FRAME_BYTES)
        var announced = false
        try {
            while (running.get()) {
                // 阻塞读满一帧；短帧会让对端的定长读取错位
                val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read < 0) break
                if (read != frame.size) continue
                if (!announced) {
                    announced = true
                    Log.i(TAG, "麦克风上行已有数据")
                }
                out.write(frame)
                out.flush()
            }
        } catch (t: Throwable) {
            Log.i(TAG, "麦克风上行结束：${t.message}")
        }
    }

    private fun playbackLoop(player: AudioTrack, input: InputStream) {
        val frame = ByteArray(VoiceAudio.FRAME_BYTES)
        var announced = false
        try {
            while (running.get()) {
                var read = 0
                while (read < frame.size) {
                    val n = input.read(frame, read, frame.size - read)
                    if (n < 0) return
                    read += n
                }
                if (!announced) {
                    announced = true
                    Log.i(TAG, "语音下行已有数据")
                }
                player.write(frame, 0, frame.size)
            }
        } catch (t: Throwable) {
            Log.i(TAG, "语音下行结束：${t.message}")
        }
    }

    private companion object {
        const val TAG = "VoiceSession"
    }
}
