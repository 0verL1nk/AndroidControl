package dev.ling.controller.core

import android.util.Log
import dev.ling.protocol.ControlMessageWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制通道：把本地交互翻译成 scrcpy 控制消息发给长辈端。
 *
 * 坐标必须是**视频帧尺寸**下的坐标，由 [TouchMapper] 换算好再传进来。
 *
 * 所有写操作都在**独立的写线程**上串行执行，调用方（界面主线程）只入队。
 * 这不是性能优化，而是正确性要求：Android 禁止在主线程做网络 I/O，
 * 直接在主线程 `output.write()` 会抛 `NetworkOnMainThreadException`
 * （它的 message 是 null，日志里只看到"控制消息发送失败：null"，非常难查）。
 */
class ControlChannel(
    private val output: OutputStream,
    private val input: InputStream,
) {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)

    private val writer = Thread({ writerLoop() }, "control-writer").apply {
        isDaemon = true
        start()
    }

    /** 连续移动事件限流：两帧之间至少间隔这么久，避免刷爆控制通道。 */
    private var lastMoveAt = 0L

    fun touch(action: Int, pointerId: Long, x: Int, y: Int, width: Int, height: Int) {
        val droppable = action == ControlMessageWriter.ACTION_MOVE
        if (droppable) {
            val now = System.currentTimeMillis()
            if (now - lastMoveAt < MOVE_INTERVAL_MS) return
            lastMoveAt = now
        }
        send(ControlMessageWriter.touch(action, pointerId, x, y, width, height), droppable)
    }

    fun tap(x: Int, y: Int, width: Int, height: Int) {
        touch(ControlMessageWriter.ACTION_DOWN, ControlMessageWriter.POINTER_ID_GENERIC_FINGER, x, y, width, height)
        touch(ControlMessageWriter.ACTION_UP, ControlMessageWriter.POINTER_ID_GENERIC_FINGER, x, y, width, height)
    }

    fun key(keycode: Int) {
        send(ControlMessageWriter.keycode(ControlMessageWriter.KEY_ACTION_DOWN, keycode), droppable = false)
        send(ControlMessageWriter.keycode(ControlMessageWriter.KEY_ACTION_UP, keycode), droppable = false)
    }

    fun scroll(x: Int, y: Int, width: Int, height: Int, hScroll: Float, vScroll: Float) {
        send(ControlMessageWriter.scroll(x, y, width, height, hScroll, vScroll), droppable = true)
    }

    /** 输入一整段文字（UTF-8，不带结尾 NUL）。空串直接忽略，避免无意义的消息。 */
    fun text(text: String) {
        if (text.isEmpty()) return
        send(ControlMessageWriter.injectText(text), droppable = false)
    }

    /** 请求引擎重新出一个关键帧（见 ControlMessageWriter.resetVideo 的说明）。 */
    fun requestKeyFrame() = send(ControlMessageWriter.resetVideo(), droppable = false)

    private fun send(bytes: ByteArray, droppable: Boolean) {
        if (!running.get()) return
        if (queue.offer(bytes)) return
        if (droppable) {
            Log.w(TAG, "控制消息队列已满，丢弃一条移动事件")
            return
        }
        // 按键/关键帧请求不能丢：挤掉最旧的一条给它腾位置（顺序仍然正确）。
        Log.w(TAG, "控制消息队列已满，挤掉最旧的一条")
        queue.poll()
        queue.offer(bytes)
    }

    private fun writerLoop() {
        while (running.get()) {
            val bytes = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            try {
                output.write(bytes)
                output.flush()
            } catch (t: Throwable) {
                // 常见的只有"连接已关闭"，记下类型便于区分真正的 bug
                Log.w(TAG, "控制消息发送失败：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** 断开时调用：写线程退出，后续输入直接丢弃。 */
    fun close() {
        running.set(false)
        writer.interrupt()
    }

    /**
     * 引擎会在控制通道上回传设备消息（目前主要是剪贴板）。
     * M1 不处理，但**必须持续读走**，否则缓冲区写满会拖住引擎。
     */
    fun drainIncoming(onStop: () -> Boolean) {
        val buffer = ByteArray(1024)
        try {
            while (onStop()) {
                if (input.read(buffer) < 0) {
                    Log.i(TAG, "控制通道已被对方关闭（EOF）")
                    break
                }
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

        /** 队列上限：按 120Hz 移动事件约 4 秒的缓冲，正常情况下远达不到。 */
        const val QUEUE_CAPACITY = 512
    }
}
