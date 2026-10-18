package dev.ling.controller.core

import android.util.Log
import dev.ling.protocol.ControlMessageWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * 控制通道：把本地交互翻译成 scrcpy 控制消息发给长辈端。
 *
 * 坐标必须是**视频帧尺寸**下的坐标，由 [TouchMapper] 换算好再传进来。
 */
class ControlChannel(
    private val output: OutputStream,
    private val input: InputStream,
) {

    /** 连续移动事件限流：两帧之间至少间隔这么久，避免刷爆控制通道。 */
    private var lastMoveAt = 0L

    fun touch(action: Int, pointerId: Long, x: Int, y: Int, width: Int, height: Int) {
        if (action == ControlMessageWriter.ACTION_MOVE) {
            val now = System.currentTimeMillis()
            if (now - lastMoveAt < MOVE_INTERVAL_MS) return
            lastMoveAt = now
        }
        send(ControlMessageWriter.touch(action, pointerId, x, y, width, height))
    }

    fun tap(x: Int, y: Int, width: Int, height: Int) {
        touch(ControlMessageWriter.ACTION_DOWN, ControlMessageWriter.POINTER_ID_GENERIC_FINGER, x, y, width, height)
        touch(ControlMessageWriter.ACTION_UP, ControlMessageWriter.POINTER_ID_GENERIC_FINGER, x, y, width, height)
    }

    fun key(keycode: Int) {
        send(ControlMessageWriter.keycode(ControlMessageWriter.KEY_ACTION_DOWN, keycode))
        send(ControlMessageWriter.keycode(ControlMessageWriter.KEY_ACTION_UP, keycode))
    }

    fun scroll(x: Int, y: Int, width: Int, height: Int, hScroll: Float, vScroll: Float) {
        send(ControlMessageWriter.scroll(x, y, width, height, hScroll, vScroll))
    }

    @Synchronized
    private fun send(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "控制消息发送失败：${t.message}")
        }
    }

    /**
     * 引擎会在控制通道上回传设备消息（目前主要是剪贴板）。
     * M1 不处理，但**必须持续读走**，否则缓冲区写满会拖住引擎。
     */
    fun drainIncoming(onStop: () -> Boolean) {
        val buffer = ByteArray(1024)
        try {
            while (onStop()) {
                if (input.read(buffer) < 0) break
            }
        } catch (t: Throwable) {
            Log.i(TAG, "控制通道读取结束：${t.message}")
        }
    }

    /** Android 键码。 */
    object Keys {
        const val BACK = 4
        const val HOME = 3
        const val APP_SWITCH = 187
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val POWER = 26
        const val WAKEUP = 224
        const val ENTER = 66
    }

    private companion object {
        const val TAG = "ControlChannel"
        const val MOVE_INTERVAL_MS = 8L // ≈120Hz 上限
    }
}
