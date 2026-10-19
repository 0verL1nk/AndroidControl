package dev.ling.controller.core

import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次远程操作会话的编排：连接 → 解码上屏 → 转发输入 → 统计 → 断线重连。
 *
 * 重连策略按验收标准（FR-012 / SC-006）：最多 15 次，总时长不超过 20 秒，
 * 且把次数报给界面——**弱网时不能让画面装作没事**。
 */
class RemoteSession(
    private val device: DeviceInfo,
    private val surface: Surface,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(frameWidth: Int, frameHeight: Int)
        fun onSizeChanged(frameWidth: Int, frameHeight: Int)
        fun onStats(fps: Double, kbps: Double, rttMs: Long)
        fun onReconnecting(attempt: Int, max: Int)
        fun onFailed(reason: String)
        /** 长辈端主动结束（对方点了"结束协助"）。这种不该自动重连。 */
        fun onEndedByPeer()
    }

    private val running = AtomicBoolean(false)
    private var client: SessionClient? = null
    private var decoder: VideoDecoder? = null
    private var control: ControlChannel? = null

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ connectLoop() }, "session").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { decoder?.stop() }
        runCatching { client?.close() }
        decoder = null
        control = null
        client = null
    }

    // ── 输入转发 ────────────────────────────────────────────────

    /** 坐标来自控件，内部换算成帧坐标；落在黑边上则忽略。 */
    fun sendTouch(action: Int, viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        val (fx, fy) = TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
            ?: return
        control?.touch(action, ControlMessageWriterPointer, fx, fy, frameWidth, frameHeight)
    }

    fun sendKey(keycode: Int) = control?.key(keycode)

    fun sendScroll(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float, vScroll: Float) {
        val (fx, fy) = TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
            ?: return
        control?.scroll(fx, fy, frameWidth, frameHeight, 0f, vScroll)
    }

    // ── 连接与重连 ──────────────────────────────────────────────

    private fun connectLoop() {
        var attempt = 0
        while (running.get()) {
            try {
                runSession()
                // 正常返回意味着对端结束了会话
                if (running.get()) {
                    listener.onEndedByPeer()
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "会话中断：${t.message}")
                if (!running.get()) return
                attempt++
                if (attempt > MAX_ATTEMPTS) {
                    listener.onFailed("连接已中断：${t.message ?: "未知原因"}")
                    return
                }
                listener.onReconnecting(attempt, MAX_ATTEMPTS)
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
    }

    /** 建立一次会话并阻塞到它结束。 */
    private fun runSession() {
        val session = SessionClient(device)
        session.connect()
        client = session
        Log.i(TAG, "已连接 ${device.name}")

        val channel = ControlChannel(session.controlOut, session.controlIn)
        control = channel
        Thread({ channel.drainIncoming { running.get() } }, "control-drain")
            .apply { isDaemon = true; start() }

        // 后连入的客户端需要一个新的 IDR 才能出画
        channel.requestKeyFrame()

        val videoDecoder = VideoDecoder(session.videoIn, surface)
        decoder = videoDecoder
        videoDecoder.onSizeChanged = VideoDecoder.SizeListener { w, h ->
            frameWidth = w
            frameHeight = h
            listener.onSizeChanged(w, h)
        }

        Thread({ statsLoop(videoDecoder) }, "stats").apply { isDaemon = true; start() }

        // 解码循环阻塞在这条线程上；尺寸未知时先按 0 报一次，拿到会话包会立刻再报
        frameWidth = 0
        frameHeight = 0
        videoDecoder.decodeLoop()
    }

    private fun statsLoop(decoder: VideoDecoder) {
        var lastFrames = 0L
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        while (running.get()) {
            Thread.sleep(STATS_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val elapsed = (now - lastAt).coerceAtLeast(1)
            val fps = (decoder.frameCount - lastFrames) * 1000.0 / elapsed
            val kbps = (decoder.bytesReceived - lastBytes) * 8.0 / elapsed
            lastFrames = decoder.frameCount
            lastBytes = decoder.bytesReceived
            lastAt = now

            if (frameWidth > 0 && frameHeight > 0) {
                listener.onConnected(frameWidth, frameHeight)
            }
            listener.onStats(fps, kbps, measureRtt())
        }
    }

    /**
     * 网络往返时延：另开一条短连接发 `PROBE` 并计时。
     * 这是**网络 RTT**，不是端到端画面延迟——界面上的文案要和它一致，不能含糊。
     */
    private fun measureRtt(): Long = runCatching {
        val start = System.nanoTime()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(device.host, device.controlPort), RTT_TIMEOUT_MS)
            socket.soTimeout = RTT_TIMEOUT_MS
            socket.getOutputStream().write("PROBE\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine()
        }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "RemoteSession"
        const val MAX_ATTEMPTS = 15
        const val RECONNECT_DELAY_MS = 1_200L
        const val STATS_INTERVAL_MS = 1_000L
        const val RTT_TIMEOUT_MS = 400
        const val ControlMessageWriterPointer = -2L
    }
}
