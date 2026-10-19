package dev.ling.controller.core

import android.util.Log
import dev.ling.audio.VoiceSession
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音通道：连中继的 `audio` 角色，然后把 socket 交给共享的 [VoiceSession]。
 *
 * 语音只有"通"和"不通"两种状态——中途断了必须让界面把按钮复位，
 * 否则用户会对着一个已经没声音的麦克风说话。
 */
class VoiceLink(private val device: DeviceInfo) {

    /** connected=true 表示双向已经通了；false 时 reason 说明原因（可为 null）。 */
    var onState: ((connected: Boolean, reason: String?) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "voice-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
    }

    private fun loop() {
        var reason: String? = null
        var connectedAt = 0L
        try {
            val opened = Channels.open(device, Role.Audio)
            socket = opened
            VoiceSession(opened).run {
                connectedAt = System.currentTimeMillis()
                onState?.invoke(true, null)
            }
        } catch (t: Throwable) {
            reason = t.message ?: "语音通道断开"
            Log.w(TAG, "语音未能建立：$reason")
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            // 刚连上就断：几乎都是对方没有录音权限，直接说人话
            if (reason == null && connectedAt != 0L && System.currentTimeMillis() - connectedAt < 1_500) {
                reason = "对方可能没有授权麦克风"
            }
            onState?.invoke(false, reason)
        }
    }

    private companion object {
        const val TAG = "VoiceLink"
    }
}
