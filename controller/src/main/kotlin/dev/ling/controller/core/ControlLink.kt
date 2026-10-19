package dev.ling.controller.core

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制通道的连接管理：**自己断了自己重连**，视频通道完全不受影响。
 *
 * 为什么必须独立：控制通道在不操作时是安静的，而视频通道一直有数据。
 * 任何中间环节只要对"空闲的长连接"不友好（NAT 表项老化、代理回收、
 * 甚至我们自己的 `tools/tcp-relay.py` 曾经有个 5 秒读超时的 bug），
 * 先死的必然是控制通道。把两条流绑在一起的话，控制一断整个会话就重连，
 * 画面跟着闪断——用户只会觉得"软件不稳定"，很难想到是连接被回收。
 *
 * 重连是主动的：一旦读到 EOF 立刻重建，空窗只有几百毫秒；
 * 如果发现连接总是"空闲一段时间后"被断开，再按观测值启用空闲心跳。
 */
class ControlLink(private val device: DeviceInfo) {

    @Volatile
    private var channel: ControlChannel? = null

    /**
     * 持有 Socket 的强引用。
     *
     * 只拿它的输入输出流而不保留引用，JVM 在最后一次使用后就可以判定对象不可达；
     * Socket 的关闭时机应当由本类显式控制，而不是交给 GC。
     */
    private var socket: java.net.Socket? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var heartbeatThread: Thread? = null

    /** 最近一次真正往控制通道写数据的时间。 */
    @Volatile
    private var lastSendAt = 0L

    /** 本次连接建立的时间。 */
    private var connectedAt = 0L

    /**
     * 空闲心跳周期：0 表示不发心跳。
     *
     * 中间设备回收空闲连接的时长千差万别（实测某代理 5 秒，运营商 NAT 常见几分钟），
     * 写死一个值要么太频（白白制造关键帧、耗电），要么不够（照样被掐）。
     * 所以从每次断开里学：只有"因为空闲被断开"才启用，周期取观测值的一半。
     */
    @Volatile
    private var heartbeatMs = 0L

    /** 每次连上（包括重连）都会回调，用来补一个关键帧请求。 */
    var onConnected: (() -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "control-link").apply { isDaemon = true; start() }
        heartbeatThread = Thread({ heartbeatLoop() }, "control-heartbeat")
            .apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        channel?.close()
        runCatching { socket?.close() }
        socket = null
        channel = null
        thread?.interrupt()
        heartbeatThread?.interrupt()
    }

    // ── 输入转发：没连上时安静丢弃，由重连兜底 ──────────────────

    fun touch(action: Int, pointerId: Long, x: Int, y: Int, width: Int, height: Int) =
        send { it.touch(action, pointerId, x, y, width, height) }

    fun key(keycode: Int) = send { it.key(keycode) }

    fun scroll(x: Int, y: Int, width: Int, height: Int, hScroll: Float, vScroll: Float) =
        send { it.scroll(x, y, width, height, hScroll, vScroll) }

    fun requestKeyFrame() = send { it.requestKeyFrame() }

    /**
     * 点亮对方屏幕。KEYCODE_WAKEUP 在屏幕已经亮着时是空操作，
     * 所以可以放心在每次连上、以及用户点"唤醒"时发送。
     */
    fun wakeScreen() = send { it.key(ControlChannel.Keys.WAKEUP) }

    private fun send(action: (ControlChannel) -> Unit) {
        val target = channel ?: return
        lastSendAt = System.currentTimeMillis()
        action(target)
    }

    private fun loop() {
        while (running.get()) {
            var connected: ControlChannel? = null
            try {
                val opened = Channels.open(device, Role.Control)
                socket = opened
                connected = ControlChannel(opened.getOutputStream(), opened.getInputStream())
                channel = connected
                connectedAt = System.currentTimeMillis()
                lastSendAt = connectedAt
                Log.i(TAG, "控制通道已连接（本地端口 ${opened.localPort}）")
                onConnected?.invoke()

                // 阻塞到这条连接结束（对端关闭或异常）
                connected.drainIncoming { running.get() }
                observeIdleDeath()
                Log.i(TAG, "控制通道断开（本地端口 ${socket?.localPort}），准备重连")
            } catch (t: Throwable) {
                Log.w(TAG, "控制通道建立失败：${t.message}")
            } finally {
                connected?.close()
                runCatching { socket?.close() }
                socket = null
                channel = null
            }

            if (!running.get()) return
            Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    /**
     * 这条连接是被"空闲"掐掉的吗？是的话按观测到的存活时长设心跳。
     * 一直在操作却断了，说明不是空闲回收，不启用心跳。
     */
    private fun observeIdleDeath() {
        val now = System.currentTimeMillis()
        val idleFor = now - maxOf(lastSendAt, connectedAt)
        heartbeatMs = if (idleFor >= IDLE_DEATH_THRESHOLD_MS) {
            (idleFor / 2).coerceIn(MIN_HEARTBEAT_MS, MAX_HEARTBEAT_MS)
        } else {
            0L
        }
        if (heartbeatMs > 0) {
            Log.i(TAG, "空闲 ${idleFor}ms 后被断开，心跳周期设为 ${heartbeatMs}ms")
        }
    }

    /**
     * 通道闲够一个心跳周期就发一次关键帧请求。
     *
     * 为什么用 RESET_VIDEO：scrcpy 的控制消息里只有它是幂等且无副作用的
     * （"再给我一个关键帧"）。代价是每次多一个 IDR，所以只在真正需要时才发。
     */
    private fun heartbeatLoop() {
        while (running.get()) {
            Thread.sleep(HEARTBEAT_TICK_MS)
            val period = heartbeatMs
            if (period <= 0) continue
            val target = channel ?: continue
            val now = System.currentTimeMillis()
            if (now - lastSendAt >= period) {
                lastSendAt = now
                target.requestKeyFrame()
            }
        }
    }

    private companion object {
        const val TAG = "ControlLink"

        /** 要短：控制通道断着的时间就是用户"点了没反应"的时间。 */
        const val RECONNECT_DELAY_MS = 300L

        /** 断前空闲超过这么久，才认为是"空闲回收"而不是用户操作中出错。 */
        const val IDLE_DEATH_THRESHOLD_MS = 2_000L

        const val MIN_HEARTBEAT_MS = 1_000L
        const val MAX_HEARTBEAT_MS = 60_000L

        const val HEARTBEAT_TICK_MS = 500L
    }
}
