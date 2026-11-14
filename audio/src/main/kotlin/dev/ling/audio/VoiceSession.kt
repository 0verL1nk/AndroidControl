package dev.ling.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Build
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
class VoiceSession(
    private val socket: Socket,
    private val context: Context? = null,
    /**
     * true：把播放路由到扬声器（长辈端——手机放在桌上，必须外放）；
     * false：保持系统默认（控制端拿在耳边时用听筒更自然）。
     */
    private val speakerphone: Boolean = false,
) {

    private val running = AtomicBoolean(false)
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioManager: AudioManager? = null
    private var savedVolume = -1
    private var savedMode = AudioManager.MODE_INVALID
    private var savedSpeakerOn = false

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
            // 必须先进入通话模式再创建 AudioRecord：AEC 在普通模式下启用会把麦克风压成静音
            if (context != null) configureAudio(context)
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
            restoreAudio()
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
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, VoiceAudio.FRAME_BYTES * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "麦克风不可用（可能没有录音权限）"
        }
        if (!DEBUG_DISABLE_AEC && AcousticEchoCanceler.isAvailable()) {
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

    /**
     * 长辈端外放：通话音源默认走听筒，且通话音量经常停在 1/15。
     * 这里临时切到扬声器并把音量提到至少一半，会话结束时全部还原。
     */
    private fun configureAudio(context: Context) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        savedMode = manager.mode
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (speakerphone) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) runCatching { manager.setCommunicationDevice(speaker) }
            } else {
                @Suppress("DEPRECATION")
                savedSpeakerOn = manager.isSpeakerphoneOn
                @Suppress("DEPRECATION")
                runCatching { manager.isSpeakerphoneOn = true }
            }
        }
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val current = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        if (max > 0 && current < (max + 1) / 2) {
            savedVolume = current
            runCatching {
                manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max + 1) / 2, 0)
            }
        }
    }

    private fun restoreAudio() {
        val manager = audioManager ?: return
        if (savedVolume >= 0) {
            runCatching {
                manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = savedSpeakerOn }
        }
        if (savedMode != AudioManager.MODE_INVALID) {
            runCatching { manager.mode = savedMode }
        }
        audioManager = null
        savedVolume = -1
    }

    private fun captureLoop(recorder: AudioRecord, out: OutputStream) {
        val frame = ByteArray(VoiceAudio.FRAME_BYTES)
        var announced = false
        var silentFrames = 0
        var checkedFrames = 0
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
                // 有些机型（实测 Redmi K60 Ultra / HyperOS 3）启用 AcousticEchoCanceler
                // 后输出被压到 ±5 LSB（约 -76 dBFS）。会话前 5 秒里连续 20 帧（约 0.4s）
                // 峰值低于 50（约 -56 dBFS）就自动关掉 AEC，保住可用性。
                if (echoCanceler != null && checkedFrames < 250) {
                    checkedFrames++
                    var peak = 0
                    var index = 0
                    while (index + 1 < frame.size) {
                        val low = frame[index].toInt() and 0xFF
                        val high = frame[index + 1].toInt() shl 8
                        val value = low or high
                        val magnitude = if (value >= 0x8000) 0x10000 - value else value
                        if (magnitude > peak) peak = magnitude
                        index += 2
                    }
                    if (peak < 50) {
                        silentFrames++
                        if (silentFrames >= 20) {
                            Log.w(TAG, "回声消除把麦克风压到静音，已自动关闭 AEC")
                            runCatching { echoCanceler?.enabled = false }
                            runCatching { echoCanceler?.release() }
                            echoCanceler = null
                        }
                    } else {
                        silentFrames = 0
                    }
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
        /**
         * 诊断开关：某些机型（实测 Redmi K60 Ultra / HyperOS 3）用
         * VOICE_COMMUNICATION 音源时麦克风只返回全零，改用 VOICE_RECOGNITION 后正常；
         * 保留开关方便以后排查同类问题。
         */
        const val DEBUG_DISABLE_AEC = false
    }
}
